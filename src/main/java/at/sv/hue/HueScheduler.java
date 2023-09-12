package at.sv.hue;

import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.HttpsResourceProviderImpl;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.HueApiFailure;
import at.sv.hue.api.HueApiHttpsClientFactory;
import at.sv.hue.api.HueApiImpl;
import at.sv.hue.api.HueEventListener;
import at.sv.hue.api.HueEventListenerImpl;
import at.sv.hue.api.HueRawEventHandler;
import at.sv.hue.api.LightState;
import at.sv.hue.api.LightStateEventTrackerImpl;
import at.sv.hue.api.ManualOverrideTracker;
import at.sv.hue.api.ManualOverrideTrackerImpl;
import at.sv.hue.api.PutCall;
import at.sv.hue.api.RateLimiter;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    @Option(names = "--interpolate-all", defaultValue = "false",
            description = "Globally sets 'interpolate:true' for all states, unless explicitly set otherwise." +
                    " Default: ${DEFAULT-VALUE}")
    private boolean interpolateAll;
    @Option(names = "--default-interpolation-transition-time", paramLabel = "<tr>", defaultValue = "4",
            description = "The default transition time defined as a multiple of 100ms used for the interpolated call" +
                    " when turning a light on during a tr-before transition. Default: ${DEFAULT-VALUE} (=400 ms)." +
                    " You can also use, e.g., 2s for convenience.")
    String defaultInterpolationTransitionTimeString; // todo: should we really use this?
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
    @Option(
            names = "--min-tr-before-gap", paramLabel = "<gap>", defaultValue = "2",
            description = "The minimum gap between multiple back-to-back tr-before states in minutes. This is needed as otherwise " +
                    "the hue bridge may not yet recognise the end value of the transition and incorrectly marks " +
                    "the light as manually overridden. This gap is automatically added between back-to-back " +
                    "tr-before states. Default: ${DEFAULT-VALUE} minutes."
    )
    int minTrBeforeGapInMinutes;
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
                        int powerOnRescheduleDelayInMs, int bridgeFailureRetryDelayInSeconds, int multiColorAdjustmentDelay,
                        int minTrBeforeGapInMinutes1, boolean interpolateAll) {
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
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes1;
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        this.interpolateAll = interpolateAll;
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
        MDC.put("context", "init");
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
        return new StateSchedulerImpl(Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2),
                ZonedDateTime::now);
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
        new InputConfigurationParser(startTimeProvider, hueApi, minTrBeforeGapInMinutes, interpolateAll)
                .parse(input)
                .forEach(state -> lightStates.computeIfAbsent(state.getIdV1(), key -> new ArrayList<>()).add(state));
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        scheduleSunDataInfoLog();
        lightStates.forEach((id, states) -> scheduleInitialStartup(states, now));
        scheduleApiCacheClear();
    }

    /**
     * Schedule the given states. To correctly schedule cross-over states, i.e., states that already started yesterday,
     * we initially calculate all end times using yesterday, and reschedule them if needed afterward.
     */
    private void scheduleInitialStartup(List<ScheduledState> states, ZonedDateTime now) {
        MDC.put("context", "init");
        ZonedDateTime yesterday = now.minusDays(1);
        states.forEach(state -> state.setPreviousStateDefinedStartLookup(this::getPreviousStateDefinedStart));
        calculateAndSetEndTimes(states, yesterday);
        states.stream()
              .sorted(Comparator.comparing(state -> state.getDefinedStart(yesterday)))
              .forEach(state -> initialSchedule(state, now));
    }

    private ZonedDateTime getPreviousStateDefinedStart(ScheduledState currentState, ZonedDateTime dateTime) {
        return Optional.ofNullable(getPreviousState(currentState, dateTime))
                       .map(ScheduledStateSnapshot::getDefinedStart)
                       .orElse(null);
    }

    private ScheduledStateSnapshot getPreviousState(ScheduledState currentState, ZonedDateTime dateTime) {
        ZonedDateTime definedStart = currentState.getDefinedStart(dateTime);
        List<ScheduledStateSnapshot> previousStates = getPreviousStatesListIgnoringSame(currentState, definedStart);
        return previousStates.stream()
                             .filter(snapshot -> snapshot.getDefinedStart().isBefore(definedStart))
                             .findFirst()
                             .orElse(null);
    }

    private List<ScheduledStateSnapshot> getPreviousStatesListIgnoringSame(ScheduledState currentState, ZonedDateTime definedStart) {
        ZonedDateTime theDayBefore = definedStart.minusDays(1).truncatedTo(ChronoUnit.DAYS).withEarlierOffsetAtOverlap();
        return getLightStatesForId(currentState)
                .stream()
                .flatMap(state -> Stream.of(state.getSnapshot(theDayBefore), state.getSnapshot(definedStart)))
                .sorted(Comparator.comparing(ScheduledStateSnapshot::getDefinedStart, Comparator.reverseOrder())
                                  .thenComparing(snapshot -> snapshot.getScheduledState().hasTransitionBefore()))
                .distinct()
                .filter(snapshot -> currentState.isNotSameState(snapshot.getScheduledState()))
                .collect(Collectors.toList());
    }

    private void calculateAndSetEndTimes(List<ScheduledState> states, ZonedDateTime now) {
        states.forEach(state -> calculateAndSetEndTime(state, states, state.getDefinedStart(now)));
    }

    private void calculateAndSetEndTime(ScheduledState state, List<ScheduledState> states, ZonedDateTime definedStart) {
        new EndTimeAdjuster(state, getNextState(states, definedStart), definedStart).calculateAndSetEndTime();
    }

    private ScheduledStateSnapshot getNextState(List<ScheduledState> states, ZonedDateTime definedStart) {
        List<ScheduledStateSnapshot> nextStates = getNextStatesList(states, definedStart);
        return nextStates.stream()
                         .filter(snapshot -> snapshot.getDefinedStart().isAfter(definedStart))
                         .findFirst()
                         .orElse(null);
    }

    private List<ScheduledStateSnapshot> getNextStatesList(List<ScheduledState> states, ZonedDateTime definedStart) {
        ZonedDateTime theDayAfter = definedStart.plusDays(1).truncatedTo(ChronoUnit.DAYS).withEarlierOffsetAtOverlap();
        return states.stream()
                     .flatMap(state -> Stream.of(state.getSnapshot(definedStart), state.getSnapshot(theDayAfter)))
                     .sorted(Comparator.comparing(ScheduledStateSnapshot::getStart))
                     .distinct()
                     .collect(Collectors.toList());
    }

    private void initialSchedule(ScheduledState state, ZonedDateTime now) {
        ZonedDateTime yesterday = now.minusDays(1);
        if (state.endsBefore(now)) {
            state.updateLastStart(yesterday);
            reschedule(state); // no cross-over state, reschedule normally today
        } else if (doesNotStartOn(state, yesterday)) {
            state.updateLastStart(now);
            schedule(state, state.getDelayUntilStart(now)); // state was already scheduled at a future time, reuse calculated start time
        } else {
            state.updateLastStart(yesterday);
            schedule(state, 0); // schedule cross-over states, i.e., states already starting yesterday immediately
        }
    }

    private boolean doesNotStartOn(ScheduledState state, ZonedDateTime dateTime) {
        return !state.isScheduledOn(DayOfWeek.from(dateTime));
    }

    private void schedule(ScheduledState state, long delayInMs) {
        if (state.isNullState()) return;
        LOG.debug("Schedule: {} in {}", state, Duration.ofMillis(delayInMs).withNanos(0));
        stateScheduler.schedule(() -> {
            MDC.put("context", state.getContextName());
            if (state.endsBefore(currentTime.get())) {
                LOG.debug("Already ended: {}", state);
                reschedule(state);
                return;
            }
            LOG.info("Set: {}", state);
            if (lightHasBeenManuallyOverriddenBefore(state) || lightHasBeenConsideredOffBeforeAndDoesNotTurnOn(state)) {
                if (shouldRetryOnPowerOn(state)) {
                    LOG.info("Off or manually overridden: Skip update and retry when back online");
                    retryWhenBackOn(state);
                } else {
                    LOG.info("Off or manually overridden: Skip state for this day.");
                    reschedule(state);
                }
                return;
            }
            boolean success;
            try {
                if (stateIsNotEnforced(state) && stateHasBeenManuallyOverriddenSinceLastSeen(state)) {
                    LOG.info("Manually overridden: Pause updates until turned off and on again");
                    manualOverrideTracker.onManuallyOverridden(state.getIdV1());
                    retryWhenBackOn(state);
                    return;
                }
                putAdditionalInterpolatedStateIfNeeded(state);
                success = putState(state);
            } catch (BridgeConnectionFailure e) {
                LOG.warn("Bridge not reachable, retrying in {}s.", bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            } catch (HueApiFailure e) {
                LOG.error("Hue api call failed: '{}'. Retrying in {}s.", e.getLocalizedMessage(), bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            }
            if (success) {
                state.setLastSeen(currentTime.get());
                manualOverrideTracker.onAutomaticallyAssigned(state.getIdV1());
            } else {
                manualOverrideTracker.onLightOff(state.getIdV1());
                LOG.info("Off: Pause updates until light is turned on again");
            }
            if (state.isOff()) {
                LOG.info("Turned off, or was already off");
            }
            if (shouldRetryOnPowerOn(state)) {
                retryWhenBackOn(createPowerOnCopy(state));
            }
            if (shouldAdjustMultiColorLoopOffset(state)) {
                scheduleMultiColorLoopOffsetAdjustments(state);
            }
            reschedule(state);
        }, currentTime.get().plus(delayInMs, ChronoUnit.MILLIS), state.getEnd());
    }

    private boolean lightHasBeenManuallyOverriddenBefore(ScheduledState state) {
        return manualOverrideTracker.isManuallyOverridden(state.getIdV1());
    }

    private boolean lightHasBeenConsideredOffBeforeAndDoesNotTurnOn(ScheduledState state) {
        return manualOverrideTracker.isOff(state.getIdV1()) && !state.isOn();
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
        if (!state.hasTransitionBefore()) {
            return;
        }
        ZonedDateTime dateTime = currentTime.get();
        ScheduledStateSnapshot previousScheduledState = getPreviousState(state, dateTime);
        if (previousScheduledState == null) {
            return;
        }
        ScheduledState previousState = previousScheduledState.getScheduledState();
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

    private void reschedule(ScheduledState state) {
        if (state.isTemporary()) return;
        ZonedDateTime now = currentTime.get();
        ZonedDateTime nextDefinedStart = getNextDefinedStart(state, now);
        state.updateLastStart(nextDefinedStart);
        calculateAndSetEndTime(state, getLightStatesForId(state), nextDefinedStart);
        schedule(state, state.getDelayUntilStart(now));
    }

    private ZonedDateTime getNextDefinedStart(ScheduledState state, ZonedDateTime now) {
        ZonedDateTime nextDefinedStart = state.getNextDefinedStart(now);
        if (shouldScheduleNextDay(state, nextDefinedStart, now)) {
            return state.getNextDefinedStart(now.plusDays(1));
        } else {
            return nextDefinedStart;
        }
    }

    private boolean shouldScheduleNextDay(ScheduledState state, ZonedDateTime nextDefinedStart, ZonedDateTime now) {
        ScheduledStateSnapshot nextState = getNextState(getLightStatesForId(state), nextDefinedStart);
        return nextState.getStart().isBefore(now) || nextState.getStart().isEqual(now);
    }

    private List<ScheduledState> getLightStatesForId(ScheduledState state) {
        return lightStates.get(state.getIdV1());
    }

    private long getMs(long seconds) {
        return seconds * 1000L;
    }

    private boolean putState(ScheduledState state) {
        ZonedDateTime now = currentTime.get();
        if (state.isInsideSplitCallWindow(now)) {
            PutCall interpolatedSplitPutCall = getInterpolatedSplitPutCall(state);
            if (interpolatedSplitPutCall == null) {
                logSplitCallSkippedWarning();
                return true;
            }
            boolean success = performPutApiCall(state, interpolatedSplitPutCall);
            if (!state.isRetryAfterPowerOnState()) { // schedule follow-up split
                schedule(ScheduledState.createTemporaryCopy(state), state.getNextInterpolationSplitDelayInMs(now));
            }
            return success;
        } else {
            return putState(state, getPutCallWithAdjustedTr(state, now));
        }
    }

    private PutCall getInterpolatedSplitPutCall(ScheduledState state) {
        ZonedDateTime dateTime = currentTime.get();
        ScheduledStateSnapshot previousState = getPreviousState(state, dateTime);
        return state.getNextInterpolatedSplitPutCall(currentTime.get(), previousState.getScheduledState());
    }

    private static void logSplitCallSkippedWarning() {
        LOG.warn("Warning: Skipped extended transition call. No previous state found, missing source properties or required pause.");
    }

    private boolean putState(ScheduledState state, PutCall putCall) {
        if (state.isGroupState() && controlGroupLightsIndividually) {
            for (Integer id : getGroupLights(state)) {
                try {
                    performPutApiCall(state, putCall.toBuilder().id(id).groupState(false).build());
                } catch (HueApiFailure e) {
                    LOG.trace("Unsupported api call for light id {}: {}", id, e.getLocalizedMessage());
                }
            }
            return true;
        } else {
            return performPutApiCall(state, putCall);
        }
    }

    private List<Integer> getGroupLights(ScheduledState state) {
        return hueApi.getGroupLights(state.getUpdateId());
    }

    private boolean performPutApiCall(ScheduledState state, PutCall putCall) {
        LOG.trace("{}", putCall);
        state.setLastPutCall(putCall);
        return hueApi.putState(putCall);
    }

    private PutCall getPutCallWithAdjustedTr(ScheduledState state, ZonedDateTime now) {
        PutCall putCall = state.getPutCall(now);
        if (putCall.getTransitionTime() == null) {
            return putCall;
        }
        ScheduledStateSnapshot nextState = getNextState(getLightStatesForId(state), state.getLastDefinedStart());
        Duration duration = Duration.between(now, nextState.getStart()).abs();
        Duration tr = Duration.ofMillis(putCall.getTransitionTime() * 100);
        long differenceInMinutes = duration.minus(tr).toMinutes();
        if (differenceInMinutes < minTrBeforeGapInMinutes) {
            putCall.setTransitionTime(getAdjustedTransitionTime(duration, nextState));
        }
        return putCall;
    }

    private Integer getAdjustedTransitionTime(Duration duration, ScheduledStateSnapshot nextState) {
        if (shouldEnsureGap(nextState)) {
            duration = duration.minusMinutes(minTrBeforeGapInMinutes);
        }
        if (duration.isZero() || duration.isNegative()) {
            return null;
        }
        return (int) (duration.toMillis() / 100);
    }

    private boolean shouldEnsureGap(ScheduledStateSnapshot nextState) {
        return !disableUserModificationTracking && !nextState.isForced() && !nextState.isNullState();
    }

    private void retry(ScheduledState state, long delayInMs) {
        schedule(state, delayInMs);
    }

    private ScheduledState createPowerOnCopy(ScheduledState state) {
        ScheduledState powerOnCopy = ScheduledState.createTemporaryCopy(state, state.calculateNextPowerOnEnd(currentTime.get()));
        powerOnCopy.setRetryAfterPowerOnState(true);
        return powerOnCopy;
    }

    private static boolean shouldRetryOnPowerOn(ScheduledState state) {
        return !state.isOff() || state.isForced();
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
        MDC.put("context", "info");
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
