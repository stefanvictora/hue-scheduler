package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Command(name = "HueScheduler", version = "1.0-SNAPSHOT", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final Map<Integer, List<ScheduledState>> lightStates;
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
    @Option(names = "--retry-delay", paramLabel = "<delay>", defaultValue = "5",
            description = "The maximum number of seconds to wait before trying again to control a light that was unreachable." +
                    " Default: ${DEFAULT-VALUE} seconds.")
    int maxRetryDelayInSeconds;
    @Option(names = "--max-requests-per-second", paramLabel = "<requests>", defaultValue = "10.0",
            description = "The maximum number of PUT API requests to perform per second. Default and recommended: " +
                    "${DEFAULT-VALUE} requests per second.")
    double requestsPerSecond;
    @Option(names = "--confirm-all", defaultValue = "true",
            description = "If all states should be confirmed by default. Default: ${DEFAULT-VALUE}.")
    boolean confirmAll;
    @Option(names = "--confirm-count", paramLabel = "<count>", defaultValue = "20",
            description = "The number of confirmations to send. Default: ${DEFAULT-VALUE} confirmations.")
    int confirmationCount;
    @Option(names = "--confirm-delay", paramLabel = "<delay>", defaultValue = "6",
            description = "The delay in seconds between each confirmation. Default: ${DEFAULT-VALUE} seconds.")
    int confirmDelayInSeconds;
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
    private StartTimeProvider startTimeProvider;
    private Supplier<ZonedDateTime> currentTime;
    private Supplier<Integer> retryDelay;

    public HueScheduler() {
        lightStates = new HashMap<>();
    }

    public HueScheduler(HueApi hueApi, StateScheduler stateScheduler, StartTimeProvider startTimeProvider,
                        Supplier<ZonedDateTime> currentTime, double requestsPerSecond, Supplier<Integer> retryDelayInMs,
                        boolean confirmAll, int confirmationCount, int confirmDelayInSeconds, int bridgeFailureRetryDelayInSeconds,
                        int multiColorAdjustmentDelay) {
        this();
        this.hueApi = hueApi;
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        this.requestsPerSecond = requestsPerSecond;
        this.retryDelay = retryDelayInMs;
        this.confirmAll = confirmAll;
        this.confirmationCount = confirmationCount;
        this.confirmDelayInSeconds = confirmDelayInSeconds;
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
        hueApi = new HueApiImpl(new HttpResourceProviderImpl(), ip, username, RateLimiter.create(requestsPerSecond));
        startTimeProvider = createStartTimeProvider(latitude, longitude, elevation);
        stateScheduler = createStateScheduler();
        currentTime = ZonedDateTime::now;
        retryDelay = this::getRandomRetryDelayMs;
        assertInputIsReadable();
        assertConnectionAndStart();
    }

    private StartTimeProviderImpl createStartTimeProvider(double latitude, double longitude, double elevation) {
        return new StartTimeProviderImpl(new SunTimesProviderImpl(latitude, longitude, elevation));
    }

    private StateSchedulerImpl createStateScheduler() {
        return new StateSchedulerImpl(Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()), ZonedDateTime::now);
    }

    private int getRandomRetryDelayMs() {
        return ThreadLocalRandom.current().nextInt(1000, maxRetryDelayInSeconds * 1000 + 1);
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
        lightStates.forEach((id, states) -> {
            calculateAndSetEndTimes(states, now);
            scheduleInitialStartup(states, now);
        });
        scheduleSunDataInfoLog();
    }

    private void calculateAndSetEndTimes(List<ScheduledState> states, ZonedDateTime now) {
        HashSet<ScheduledState> alreadyUpdatedStates = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            ZonedDateTime day = now.plusDays(i);
            List<ScheduledState> unprocessedStates = getStatesStartingOnDay(states, alreadyUpdatedStates, day, DayOfWeek.from(day));
            if (unprocessedStates.isEmpty()) continue;
            unprocessedStates.forEach(state -> calculateAndSetEndTime(state, states, state.getStart(now)));
            alreadyUpdatedStates.addAll(unprocessedStates);
        }
    }

    private void calculateAndSetEndTime(ScheduledState state, List<ScheduledState> states, ZonedDateTime start) {
        new EndTimeAdjuster(state, start, (day, dayOfWeeks) -> getStatesStartingOnDay(states, day, dayOfWeeks)).calculateAndSetEndTime();
    }

    private List<ScheduledState> getStatesStartingOnDay(List<ScheduledState> states, ZonedDateTime now, DayOfWeek... days) {
        return getStatesStartingOnDay(states, Collections.emptySet(), now, days);
    }

    private List<ScheduledState> getStatesStartingOnDay(List<ScheduledState> states, Set<ScheduledState> alreadyProcessedStates,
                                                        ZonedDateTime now, DayOfWeek... days) {
        return states.stream()
                     .filter(state -> state.isScheduledOn(days))
                     .filter(state -> !alreadyProcessedStates.contains(state))
                     .sorted(Comparator.comparing(scheduledState -> scheduledState.getStart(now)))
                     .collect(Collectors.toList());
    }

    private void scheduleInitialStartup(List<ScheduledState> states, ZonedDateTime now) {
        scheduleStatesStartingToday(states, now);
        scheduleStatesNotStartingToday(states, now);
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
                scheduleNextDay(state);
                return;
            }
            boolean success;
            LightState lightState = null;
            try {
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
                scheduleNextDay(state);
                return;
            }
            if (!success || lightState.isUnreachableOrOff()) {
                Integer delay = retryDelay.get();
                LOG.trace("{} not reachable or off, retrying in {}", state.getFormattedName(), Duration.ofMillis(delay));
                retry(state, delay);
                return;
            }
            if (state.getConfirmCounter() == 0) {
                LOG.info("Set {}", state);
                if (shouldAdjustMultiColorLoopOffset(state)) {
                    scheduleMultiColorLoopOffsetAdjustments(state.getGroupLights(), 1);
                }
            }
            if (shouldAndIsNotFullyConfirmed(state)) {
                state.addConfirmation();
                if (state.getConfirmCounter() % 5 == 0) {
                    LOG.debug("Confirmed {} ({})", state.getFormattedName(), state.getConfirmDebugString(confirmationCount));
                }
                schedule(state, getMs(confirmDelayInSeconds));
            } else {
                if (isFullyConfirmed(state)) {
                    LOG.debug("Fully confirmed {}", state.getFormattedName());
                }
                scheduleNextDay(state);
            }
        }, currentTime.get().plus(delayInMs, ChronoUnit.MILLIS), state.getEnd());
    }

    private boolean shouldLogScheduleDebugMessage(long delayInMs) {
        return delayInMs == 0 || delayInMs > Math.max(5000, getMs(confirmDelayInSeconds)) && delayInMs != getMs(bridgeFailureRetryDelayInSeconds);
    }

    private void scheduleNextDay(ScheduledState state) {
        scheduleNextDay(state, currentTime.get());
    }

    private void scheduleNextDay(ScheduledState state, ZonedDateTime now) {
        if (state.isTemporary()) return;
        state.resetConfirmations();
        recalculateNextEnd(state, now);
        long delay = state.secondsUntilNextDayFromStart(now);
        state.updateLastStart(now);
        schedule(state, getMs(delay));
    }

    private void recalculateNextEnd(ScheduledState state, ZonedDateTime now) {
        calculateAndSetEndTime(state, getLightStatesForId(state), state.getNextStart(now));
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

    private boolean shouldAndIsNotFullyConfirmed(ScheduledState state) {
        return state.shouldConfirm(confirmAll) && !isFullyConfirmed(state);
    }

    private boolean isFullyConfirmed(ScheduledState state) {
        return state.isFullyConfirmed(confirmationCount);
    }

    private long getMs(long seconds) {
        return seconds * 1000L;
    }

    private boolean putState(ScheduledState state) {
        return hueApi.putState(state.getUpdateId(), state.getBrightness(), state.getCt(), state.getX(), state.getY(),
                state.getHue(), state.getSat(), state.getEffect(), state.getOn(), state.getTransitionTime(currentTime.get()),
                state.isGroupState());
    }

    private void retry(ScheduledState state, long delayInMs) {
        state.resetConfirmations();
        schedule(state, delayInMs);
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
}
