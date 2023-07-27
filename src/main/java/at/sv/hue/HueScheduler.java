package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Command(name = "HueScheduler", version = "0.8.0", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final Map<Integer, List<ScheduledState>> lightStates;
    private final ConcurrentHashMap<Integer, List<Runnable>> onStateWaitingList;
    @Parameters(description = "The IP address of your Philips Hue Bridge.")
    String ip;
    @Parameters(description = "The Philips Hue Bridge username created for Hue Scheduler during setup.")
    String username;
    @Parameters(paramLabel = "FILE", description = "The configuration file containing your schedules.")
    Path inputFile;
    @Option(names = "--lat", required = true, description = "The latitude of your location.")
    double latitude;
    @Option(names = "--long", required = true, description = "The longitude of your location.")
    double longitude;
    @Option(names = "--elevation", defaultValue = "0.0", description = "The optional elevation (in meters) of your location, " +
            "used to provide more accurate sunrise and sunset times.")
    double elevation;
    @Option(names = "--max-requests-per-second", paramLabel = "<requests>", defaultValue = "10.0",
            description = "The maximum number of PUT API requests to perform per second. Default and recommended: " +
                    "${DEFAULT-VALUE} requests per second.")
    double requestsPerSecond;
    @Option(names = "--control-group-lights-individually", defaultValue = "false",
            description = "Experimental: If the lights in a group should be controlled individually instead of using broadcast messages." +
                    " This might improve performance. Default: ${DEFAULT-VALUE}")
    boolean controlGroupLightsIndividually;
    @Option(names = "--track-user-changes", defaultValue = "true",
            description = "Experimental: TODO." +
                    ". Default: ${DEFAULT-VALUE}")
    boolean trackUserModifications;
    @Option(names = "--power-on-reschedule-delay", paramLabel = "<delay>", defaultValue = "150",
            description = "The delay in ms after the light on-event was received between each confirmation. Default: ${DEFAULT-VALUE} ms.")
    int powerOnRescheduleDelayInMs;
    @Option(names = "--bridge-failure-retry-delay", paramLabel = "<delay>", defaultValue = "10",
            description = "The delay in seconds for retrying an API call, if the bridge could not be reached due to " +
                    "network failure, or if it returned an API error code. Default: ${DEFAULT-VALUE} seconds.")
    int bridgeFailureRetryDelayInSeconds;
    @Option(names = "--multi-color-adjustment-delay", paramLabel = "<delay>", defaultValue = "4",
            description = "The adjustment delay in seconds for each light in a group when using the multi_color effect." +
                    " Adjust to change the hue values of 'neighboring' lights. Default: ${DEFAULT-VALUE} seconds.")
    int multiColorAdjustmentDelay;
    private HueApi hueApi;
    private StateScheduler stateScheduler;
    private final ManualOverrideTracker manualOverrideTracker;
    private final HueEventListener hueEventListener;
    private StartTimeProvider startTimeProvider;
    private Supplier<ZonedDateTime> currentTime;

    public HueScheduler() {
        lightStates = new HashMap<>();
        onStateWaitingList = new ConcurrentHashMap<>();
        manualOverrideTracker = new ManualOverrideTrackerImpl();
        hueEventListener = new HueEventListenerImpl(manualOverrideTracker, onStateWaitingList::remove);
    }

    public HueScheduler(HueApi hueApi, StateScheduler stateScheduler,
                        StartTimeProvider startTimeProvider, Supplier<ZonedDateTime> currentTime,
                        double requestsPerSecond, boolean controlGroupLightsIndividually,
                        boolean trackUserModifications, int powerOnRescheduleDelayInMs,
                        int bridgeFailureRetryDelayInSeconds, int multiColorAdjustmentDelay) {
        this();
        this.hueApi = hueApi;
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        this.requestsPerSecond = requestsPerSecond;
        this.controlGroupLightsIndividually = controlGroupLightsIndividually;
        this.trackUserModifications = trackUserModifications;
        this.powerOnRescheduleDelayInMs = powerOnRescheduleDelayInMs;
        this.bridgeFailureRetryDelayInSeconds = bridgeFailureRetryDelayInSeconds;
        this.multiColorAdjustmentDelay = multiColorAdjustmentDelay;
    }

    public static void main(String[] args) {
        int execute = new CommandLine(new HueScheduler()).execute(args);
        if (execute != 0) {
            System.exit(execute);
        }
    }

    @Override
    public void run() {
        OkHttpClient httpsClient = createHttpsClient();
        hueApi = new HueApiImpl(new HttpsResourceProviderImpl(httpsClient), ip, username, RateLimiter.create(requestsPerSecond));
        startTimeProvider = createStartTimeProvider(latitude, longitude, elevation);
        stateScheduler = createStateScheduler();
        currentTime = ZonedDateTime::now;
        new LightStateEventTrackerImpl(ip, username, httpsClient, new HueRawEventHandler(hueEventListener)).start();
        assertInputIsReadable();
        assertConnectionAndStart();
    }

    private OkHttpClient createHttpsClient() {
        try {
            return HueApiHttpsClientFactory.createHttpsClient(ip);
        } catch (Exception e) {
            System.err.println("Failed to create https client: " + e.getLocalizedMessage());
            System.exit(1);
        }
        return null;
    }

    private StartTimeProviderImpl createStartTimeProvider(double latitude, double longitude, double elevation) {
        return new StartTimeProviderImpl(new SunTimesProviderImpl(latitude, longitude, elevation));
    }

    private StateSchedulerImpl createStateScheduler() {
        return new StateSchedulerImpl(Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()), ZonedDateTime::now);
    }

    private void assertInputIsReadable() {
        if (!Files.isReadable(inputFile)) {
            System.err.println("Given input file '" + inputFile.toAbsolutePath() + "' does not exist or is not readable!");
            System.exit(1);
        }
    }

    private void assertConnectionAndStart() {
        if (!assertConnection()) {
            stateScheduler.schedule(this::assertConnectionAndStart, currentTime.get().plusSeconds(5), null);
        } else {
            parseInput();
            start();
        }
    }

    private boolean assertConnection() {
        try {
            hueApi.assertConnection();
            LOG.info("Connected to bridge at {}.", ip);
        } catch (BridgeConnectionFailure e) {
            LOG.warn("Bridge not reachable: '" + e.getCause().getLocalizedMessage() + "'. Retrying in 5s.");
            return false;
        } catch (BridgeAuthenticationFailure e) {
            System.err.println("Bridge connection rejected: 'Unauthorized user'. Please make sure you use the correct username from the setup process," +
                    " or try to generate a new one.");
            System.exit(3);
        }
        return true;
    }

    private void parseInput() {
        try {
            Files.lines(inputFile)
                 .filter(s -> !s.isEmpty())
                 .filter(s -> !s.startsWith("//") && !s.startsWith("#"))
                 .forEachOrdered(input -> {
                     try {
                         addState(input);
                     } catch (Exception e) {
                         System.err.println("Failed to parse configuration line '" + input + "':\n" + e.getClass().getSimpleName() + ": " + e.getLocalizedMessage());
                         System.exit(2);
                     }
                 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void addState(String input) {
        new InputConfigurationParser(startTimeProvider, hueApi).parse(input).forEach(state -> {
            int updateId;
            if (state.isGroupState()) {
                updateId = getGroupId(state.getUpdateId());
            } else {
                updateId = state.getUpdateId();
            }
            lightStates.computeIfAbsent(updateId, ArrayList::new).add(state);
        });
    }

    private int getGroupId(int id) {
        return id + 1000;
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        lightStates.forEach((id, states) -> scheduleInitialStartup(states, now));
        scheduleSunDataInfoLog();
    }

    private void scheduleInitialStartup(List<ScheduledState> states, ZonedDateTime now) {
        calculateAndSetEndTimes(states, now);
        scheduleStatesStartingToday(states, now);
        scheduleStatesNotStartingToday(states, now);
    }

    private void calculateAndSetEndTimes(List<ScheduledState> states, ZonedDateTime now) {
        HashSet<ScheduledState> alreadyProcessedStates = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            ZonedDateTime day = now.plusDays(i);
            List<ScheduledState> statesStartingOnDay = getStatesStartingOnDay(states, day, DayOfWeek.from(day));
            statesStartingOnDay.stream()
                               .filter(state -> !alreadyProcessedStates.contains(state))
                               .forEach(state -> calculateAndSetEndTime(state, states, state.getStart(now)));
            alreadyProcessedStates.addAll(statesStartingOnDay);
        }
    }

    private List<ScheduledState> getStatesStartingOnDay(List<ScheduledState> states, ZonedDateTime now, DayOfWeek... days) {
        return states.stream()
                     .filter(state -> state.isScheduledOn(days))
                     .sorted(Comparator.comparing(scheduledState -> scheduledState.getStart(now)))
                     .collect(Collectors.toList());
    }

    private void calculateAndSetEndTime(ScheduledState state, List<ScheduledState> states, ZonedDateTime start) {
        new EndTimeAdjuster(state, start, (day, dayOfWeeks) -> getStatesStartingOnDay(states, day, dayOfWeeks)).calculateAndSetEndTime();
    }

    private void scheduleStatesStartingToday(List<ScheduledState> states, ZonedDateTime now) {
        new InitialTodayScheduler(now, (day, dayOfWeeks) -> getStatesStartingOnDay(states, day, dayOfWeeks), this::schedule,
                this::scheduleNextDay).scheduleStatesStartingToday();
    }

    private void scheduleStatesNotStartingToday(List<ScheduledState> states, ZonedDateTime now) {
        states.stream()
              .filter(state -> doesNotStartToday(state, now))
              .sorted(Comparator.comparing(state -> state.getStart(now)))
              .forEach(this::schedule);
    }

    private boolean doesNotStartToday(ScheduledState state, ZonedDateTime now) {
        return !DayOfWeek.from(state.getStart(now)).equals(DayOfWeek.from(now));
    }

    private void schedule(ScheduledState state) {
        ZonedDateTime now = currentTime.get();
        state.updateLastStart(now);
        schedule(state, getMs(state.getDelayInSeconds(now)));
    }

    private void schedule(ScheduledState state, long delayInMs) {
        if (state.isNullState()) return;
        if (shouldLogScheduleDebugMessage(delayInMs)) {
            LOG.debug("Schedule {} in {}", state, Duration.ofMillis(delayInMs));
        }
        stateScheduler.schedule(() -> {
            if (state.endsAfter(currentTime.get())) {
                LOG.debug("{} already ended", state);
                reschedule(state);
                return;
            }
            if (lightHasBeenManuallyOverriddenBefore(state)) {
                if (state.isOff()) {
                    LOG.debug("{} state has been manually overridden before, skip off-state for this day: {}", state.getFormattedName(), state);
                    reschedule(state, true);
                } else {
                    LOG.debug("{} state has been manually overridden before, skip update and retry when back online", state.getFormattedName());
                    retryWhenBackOn(state);
                }
                return;
            }
            boolean success;
            LightState lightState = null;
            try {
                if (stateIsNotEnforced(state) && stateHasBeenManuallyOverriddenSinceLastSeen(state)) {
                    LOG.debug("{} state has been manually overridden, pause update until light is turned off and on again", state.getFormattedName());
                    manualOverrideTracker.onManuallyOverridden(state.getStatusId());
                    retryWhenBackOn(state);
                    return;
                }
                success = putState(state);
                if (success) {
                    lightState = hueApi.getLightState(state.getStatusId());
                }
            } catch (BridgeConnectionFailure e) {
                LOG.warn("Bridge not reachable, retrying in {}s.", bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            } catch (HueApiFailure e) {
                LOG.error("Hue api call failed: '{}'. Retrying in {}s.", e.getLocalizedMessage(), bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            }
            if (success && state.isOff() && lightState.isUnreachableOrOff()) {
                LOG.info("Turned off {}, or was already off", state.getFormattedName());
                reschedule(state, true);
                return;
            }
            if (!success || lightState.isUnreachableOrOff()) {
                LOG.trace("{} not reachable or off, retry when light turns back on", state.getFormattedName());
                retryWhenBackOn(state);
                return;
            }
            LOG.info("Set {}", state);
            if (shouldAdjustMultiColorLoopOffset(state)) {
                scheduleMultiColorLoopOffsetAdjustments(state.getGroupLights(), 1);
            }
            state.setLastSeen(currentTime.get());
            manualOverrideTracker.onAutomaticallyAssigned(state.getStatusId());
            retryWhenBackOn(ScheduledState.createTemporaryCopy(state, currentTime.get(), state.getEnd()));
            boolean forceNextDay = currentTime.get().toLocalTime().isAfter(state.getLastStart().toLocalTime()) || currentTime.get().toLocalTime().equals(state.getLastStart().toLocalTime());
            reschedule(state, forceNextDay);
        }, currentTime.get().plus(delayInMs, ChronoUnit.MILLIS), state.getEnd());
    }

    private boolean lightHasBeenManuallyOverriddenBefore(ScheduledState state) {
        return trackUserModifications && manualOverrideTracker.isManuallyOverridden(state.getStatusId());
    }

    private boolean stateIsNotEnforced(ScheduledState state) {
        return trackUserModifications && !manualOverrideTracker.shouldEnforceSchedule(state.getStatusId()); // TODO: I think it would make sense to have a logic that also ENFORCES states regardless of user changes
    }

    private boolean stateHasBeenManuallyOverriddenSinceLastSeen(ScheduledState currentState) {
        ScheduledState lastSeenState = getLastSeenState(currentState);
        if (lastSeenState == null) {
            return false;
        }
        // todo: this does not really work reliably for groups, as it could be that just this light is not reachable because it was manually turned off
        return lastSeenState.lightStateDiffers(hueApi.getLightState(currentState.getStatusId()));
    }

    private ScheduledState getLastSeenState(ScheduledState currentState) {
        return getLightStatesForId(currentState).stream()
                                                .sorted(Comparator.comparing(ScheduledState::getLastSeen, Comparator.nullsFirst(ZonedDateTime::compareTo).reversed()))
                                                .filter(state -> state.getLastSeen() != null)
                                                .findFirst()
                                                .orElse(null);
    }

    private boolean shouldLogScheduleDebugMessage(long delayInMs) {
        return delayInMs == 0 || delayInMs > 5000 && delayInMs != getMs(bridgeFailureRetryDelayInSeconds);
    }

    private void reschedule(ScheduledState state) {
        reschedule(state, false);
    }

    private void reschedule(ScheduledState state, boolean forceNextDay) {
        if (state.isTemporary()) return;
        ZonedDateTime now = currentTime.get();
        ZonedDateTime nextStart = getNextStart(state, now, forceNextDay);
        state.updateLastStart(nextStart); // todo: we should write a test, that his value is now set to the next start instead of now
        calculateAndSetEndTime(state, getLightStatesForId(state), nextStart);
        schedule(state, getMs(getDelayInSeconds(now, nextStart)));
    }

    private ZonedDateTime getNextStart(ScheduledState state, ZonedDateTime now, boolean forceNextDay) {
        if (forceNextDay || shouldScheduleNextDay(state)) {
            return state.getStart(now.plusDays(1));
        } else {
            return state.getStart(now);
        }
    }

    private boolean shouldScheduleNextDay(ScheduledState state) {
        List<ScheduledState> todaysStates = getStatesStartingToday(state);
        if (isLastState(state, todaysStates)) {
            return false;
        }
        return nextStateAlreadyStarted(state, todaysStates);
    }

    private List<ScheduledState> getStatesStartingToday(ScheduledState state) {
        ZonedDateTime now = currentTime.get();
        return getStatesStartingOnDay(getLightStatesForId(state), now, DayOfWeek.from(now))
                .stream()
                .sorted(Comparator.comparing(scheduledState -> scheduledState.getStart(now)))
                .collect(Collectors.toList());
    }

    private boolean isLastState(ScheduledState state, List<ScheduledState> todaysStates) {
        return todaysStates.indexOf(state) == todaysStates.size() - 1;
    }

    private boolean nextStateAlreadyStarted(ScheduledState state, List<ScheduledState> todaysStates) {
        return todaysStates.get(todaysStates.indexOf(state) + 1).isInThePastOrNow(currentTime.get());
    }

    private static long getDelayInSeconds(ZonedDateTime now, ZonedDateTime nextStart) {
        Duration between = Duration.between(now, nextStart);
        if (between.isNegative()) {
            return 0;
        } else {
            return between.getSeconds();
        }
    }

    private void scheduleNextDay(ScheduledState state) {
        reschedule(state, true);
    }

    private List<ScheduledState> getLightStatesForId(ScheduledState state) {
        return lightStates.get(getLightId(state));
    }

    private int getLightId(ScheduledState state) {
        if (state.isGroupState()) {
            return getGroupId(state.getUpdateId());
        } else {
            return state.getUpdateId();
        }
    }

    private long getMs(long seconds) {
        return seconds * 1000L;
    }

    private boolean putState(ScheduledState state) {
        if (state.isGroupState() && controlGroupLightsIndividually) {
            for (Integer id : state.getGroupLights()) {
                try {
                    if (!putState(id, state, false)) {
                        LOG.trace("Group light with id {} is off, could not update state", id);
                    }
                } catch (HueApiFailure e) {
                    LOG.trace("Unsupported api call for light id {}: {}", id, e.getLocalizedMessage());
                }
            }
            return true;
        } else {
            return putState(state.getUpdateId(), state, state.isGroupState());
        }
    }

    private boolean putState(int updateId, ScheduledState state, boolean groupState) {
        return hueApi.putState(updateId, state.getBrightness(), state.getCt(), state.getX(), state.getY(),
                state.getHue(), state.getSat(), state.getEffect(), state.getOn(), state.getTransitionTime(currentTime.get()),
                groupState);
    }

    private void retry(ScheduledState state, long delayInMs) {
        schedule(state, delayInMs);
    }

    private void retryWhenBackOn(ScheduledState state) {
        onStateWaitingList.computeIfAbsent(state.getStatusId(), id -> new ArrayList<>()).add(() -> schedule(state, powerOnRescheduleDelayInMs));
    }

    private boolean shouldAdjustMultiColorLoopOffset(ScheduledState state) {
        return state.isMultiColorLoop() && state.getGroupLights().size() > 1;
    }

    private void scheduleMultiColorLoopOffsetAdjustments(List<Integer> groupLights, int i) {
        stateScheduler.schedule(() -> offsetMultiColorLoopForLight(groupLights, i), currentTime.get().plusSeconds(multiColorAdjustmentDelay), null);
    }

    private void offsetMultiColorLoopForLight(List<Integer> groupLights, int i) {
        Integer light = groupLights.get(i);
        LightState lightState = hueApi.getLightState(light);
        boolean delayNext = false;
        if (lightState.isOn() && lightState.isColorLoopEffect()) {
            turnOff(light);
            scheduleTurnOn(light);
            delayNext = true;
        }
        if (i + 1 >= groupLights.size()) return;
        if (delayNext) {
            scheduleMultiColorLoopOffsetAdjustments(groupLights, i + 1);
        } else {
            offsetMultiColorLoopForLight(groupLights, i + 1);
        }
    }

    private void turnOff(Integer light) {
        putOnState(light, false, null);
    }

    private void scheduleTurnOn(Integer light) {
        stateScheduler.schedule(() -> putOnState(light, true, "colorloop"), currentTime.get().plus(300, ChronoUnit.MILLIS), null);
    }

    private void putOnState(int light, boolean on, String effect) {
        hueApi.putState(light, null, null, null, null, null, null, effect, on, null, false);
    }

    private void scheduleSunDataInfoLog() {
        logSunDataInfo();
        ZonedDateTime now = currentTime.get();
        ZonedDateTime midnight = ZonedDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT, now.getZone());
        long delay = Duration.between(now, midnight).toMinutes();
        stateScheduler.scheduleAtFixedRate(this::logSunDataInfo, delay + 1, 60 * 24, TimeUnit.MINUTES);
    }

    private void logSunDataInfo() {
        LOG.info("Current sun times:\n{}", startTimeProvider.toDebugString(currentTime.get()));
    }

    HueEventListener getHueEventListener() {
        return hueEventListener;
    }

    ManualOverrideTracker getManualOverrideTracker() {
        return manualOverrideTracker;
    }
}
