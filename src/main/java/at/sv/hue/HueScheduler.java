package at.sv.hue;

import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.HttpResourceProviderImpl;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.LightEventListener;
import at.sv.hue.api.LightEventListenerImpl;
import at.sv.hue.api.LightState;
import at.sv.hue.api.ManualOverrideTracker;
import at.sv.hue.api.ManualOverrideTrackerImpl;
import at.sv.hue.api.PutCall;
import at.sv.hue.api.RateLimiter;
import at.sv.hue.api.hass.HassApiImpl;
import at.sv.hue.api.hass.HassEventHandler;
import at.sv.hue.api.hass.HassEventStreamReader;
import at.sv.hue.api.hue.HueApiImpl;
import at.sv.hue.api.hue.HueEventHandler;
import at.sv.hue.api.hue.HueEventStreamReader;
import at.sv.hue.api.hue.HueHttpsClientFactory;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "HueScheduler", version = "0.10.0", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final Map<String, List<ScheduledState>> lightStates;
    @Parameters(description = "The IP address of your Philips Hue Bridge or hostname of your HASS instance.")
    String host;
    @Parameters(description = "The Philips Hue Bridge username or HASS Bearer token used for authentication.")
    String key;
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
    @Option(
            names = "--min-tr-before-gap", paramLabel = "<gap>", defaultValue = "3",
            description = "The minimum gap between multiple back-to-back tr-before states in minutes. This is needed as otherwise " +
                          "the hue bridge may not yet recognise the end value of the transition and incorrectly marks " +
                          "the light as manually overridden. This gap is automatically added between back-to-back " +
                          "tr-before states. Default: ${DEFAULT-VALUE} minutes."
    )
    int minTrBeforeGapInMinutes;
    private HueApi api;
    private StateScheduler stateScheduler;
    private final ManualOverrideTracker manualOverrideTracker;
    private final LightEventListener lightEventListener;
    private StartTimeProvider startTimeProvider;
    private Supplier<ZonedDateTime> currentTime;

    public HueScheduler() {
        lightStates = new HashMap<>();
        manualOverrideTracker = new ManualOverrideTrackerImpl();
        lightEventListener = new LightEventListenerImpl(manualOverrideTracker, lightId -> api.getAssignedGroups(lightId));
    }

    public HueScheduler(HueApi api, StateScheduler stateScheduler,
                        StartTimeProvider startTimeProvider, Supplier<ZonedDateTime> currentTime,
                        double requestsPerSecond, boolean controlGroupLightsIndividually,
                        boolean disableUserModificationTracking, String defaultInterpolationTransitionTimeString,
                        int powerOnRescheduleDelayInMs, int bridgeFailureRetryDelayInSeconds, int multiColorAdjustmentDelay,
                        int minTrBeforeGapInMinutes, boolean interpolateAll) {
        this();
        this.api = api;
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
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
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

    @Command(name = "hass")
    public void hass(@Option(names = "--port", description = "The port of your HASS instance", defaultValue = "8123") String port) {
        MDC.put("context", "init");
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request().newBuilder()
                                           .header("Authorization", "Bearer " + key)
                                           .build();
                    return chain.proceed(request);
                })
                .build();
        api = new HassApiImpl(new HttpResourceProviderImpl(httpClient), host, port, RateLimiter.create(requestsPerSecond));
        new HassEventStreamReader(host, port, key, httpClient, new HassEventHandler(lightEventListener)).start();
        createAndStart();
    }

    @Override
    public void run() {
        MDC.put("context", "init");
        OkHttpClient httpsClient = createHttpsClient();
        api = new HueApiImpl(new HttpResourceProviderImpl(httpsClient), host, key, RateLimiter.create(requestsPerSecond));
        new HueEventStreamReader(host, key, httpsClient, new HueEventHandler(lightEventListener), eventStreamReadTimeoutInMinutes).start();
        createAndStart();
    }

    private void createAndStart() {
        startTimeProvider = createStartTimeProvider(latitude, longitude, elevation);
        stateScheduler = createStateScheduler();
        currentTime = ZonedDateTime::now;
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        assertInputIsReadable();
        assertConnectionAndStart();
    }

    private OkHttpClient createHttpsClient() {
        try {
            return HueHttpsClientFactory.createHttpsClient(host);
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
        return new StateSchedulerImpl(Executors.newSingleThreadScheduledExecutor(), ZonedDateTime::now);
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
            api.assertConnection();
            LOG.info("Connected to bridge at {}.", host);
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
        new InputConfigurationParser(startTimeProvider, api, minTrBeforeGapInMinutes, interpolateAll)
                .parse(input)
                .forEach(state -> lightStates.computeIfAbsent(state.getId(), key -> new ArrayList<>()).add(state));
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        scheduleSolarDataInfoLog();
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
        states.forEach(state -> state.setPreviousStateLookup(this::getPreviousState));
        calculateAndSetEndTimes(states, yesterday);
        states.stream()
              .sorted(Comparator.comparing(state -> state.getDefinedStart(yesterday)))
              .forEach(state -> initialSchedule(state, now));
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
            if (lightHasBeenManuallyOverriddenBefore(state) || lightIsOffAndDoesNotTurnOn(state)) {
                if (shouldRetryOnPowerOn(state)) {
                    LOG.info("Off or manually overridden: Skip update and retry when back online");
                    retryWhenBackOn(state);
                } else {
                    LOG.info("Off or manually overridden: Skip state for this day.");
                    reschedule(state);
                }
                return;
            }
            try {
                if (stateIsNotEnforced(state) && stateHasBeenManuallyOverriddenSinceLastSeen(state)) {
                    LOG.info("Manually overridden: Pause updates until turned off and on again");
                    manualOverrideTracker.onManuallyOverridden(state.getId());
                    retryWhenBackOn(state);
                    return;
                }
                putAdditionalInterpolatedStateIfNeeded(state);
                putState(state);
            } catch (BridgeConnectionFailure e) {
                LOG.warn("Bridge not reachable, retrying in {}s.", bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            } catch (ApiFailure e) {
                LOG.error("Hue api call failed: '{}'. Retrying in {}s.", e.getLocalizedMessage(), bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            }
            state.setLastSeen(currentTime.get());
            manualOverrideTracker.onAutomaticallyAssigned(state.getId());
            if (state.isOff()) {
                LOG.info("Turned off");
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
        return manualOverrideTracker.isManuallyOverridden(state.getId());
    }

    private boolean lightIsOffAndDoesNotTurnOn(ScheduledState state) {
        return !state.isOn() && (lastSeenAsOff(state) || isCurrentlyOff(state));
    }

    private boolean lastSeenAsOff(ScheduledState state) {
        return manualOverrideTracker.isOff(state.getId());
    }

    private boolean isCurrentlyOff(ScheduledState state) {
        boolean off = isGroupOrLightOff(state);
        if (off) {
            manualOverrideTracker.onLightOff(state.getId());
        }
        return off;
    }

    private boolean isGroupOrLightOff(ScheduledState state) {
        boolean off;
        if (state.isGroupState()) {
            off = api.isGroupOff(state.getId());
        } else {
            off = api.isLightOff(state.getId());
        }
        return off;
    }

    private boolean stateIsNotEnforced(ScheduledState state) {
        return !disableUserModificationTracking && !state.isForced() && !manualOverrideTracker.shouldEnforceSchedule(state.getId());
    }

    private boolean stateHasBeenManuallyOverriddenSinceLastSeen(ScheduledState scheduledState) {
        ScheduledState lastSeenState = getLastSeenState(scheduledState);
        if (lastSeenState == null) {
            return false;
        }
        if (scheduledState.isGroupState()) {
            return api.getGroupStates(scheduledState.getId()).stream().anyMatch(lastSeenState::lightStateDiffers);
        } else {
            return lastSeenState.lightStateDiffers(api.getLightState(scheduledState.getId()));
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
        ScheduledStateSnapshot previousStateSnapshot = getPreviousState(state, dateTime);
        if (previousStateSnapshot == null) {
            return;
        }
        ScheduledState previousState = previousStateSnapshot.getScheduledState();
        ScheduledState lastSeenState = getLastSeenState(state);
        if ((lastSeenState == previousState || state.isSameState(lastSeenState) && state.isSplitState())
            && !manualOverrideTracker.shouldEnforceSchedule(state.getId())) {
            return; // skip interpolations if the previous or current state was the last state set without any power cycles
        }
        PutCall interpolatedPutCall = state.getInterpolatedPutCall(previousStateSnapshot, currentTime.get(), true);
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

    private void sleepIfNeeded(Integer sleepTime) {
        if (sleepTime == null) {
            return;
        }
        try {
            Thread.sleep(sleepTime * 100L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
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
        return lightStates.get(state.getId());
    }

    private long getMs(long seconds) {
        return seconds * 1000L;
    }

    private void putState(ScheduledState state) {
        ZonedDateTime now = currentTime.get();
        if (state.isInsideSplitCallWindow(now)) {
            PutCall interpolatedSplitPutCall = getInterpolatedSplitPutCall(state);
            if (interpolatedSplitPutCall == null) {
                return;
            }
            performPutApiCall(state, interpolatedSplitPutCall);
            if (!state.isRetryAfterPowerOnState()) { // schedule follow-up split
                schedule(ScheduledState.createTemporaryCopy(state), state.getNextInterpolationSplitDelayInMs(now));
            }
        } else {
            putState(state, getPutCallWithAdjustedTr(state, now));
        }
    }

    private PutCall getInterpolatedSplitPutCall(ScheduledState state) {
        ZonedDateTime dateTime = currentTime.get();
        ScheduledStateSnapshot previousState = getPreviousState(state, dateTime);
        return state.getNextInterpolatedSplitPutCall(currentTime.get(), previousState);
    }

    private void putState(ScheduledState state, PutCall putCall) {
        if (state.isGroupState() && controlGroupLightsIndividually) {
            for (String id : getGroupLights(state)) {
                try {
                    performPutApiCall(state, putCall.toBuilder().id(id).groupState(false).build());
                } catch (ApiFailure e) {
                    LOG.trace("Unsupported api call for light id {}: {}", id, e.getLocalizedMessage());
                }
            }
        } else {
            performPutApiCall(state, putCall);
        }
    }

    private List<String> getGroupLights(ScheduledState state) {
        return api.getGroupLights(state.getId());
    }

    private void performPutApiCall(ScheduledState state, PutCall putCall) {
        LOG.trace("{}", putCall);
        state.setLastPutCall(putCall);
        api.putState(putCall);
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
        int requiredGap = state.getRequiredGap();
        if (differenceInMinutes < requiredGap) {
            putCall.setTransitionTime(getAdjustedTransitionTime(duration, requiredGap, nextState));
        }
        return putCall;
    }

    private Integer getAdjustedTransitionTime(Duration duration, int requiredGap, ScheduledStateSnapshot nextState) {
        if (shouldEnsureGap(nextState)) {
            duration = duration.minusMinutes(requiredGap);
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
        lightEventListener.runWhenTurnedOn(state.getId(), () -> schedule(state, powerOnRescheduleDelayInMs));
    }

    private boolean shouldAdjustMultiColorLoopOffset(ScheduledState state) {
        return state.isMultiColorLoop() && getGroupLights(state).size() > 1;
    }

    private void scheduleMultiColorLoopOffsetAdjustments(ScheduledState state) {
        scheduleMultiColorLoopOffsetAdjustments(getGroupLights(state), 1);
    }

    private void scheduleMultiColorLoopOffsetAdjustments(List<String> groupLights, int i) {
        stateScheduler.schedule(() -> offsetMultiColorLoopForLight(groupLights, i),
                currentTime.get().plusSeconds(multiColorAdjustmentDelay), null);
    }

    private void offsetMultiColorLoopForLight(List<String> groupLights, int i) {
        String light = groupLights.get(i);
        LightState lightState = api.getLightState(light);
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

    private void turnOff(String light) {
        putOnState(light, false, null);
    }

    private void scheduleTurnOn(String light) {
        stateScheduler.schedule(() -> putOnState(light, true, "colorloop"),
                currentTime.get().plus(300, ChronoUnit.MILLIS), null);
    }

    private void putOnState(String light, boolean on, String effect) {
        api.putState(PutCall.builder().id(light).on(on).effect(effect).build());
    }

    private void scheduleSolarDataInfoLog() {
        logSolarDataInfo();
        ZonedDateTime now = currentTime.get();
        ZonedDateTime midnight = ZonedDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT, now.getZone());
        long delay = Duration.between(now, midnight).toMinutes();
        stateScheduler.scheduleAtFixedRate(this::logSolarDataInfo, delay + 1, 60 * 24L, TimeUnit.MINUTES);
    }

    private void logSolarDataInfo() {
        MDC.put("context", "info");
        LOG.info("Current solar times:\n{}", startTimeProvider.toDebugString(currentTime.get()));
    }

    private void scheduleApiCacheClear() {
        stateScheduler.scheduleAtFixedRate(
                api::clearCaches, apiCacheInvalidationIntervalInMinutes, apiCacheInvalidationIntervalInMinutes, TimeUnit.MINUTES);
    }

    LightEventListener getHueEventListener() {
        return lightEventListener;
    }

    ManualOverrideTracker getManualOverrideTracker() {
        return manualOverrideTracker;
    }
}
