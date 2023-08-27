package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import lombok.SneakyThrows;
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

@Command(name = "HueScheduler", version = "0.9.0", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final Map<String, List<ScheduledState>> lightStates;
    private final ConcurrentHashMap<String, List<Runnable>> onStateWaitingList;
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
    @Option(names = "--disable-user-modification-tracking", defaultValue = "false",
            description = "Globally disable tracking of user modifications which would pause their schedules until they are turned off and on again." +
                    " Default: ${DEFAULT-VALUE}")
    boolean disableUserModificationTracking;
    @Option(names = "--default-interpolation-transition-time", paramLabel = "<tr>", defaultValue = "4",
            description = "The default transition time in defined as a multiple of 100ms used for the interpolated call" +
                    " when turning a light on during a tr-before transition. Default: ${DEFAULT-VALUE} (=400 ms)." +
                    " You can also use, e.g., 2s for convenience.")
    String defaultInterpolationTransitionTimeString;
    /**
     * The converted transition time as a multiple of 100ms.
     */
    Integer defaultInterpolationTransitionTime = 4;
    @Option(names = "--power-on-reschedule-delay", paramLabel = "<delay>", defaultValue = "150",
            description = "The delay in ms after the light on-event was received and the current state should be " +
                    "rescheduled again. Default: ${DEFAULT-VALUE} ms.")
    int powerOnRescheduleDelayInMs;
    @Option(names = "--bridge-failure-retry-delay", paramLabel = "<delay>", defaultValue = "10",
            description = "The delay in seconds for retrying an API call, if the bridge could not be reached due to " +
                    "network failure, or if it returned an API error code. Default: ${DEFAULT-VALUE} seconds.")
    int bridgeFailureRetryDelayInSeconds;
    @Option(names = "--multi-color-adjustment-delay", paramLabel = "<delay>", defaultValue = "4",
            description = "The adjustment delay in seconds for each light in a group when using the multi_color effect." +
                    " Adjust to change the hue values of 'neighboring' lights. Default: ${DEFAULT-VALUE} seconds.")
    int multiColorAdjustmentDelay;
    @Option(names = "--event-stream-read-timeout", paramLabel = "<timout>", defaultValue = "120",
            description = "The read timeout of the API v2 SSE event stream in minutes. " +
                    "The connection is automatically restored after a timeout. Default: ${DEFAULT-VALUE} minutes.")
    int eventStreamReadTimeoutInMinutes;
    @Option(
            names = "--api-cache-invalidation-interval", paramLabel = "<interval>", defaultValue = "15",
            description = "The interval in which the api cache for groups and lights should be invalidated. " +
                    "Default: ${DEFAULT-VALUE} minutes."
    )
    int apiCacheInvalidationIntervalInMinutes;
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
        hueEventListener = new HueEventListenerImpl(manualOverrideTracker, onStateWaitingList::remove, this::getAssignedGroups);
    }
    
    public HueScheduler(HueApi hueApi, StateScheduler stateScheduler,
                        StartTimeProvider startTimeProvider, Supplier<ZonedDateTime> currentTime,
                        double requestsPerSecond, boolean controlGroupLightsIndividually,
                        boolean disableUserModificationTracking, String defaultInterpolationTransitionTimeString,
                        int powerOnRescheduleDelayInMs, int bridgeFailureRetryDelayInSeconds, int multiColorAdjustmentDelay) {
        this();
        this.hueApi = hueApi;
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        this.requestsPerSecond = requestsPerSecond;
        this.controlGroupLightsIndividually = controlGroupLightsIndividually;
        this.disableUserModificationTracking = disableUserModificationTracking;
        this.defaultInterpolationTransitionTimeString = defaultInterpolationTransitionTimeString;
        this.powerOnRescheduleDelayInMs = powerOnRescheduleDelayInMs;
        this.bridgeFailureRetryDelayInSeconds = bridgeFailureRetryDelayInSeconds;
        this.multiColorAdjustmentDelay = multiColorAdjustmentDelay;
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        apiCacheInvalidationIntervalInMinutes = 15;
    }
    
    private Integer parseInterpolationTransitionTime(String interpolationTransitionTimeString) {
		if (interpolationTransitionTimeString == null) {
			return null;
		}
		return InputConfigurationParser.parseTransitionTime(
				"interpolation-transition-time", interpolationTransitionTimeString);
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
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        new LightStateEventTrackerImpl(ip, username, httpsClient, new HueRawEventHandler(hueEventListener), eventStreamReadTimeoutInMinutes).start();
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
            LOG.warn("Bridge not reachable: '{}'. Retrying in 5s.", e.getCause().getLocalizedMessage());
            return false;
        } catch (BridgeAuthenticationFailure e) {
            System.err.println("Bridge connection rejected: 'Unauthorized user'. Please make sure you use the correct" +
                    " username from the setup process, or try to generate a new one.");
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
                         System.err.println("Failed to parse configuration line '" + input + "':\n" +
                                 e.getClass().getSimpleName() + ": " + e.getLocalizedMessage());
                         System.exit(2);
                     }
                 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void addState(String input) {
        new InputConfigurationParser(startTimeProvider, hueApi).parse(input).forEach(state ->
                lightStates.computeIfAbsent(state.getIdV1(), key -> new ArrayList<>()).add(state));
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        scheduleSunDataInfoLog();
        lightStates.forEach((id, states) -> scheduleInitialStartup(states, now));
        scheduleApiCacheClear();
    }

    /**
     * Schedule the given states. To correctly schedule cross-over states, i.e., states that already started yesterday,
     * we initially calculate all end times using the day before, and reschedule them if needed afterward.
     */
    private void scheduleInitialStartup(List<ScheduledState> states, ZonedDateTime now) {
        calculateAndSetEndTimes(states, now.minusDays(1));
        states.stream()
                .sorted(Comparator.comparing(state -> state.getStart(now.minusDays(1))))
                .forEach(this::initialSchedule);
    }

    private void calculateAndSetEndTimes(List<ScheduledState> states, ZonedDateTime now) {
        HashSet<ScheduledState> alreadyProcessedStates = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            ZonedDateTime day = now.plusDays(i);
            List<ScheduledState> statesStartingOnDay = getStatesStartingOnDay(states, day, DayOfWeek.from(day));
            statesStartingOnDay.stream()
                               .filter(state -> !alreadyProcessedStates.contains(state))
                    .forEach(state -> calculateAndSetEndTime(state, states, state.getDefinedStart(now)));
            alreadyProcessedStates.addAll(statesStartingOnDay);
        }
    }

    private List<ScheduledState> getStatesStartingOnDay(List<ScheduledState> states, ZonedDateTime dateTime,
                                                        DayOfWeek... days) {
        return states.stream()
                     .filter(state -> state.isScheduledOn(days))
                .sorted(Comparator.comparing(scheduledState -> scheduledState.getStart(dateTime)))
                .collect(Collectors.toList());
    }

    private List<ScheduledState> getStatesStartingOnDay(ScheduledState state, ZonedDateTime dateTime) {
        return getStatesStartingOnDay(getLightStatesForId(state), dateTime, DayOfWeek.from(dateTime));
    }

    private void calculateAndSetEndTime(ScheduledState state, List<ScheduledState> states, ZonedDateTime definedStart) {
        new EndTimeAdjuster(state, definedStart,
                (day, dayOfWeeks) -> getStatesStartingOnDay(states, day, dayOfWeeks)).calculateAndSetEndTime();
    }

    private void initialSchedule(ScheduledState state) {
        ZonedDateTime now = currentTime.get();
        ZonedDateTime yesterday = now.minusDays(1);
        state.updateLastStart(yesterday);
        if (state.endsBefore(now)) {
            reschedule(state); // no cross-over state, reschedule normally today
        } else if (doesNotStartOn(state, yesterday)) {
            schedule(state, state.getDelayUntilStart(now)); // state was already scheduled at a future time, reuse calculated start time
        } else {
            schedule(state, 0); // schedule cross-over states, i.e., states already starting yesterday immediately
        }
    }

    private boolean doesNotStartOn(ScheduledState state, ZonedDateTime dateTime) {
        return !state.isScheduledOn(DayOfWeek.from(dateTime));
    }

    private void schedule(ScheduledState state, long delayInMs) {
        if (state.isNullState()) return;
        LOG.debug("Schedule {} in {}", state, Duration.ofMillis(delayInMs).withNanos(0));
        stateScheduler.schedule(() -> {
            if (state.endsBefore(currentTime.get())) {
                LOG.debug("{} already ended", state);
                reschedule(state);
                return;
            }
            LOG.info("Set {}", state);
            if (lightHasBeenManuallyOverriddenBefore(state)) {
                if (state.isOff()) {
                    LOG.info("{} state has been manually overridden before, skip off-state for this day.", state.getFormattedName());
                    reschedule(state);
                } else {
                    LOG.info("{} state has been manually overridden before, skip update and retry when back online", state.getFormattedName());
                    retryWhenBackOn(state);
                }
                return;
            }
            try {
                if (stateIsNotEnforced(state) && stateHasBeenManuallyOverriddenSinceLastSeen(state)) {
                    LOG.info("{} state has been manually overridden, pause update until light is turned off and on again", state.getFormattedName());
                    manualOverrideTracker.onManuallyOverridden(state.getIdV1());
                    retryWhenBackOn(state);
                    return;
                }
                putAdditionalInterpolatedStateIfNeeded(state);
                putState(state);
            } catch (BridgeConnectionFailure e) {
                LOG.warn("Bridge not reachable, retrying in {}s.", bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            } catch (HueApiFailure e) {
                LOG.error("Hue api call failed: '{}'. Retrying in {}s.", e.getLocalizedMessage(), bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            }
            if (state.isOff()) {
                LOG.info("Turned off {}, or was already off", state.getFormattedName());
            } else {
                retryWhenBackOn(createPowerOnCopy(state));
            }
            if (shouldAdjustMultiColorLoopOffset(state)) {
                scheduleMultiColorLoopOffsetAdjustments(state);
            }
            state.setLastSeen(currentTime.get());
            manualOverrideTracker.onAutomaticallyAssigned(state.getIdV1());
            reschedule(state);
        }, currentTime.get().plus(delayInMs, ChronoUnit.MILLIS), state.getEnd());
    }

    private boolean lightHasBeenManuallyOverriddenBefore(ScheduledState state) {
        return !disableUserModificationTracking && manualOverrideTracker.isManuallyOverridden(state.getIdV1());
    }

    private boolean stateIsNotEnforced(ScheduledState state) {
        return !disableUserModificationTracking && !state.isForced() && !manualOverrideTracker.shouldEnforceSchedule(state.getIdV1());
    }

    private boolean stateHasBeenManuallyOverriddenSinceLastSeen(ScheduledState scheduledState) {
        ScheduledState lastSeenState = getLastSeenState(scheduledState);
        if (lastSeenState == null) {
            return false;
        }
        if (scheduledState.isGroupState()) {
            return hueApi.getGroupStates(scheduledState.getUpdateId()).stream().anyMatch(lastSeenState::lightStateDiffers);
        } else {
            return lastSeenState.lightStateDiffers(hueApi.getLightState(scheduledState.getUpdateId()));
        }
    }
    
    private ScheduledState getLastSeenState(ScheduledState currentState) {
        return getLightStatesForId(currentState).stream()
                                                .filter(state -> state.getLastSeen() != null)
                                                .max(Comparator.comparing(ScheduledState::getLastSeen))
                                                .orElse(null);
    }
    
    private void putAdditionalInterpolatedStateIfNeeded(ScheduledState state) {
        if (state.getTransitionTimeBeforeString() == null) {
            return;
        }
        ScheduledState previousState = getPreviousStateIgnoringNullStates(state);
        if (previousState == null) {
            return;
        }
        ScheduledState lastSeenState = getLastSeenState(state);
        if ((lastSeenState == previousState || state.isSameState(lastSeenState))
                && !manualOverrideTracker.shouldEnforceSchedule(state.getIdV1())) {
            return; // skip interpolations if the previous or current state was the last state set without any power cycles
        }
        PutCall interpolatedPutCall = state.getInterpolatedPutCall(currentTime.get(), previousState, true);
        if (interpolatedPutCall == null) {
            return;
        }
        LOG.trace("Perform interpolation from previous state: {}", previousState);
        Integer interpolationTransitionTime = getInterpolationTransitionTime(previousState);
        interpolatedPutCall.setTransitionTime(interpolationTransitionTime);
        putState(previousState, interpolatedPutCall);
        sleepIfNeeded(interpolationTransitionTime);
    }

    private Integer getInterpolationTransitionTime(ScheduledState previousState) {
        Integer definedTransitionTime = previousState.getDefinedTransitionTime();
        if (definedTransitionTime == null) {
            return defaultInterpolationTransitionTime;
        }
        return definedTransitionTime;
    }

    @SneakyThrows(InterruptedException.class)
    private void sleepIfNeeded(Integer sleepTime) {
        if (sleepTime == null) {
            return;
        }
        Thread.sleep(sleepTime * 100L);
    }

    private ScheduledState getPreviousStateIgnoringNullStates(ScheduledState currentState) {
        ScheduledState previousState = findPreviousState(currentState);
        if (previousState == null || previousState.isNullState()) {
            return null;
        }
        return previousState;
    }
    
    private ScheduledState findPreviousState(ScheduledState currentState) {
        List<ScheduledState> lightStatesForId = getLightStatesForId(currentState);
        List<ScheduledState> calculatedStateOrderAscending = lightStatesForId.stream()
                                                           .sorted(Comparator.comparing(state -> state.getStart(currentTime.get())))
                                                           .collect(Collectors.toList());
        int position = calculatedStateOrderAscending.indexOf(currentState);
        if (position > 0) {
            return calculatedStateOrderAscending.get(position - 1);
        }
        ZonedDateTime yesterday = currentTime.get().minusDays(1);
        return lightStatesForId.stream()
                .filter(state -> state.isScheduledOn(yesterday))
                .filter(currentState::isNotSameState)
                .max(Comparator.comparing(state -> state.getStart(yesterday)))
                .orElse(null);
    }

    private void reschedule(ScheduledState state) {
        if (state.isTemporary()) return;
        ZonedDateTime now = currentTime.get();
        ZonedDateTime nextDefinedStart = getNextDefinedStart(state, now);
        state.updateLastStart(nextDefinedStart);
        calculateAndSetEndTime(state, getLightStatesForId(state), nextDefinedStart);
        schedule(state, state.getDelayUntilStart(now));
    }

    private ZonedDateTime getNextDefinedStart(ScheduledState state, ZonedDateTime now) {
        ZonedDateTime date;
        if (shouldScheduleNextDay(state, now)) {
            date = now.plusDays(1);
        } else {
            date = now;
        }
        return state.getNextDefinedStart(date);
    }

    private boolean shouldScheduleNextDay(ScheduledState state, ZonedDateTime now) {
        List<ScheduledState> todaysStates = getStatesStartingOnDay(state, now);
        if (isLastState(state, todaysStates)) {
            return firstStateOfNextDayAlreadyStarted(state, now);
        }
        return nextStateAlreadyStarted(state, todaysStates, now);
    }

    private boolean isLastState(ScheduledState state, List<ScheduledState> todaysStates) {
        return todaysStates.indexOf(state) == todaysStates.size() - 1;
    }

    private boolean firstStateOfNextDayAlreadyStarted(ScheduledState state, ZonedDateTime now) {
        List<ScheduledState> nextDayStates = getStatesStartingOnDay(state, now.plusDays(1));
        if (nextDayStates.isEmpty()) {
            return false;
        }
        return nextDayStates.get(0).stateAlreadyStarted(now, now.plusDays(1));
    }

    private static boolean nextStateAlreadyStarted(ScheduledState state, List<ScheduledState> todaysStates, ZonedDateTime now) {
        return todaysStates.get(todaysStates.indexOf(state) + 1).stateAlreadyStarted(now, now);
    }

    private List<ScheduledState> getLightStatesForId(ScheduledState state) {
        return lightStates.get(state.getIdV1());
    }

    private long getMs(long seconds) {
        return seconds * 1000L;
    }
    
    private void putState(ScheduledState state) {
        ZonedDateTime now = currentTime.get();
        if (state.shouldSplitLongBeforeTransition(now)) {
            PutCall interpolatedSplitPutCall = getInterpolatedSplitPutCall(state);
            if (interpolatedSplitPutCall == null) {
                logMissingPreviousStateWarning(state);
                return;
            }
            performPutApiCall(state, interpolatedSplitPutCall);
            if (!state.isRetryAfterPowerOnState()) { // schedule follow-up split
                schedule(ScheduledState.createTemporaryCopy(state), state.getNextInterpolationSplitTransitionTime(now));
            }
        } else {
            putState(state, state.getPutCall(now));
        }
    }
    
    private PutCall getInterpolatedSplitPutCall(ScheduledState state) {
        ScheduledState previousState = getPreviousStateIgnoringNullStates(state);
        if (previousState == null) {
            return null;
        }
		return state.getNextInterpolatedSplitPutCall(currentTime.get(), previousState);
    }
    
    private static void logMissingPreviousStateWarning(ScheduledState state) {
        LOG.warn("Warning: Can't set {} with extended transition time. No previous state found or missing source " +
                "properties for required interpolation!", state.getFormattedName());
    }
    
    private void putState(ScheduledState state, PutCall putCall) {
        if (state.isGroupState() && controlGroupLightsIndividually) {
            for (Integer id : getGroupLights(state)) {
                try {
					performPutApiCall(state, putCall.toBuilder().id(id).groupState(false).build());
				} catch (HueApiFailure e) {
                    LOG.trace("Unsupported api call for light id {}: {}", id, e.getLocalizedMessage());
                }
            }
        } else {
            performPutApiCall(state, putCall);
        }
    }

    private List<Integer> getGroupLights(ScheduledState state) {
        return hueApi.getGroupLights(state.getUpdateId());
    }

    private void performPutApiCall(ScheduledState state, PutCall putCall) {
        LOG.trace("{}", putCall);
        state.setLastPutCall(putCall);
        hueApi.putState(putCall);
    }

    private void retry(ScheduledState state, long delayInMs) {
        schedule(state, delayInMs);
    }
    
    private ScheduledState createPowerOnCopy(ScheduledState state) {
        ScheduledState powerOnCopy = ScheduledState.createTemporaryCopy(state, state.calculateNextPowerOnEnd(currentTime.get()));
        powerOnCopy.setRetryAfterPowerOnState(true);
        return powerOnCopy;
    }
    
    private void retryWhenBackOn(ScheduledState state) {
        onStateWaitingList.computeIfAbsent(state.getIdV1(), id -> new ArrayList<>()).add(() -> schedule(state, powerOnRescheduleDelayInMs));
    }

    private boolean shouldAdjustMultiColorLoopOffset(ScheduledState state) {
        return state.isMultiColorLoop() && getGroupLights(state).size() > 1;
    }

    private void scheduleMultiColorLoopOffsetAdjustments(ScheduledState state) {
        scheduleMultiColorLoopOffsetAdjustments(getGroupLights(state), 1);
    }

    private void scheduleMultiColorLoopOffsetAdjustments(List<Integer> groupLights, int i) {
        stateScheduler.schedule(() -> offsetMultiColorLoopForLight(groupLights, i),
                currentTime.get().plusSeconds(multiColorAdjustmentDelay), null);
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
        stateScheduler.schedule(() -> putOnState(light, true, "colorloop"),
                currentTime.get().plus(300, ChronoUnit.MILLIS), null);
    }

    private void putOnState(int light, boolean on, String effect) {
        hueApi.putState(PutCall.builder().id(light).on(on).effect(effect).build());
    }

    private void scheduleSunDataInfoLog() {
        logSunDataInfo();
        ZonedDateTime now = currentTime.get();
        ZonedDateTime midnight = ZonedDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT, now.getZone());
        long delay = Duration.between(now, midnight).toMinutes();
        stateScheduler.scheduleAtFixedRate(this::logSunDataInfo, delay + 1, 60 * 24L, TimeUnit.MINUTES);
    }

    private void logSunDataInfo() {
        LOG.info("Current sun times:\n{}", startTimeProvider.toDebugString(currentTime.get()));
    }
    
    private void scheduleApiCacheClear() {
        stateScheduler.scheduleAtFixedRate(
                hueApi::clearCaches, apiCacheInvalidationIntervalInMinutes, apiCacheInvalidationIntervalInMinutes, TimeUnit.MINUTES);
    }
    
    private List<String> getAssignedGroups(String idv1LightId) {
        int lightId = Integer.parseInt(idv1LightId.substring("/lights/".length()));
        return hueApi.getAssignedGroups(lightId)
                .stream()
                .map(id -> "/groups/" + id)
                .collect(Collectors.toList());
    }

    HueEventListener getHueEventListener() {
        return hueEventListener;
    }

    ManualOverrideTracker getManualOverrideTracker() {
        return manualOverrideTracker;
    }
}
