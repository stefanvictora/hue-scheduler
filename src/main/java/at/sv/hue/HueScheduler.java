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
import at.sv.hue.api.SceneEventListener;
import at.sv.hue.api.SceneEventListenerImpl;
import at.sv.hue.api.hass.HassApiImpl;
import at.sv.hue.api.hass.HassApiUtils;
import at.sv.hue.api.hass.HassEventHandler;
import at.sv.hue.api.hass.HassEventStreamReader;
import at.sv.hue.api.hue.HueApiImpl;
import at.sv.hue.api.hue.HueEventHandler;
import at.sv.hue.api.hue.HueEventStreamReader;
import at.sv.hue.api.hue.HueHttpsClientFactory;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import com.github.benmanes.caffeine.cache.Ticker;
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
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "HueScheduler", version = "0.11.0", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final Map<String, List<ScheduledState>> lightStates;
    @Parameters(
            defaultValue = "${env:API_HOST}",
            description = "The host for your Philips Hue Bridge or origin (i.e. scheme, host, port) for your Home Assistant instance. " +
                          "Examples: Philips Hue: 192.168.0.157; " +
                          "Home Assistant: http://localhost:8123, or https://UNIQUE_ID.ui.nabu.casa")
    String apiHost;
    @Parameters(
            defaultValue = "${env:ACCESS_TOKEN}",
            description = "The Philips Hue Bridge or Home Assistant access token used for authentication.")
    String accessToken;
    @Parameters(paramLabel = "CONFIG_FILE",
            defaultValue = "${env:CONFIG_FILE}",
            description = "The configuration file containing your schedules.")
    Path configFile;
    @Option(names = "--lat", required = true,
            defaultValue = "${env:LAT}",
            description = "The latitude of your location.")
    double latitude;
    @Option(names = "--long", required = true,
            defaultValue = "${env:LONG}",
            description = "The longitude of your location.")
    double longitude;
    @Option(names = "--elevation", paramLabel = "<meters>",
            defaultValue = "${env:ELEVATION:-0.0}",
            description = "The optional elevation (in meters) of your location, " +
                          "used to provide more accurate sunrise and sunset times.")
    double elevation;
    @Option(names = "--max-requests-per-second", paramLabel = "<requests>",
            defaultValue = "${env:MAX_REQUESTS_PER_SECOND:-10.0}",
            description = "The maximum number of PUT API requests to perform per second. Default and recommended: " +
                          "${DEFAULT-VALUE} requests per second.")
    double requestsPerSecond;
    @Option(names = "--control-group-lights-individually",
            defaultValue = "${env:CONTROL_GROUP_LIGHTS_INDIVIDUALLY:-false}",
            description = "Experimental: If the lights in a group should be controlled individually instead of using broadcast messages." +
                          " This might improve performance. Default: ${DEFAULT-VALUE}")
    boolean controlGroupLightsIndividually;
    @Option(names = "--disable-user-modification-tracking",
            defaultValue = "${env:DISABLE_USER_MODIFICATION_TRACKING:-false}",
            description = "Globally disable tracking of user modifications which would pause their schedules until they are turned off and on again." +
                          " Default: ${DEFAULT-VALUE}")
    boolean disableUserModificationTracking;
    @Option(names = "--enable-scene-sync",
            defaultValue = "${env:ENABLE_SCENE_SYNC:-false}",
            description = "Enable the creating of Hue scenes that always match the state of a scheduled group or zone." +
                          " Default: ${DEFAULT-VALUE}")
    boolean enableSceneSync;
    @Option(names = "--scene-sync-name",
            defaultValue = "${env:SCENE_SYNC_NAME:-HueScheduler}",
            description = "The name of the synced scene. Related to '--enable-scene-sync'." +
                          " Default: ${DEFAULT-VALUE}")
    String sceneSyncName;
    @Option(names = "--scene-sync-interpolation-interval",
            defaultValue = "${env:SCENE_SYNC_INTERPOLATION_INTERVAL:-2}",
            description = "The interval for syncing interpolated states to scenes in minutes. Related to '--enable-scene-sync'." +
                          " Default: ${DEFAULT-VALUE}")
    int sceneSyncInterpolationIntervalInMinutes;
    @Option(names = "--interpolate-all",
            defaultValue = "${env:INTERPOLATE_ALL:-false}",
            description = "Globally sets 'interpolate:true' for all states, unless explicitly set otherwise." +
                          " Default: ${DEFAULT-VALUE}")
    private boolean interpolateAll;
    @Option(names = "--default-interpolation-transition-time", paramLabel = "<tr>",
            defaultValue = "${env:DEFAULT_INTERPOLATION_TRANSITION_TIME:-4}",
            description = "The default transition time defined as a multiple of 100ms used for the interpolated call" +
                          " when turning a light on during a tr-before transition. Default: ${DEFAULT-VALUE} (=400 ms)." +
                          " You can also use, e.g., 2s for convenience.")
    String defaultInterpolationTransitionTimeString;
    /**
     * The converted transition time as a multiple of 100ms.
     */
    Integer defaultInterpolationTransitionTime = 4;
    @Option(names = "--power-on-reschedule-delay", paramLabel = "<delay>",
            defaultValue = "${env:POWER_ON_RESCHEDULE_DELAY:-150}",
            description = "The delay in ms after the light on-event was received and the current state should be " +
                          "rescheduled again. Default: ${DEFAULT-VALUE} ms.")
    int powerOnRescheduleDelayInMs;
    @Option(names = "--bridge-failure-retry-delay", paramLabel = "<delay>",
            defaultValue = "${env:BRIDGE_FAILURE_RETRY_DELAY:-10}",
            description = "The delay in seconds for retrying an API call, if the bridge could not be reached due to " +
                          "network failure, or if it returned an API error code. Default: ${DEFAULT-VALUE} seconds.")
    int bridgeFailureRetryDelayInSeconds;
    @Option(names = "--multi-color-adjustment-delay", paramLabel = "<delay>",
            defaultValue = "${env:MULTI_COLOR_ADJUSTMENT_DELAY:-4}",
            description = "The adjustment delay in seconds for each light in a group when using the multi_color effect." +
                          " Adjust to change the hue values of 'neighboring' lights. Default: ${DEFAULT-VALUE} seconds.")
    int multiColorAdjustmentDelay;
    @Option(names = "--event-stream-read-timeout", paramLabel = "<timout>",
            defaultValue = "${env:EVENT_STREAM_READ_TIMEOUT:-120}",
            description = "The read timeout of the API v2 SSE event stream in minutes. " +
                          "The connection is automatically restored after a timeout. Default: ${DEFAULT-VALUE} minutes.")
    int eventStreamReadTimeoutInMinutes;
    @Option(
            names = "--api-cache-invalidation-interval", paramLabel = "<interval>",
            defaultValue = "${env:API_CACHE_INVALIDATION_INTERVAL:-15}",
            description = "The interval in which the api cache for groups and lights should be invalidated. " +
                          "Default: ${DEFAULT-VALUE} minutes."
    )
    int apiCacheInvalidationIntervalInMinutes;
    @Option(
            names = "--min-tr-before-gap", paramLabel = "<gap>",
            defaultValue = "${env:MIN_TR_BEFORE_GAP:-3}",
            description = "The minimum gap between multiple back-to-back tr-before states in minutes. This is needed as otherwise " +
                          "the hue bridge may not yet recognise the end value of the transition and incorrectly marks " +
                          "the light as manually overridden. This gap is automatically added between back-to-back " +
                          "tr-before states. Default: ${DEFAULT-VALUE} minutes."
    )
    int minTrBeforeGapInMinutes;
    @Option(names = "--scene-activation-ignore-window", paramLabel = "<duration>",
            defaultValue = "${env:SCENE_ACTIVATION_IGNORE_WINDOW:-5}",
            description = "The delay in seconds during which turn-on events for affected lights and groups are ignored " +
                          "after a scene activation has been detected. Default: ${DEFAULT-VALUE} seconds.")
    int sceneActivationIgnoreWindowInSeconds;
    private HueApi api;
    private StateScheduler stateScheduler;
    private final ManualOverrideTracker manualOverrideTracker;
    private final LightEventListener lightEventListener;
    private StartTimeProvider startTimeProvider;
    private Supplier<ZonedDateTime> currentTime;
    private SceneEventListenerImpl sceneEventListener;

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
                        int minTrBeforeGapInMinutes, int sceneActivationIgnoreWindowInSeconds, boolean interpolateAll,
                        boolean enableSceneSync, String sceneSyncName, int sceneSyncInterpolationIntervalInMinutes) {
        this();
        this.api = api;
        ZonedDateTime initialTime = currentTime.get();
        this.sceneEventListener = new SceneEventListenerImpl(api,
                () -> Duration.between(initialTime, currentTime.get()).toNanos(), sceneActivationIgnoreWindowInSeconds);
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
        this.sceneActivationIgnoreWindowInSeconds = sceneActivationIgnoreWindowInSeconds;
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        this.interpolateAll = interpolateAll;
        this.enableSceneSync = enableSceneSync;
        this.sceneSyncName = sceneSyncName;
        this.sceneSyncInterpolationIntervalInMinutes = sceneSyncInterpolationIntervalInMinutes;
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

    /**
     * The main entry point for the command line. Creating the necessary api clients for either Hue or Home Assistant.
     */
    @Override
    public void run() {
        MDC.put("context", "init");
        if (HassApiUtils.isHassConnection(accessToken)) {
            setupHassApi();
        } else {
            setupHueApi();
        }
        createAndStart();
    }

    private void setupHassApi() {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request().newBuilder()
                                           .header("Authorization", "Bearer " + accessToken)
                                           .build();
                    return chain.proceed(request);
                })
                .build();
        RateLimiter rateLimiter = RateLimiter.create(requestsPerSecond);
        api = new HassApiImpl(apiHost, new HttpResourceProviderImpl(httpClient), rateLimiter);
        sceneEventListener = new SceneEventListenerImpl(api, Ticker.systemTicker(), sceneActivationIgnoreWindowInSeconds);
        new HassEventStreamReader(HassApiUtils.getHassWebsocketOrigin(apiHost), accessToken, httpClient,
                new HassEventHandler(lightEventListener, sceneEventListener)).start();
    }

    private void setupHueApi() {
        OkHttpClient httpsClient = createHueHttpsClient();
        RateLimiter rateLimiter = RateLimiter.create(requestsPerSecond);
        api = new HueApiImpl(new HttpResourceProviderImpl(httpsClient), apiHost, accessToken, rateLimiter);
        sceneEventListener = new SceneEventListenerImpl(api, Ticker.systemTicker(), sceneActivationIgnoreWindowInSeconds);
        new HueEventStreamReader(apiHost, accessToken, httpsClient, new HueEventHandler(lightEventListener, sceneEventListener),
                eventStreamReadTimeoutInMinutes).start();
    }

    private void createAndStart() {
        startTimeProvider = createStartTimeProvider(latitude, longitude, elevation);
        stateScheduler = createStateScheduler();
        currentTime = ZonedDateTime::now;
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        assertInputIsReadable();
        assertConnectionAndStart();
    }

    private OkHttpClient createHueHttpsClient() {
        try {
            return HueHttpsClientFactory.createHttpsClient(apiHost);
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
        if (!Files.isReadable(configFile)) {
            System.err.println("Given config file '" + configFile.toAbsolutePath() + "' does not exist or is not readable!");
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
            LOG.info("Connected to {}.", apiHost);
        } catch (BridgeConnectionFailure e) {
            LOG.warn("Api not reachable: '{}'. Retrying in 5s.", e.getCause().getLocalizedMessage());
            return false;
        } catch (BridgeAuthenticationFailure e) {
            System.err.println("Api connection rejected: 'Unauthorized user'. Please make sure you use the correct" +
                               " username or access token from the setup process, or try to generate a new one.");
            System.exit(3);
        }
        return true;
    }

    private void parseInput() {
        try {
            Files.lines(configFile)
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
        return getPreviousState(currentState.getSnapshot(dateTime));
    }

    private ScheduledStateSnapshot getPreviousState(ScheduledStateSnapshot currentStateSnapshot) {
        List<ScheduledStateSnapshot> previousStates = getPreviousStatesListIgnoringSame(currentStateSnapshot);
        return previousStates.stream()
                             .filter(previousStateSnapshot -> previousStateSnapshot.getDefinedStart().isBefore(currentStateSnapshot.getDefinedStart()))
                             .findFirst()
                             .orElse(null);
    }

    private List<ScheduledStateSnapshot> getPreviousStatesListIgnoringSame(ScheduledStateSnapshot currentStateSnapshot) {
        ZonedDateTime definedStart = currentStateSnapshot.getDefinedStart();
        ZonedDateTime theDayBefore = definedStart.minusDays(1).truncatedTo(ChronoUnit.DAYS).withEarlierOffsetAtOverlap();
        return getLightStatesForId(currentStateSnapshot.getId())
                .stream()
                .flatMap(state -> Stream.of(state.getSnapshot(theDayBefore), state.getSnapshot(definedStart)))
                .sorted(Comparator.comparing(ScheduledStateSnapshot::getDefinedStart, Comparator.reverseOrder())
                                  .thenComparing(snapshot -> snapshot.getScheduledState().hasTransitionBefore()))
                .distinct()
                .filter(snapshot -> currentStateSnapshot.getScheduledState().isNotSameState(snapshot.getScheduledState()))
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
            if (shouldSyncScene(state)) {
                syncScene(state.getSnapshot(currentTime.get()));
            }
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
                if (shouldTrackUserModification(state) && (turnedOnThroughScene(state) || stateHasBeenManuallyOverriddenSinceLastSeen(state))) {
                    LOG.info("Manually overridden or scene turn-on: Pause updates until turned off and on again");
                    manualOverrideTracker.onManuallyOverridden(state.getId());
                    retryWhenBackOn(state);
                    return;
                }
                putAdditionalInterpolatedStateIfNeeded(state);
                putState(state);
            } catch (BridgeConnectionFailure e) {
                LOG.warn("Api not reachable, retrying in {}s.", bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            } catch (ApiFailure e) {
                LOG.error("Api call failed: '{}'. Retrying in {}s.", e.getLocalizedMessage(), bridgeFailureRetryDelayInSeconds);
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

    private boolean shouldSyncScene(ScheduledState state) {
        return state.isGroupState() && enableSceneSync && !manualOverrideTracker.wasJustTurnedOn(state.getId());
    }

    private void syncScene(ScheduledStateSnapshot state) {
        Interpolation interpolation = getInterpolatedPutCallIfNeeded(state);
        syncScene(state.getId(), getScenePutCall(state, interpolation));
        if (interpolation != null) {
            scheduleNextSceneSync(state);
        }
    }

    private PutCall getScenePutCall(ScheduledStateSnapshot state, Interpolation interpolation) {
        if (interpolation != null) {
            return interpolation.putCall();
        } else {
            return state.getPutCall(currentTime.get());
        }
    }

    private void syncScene(String id, PutCall scenePutCall) {
        api.createOrUpdateScene(id, scenePutCall, sceneSyncName);
    }

    private void scheduleNextSceneSync(ScheduledStateSnapshot stateSnapshot) {
        ZonedDateTime end = stateSnapshot.getEnd();
        stateScheduler.schedule(() -> {
            if (currentTime.get().isAfter(end)) {
                return;
            }
            syncScene(stateSnapshot);
        }, currentTime.get().plusMinutes(sceneSyncInterpolationIntervalInMinutes), end);
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

    private boolean shouldTrackUserModification(ScheduledState state) {
        return !disableUserModificationTracking && !state.isForced();
    }

    private boolean turnedOnThroughScene(ScheduledState state) {
        return manualOverrideTracker.wasJustTurnedOn(state.getId()) && sceneEventListener.wasRecentlyAffectedByAScene(state.getId());
    }

    private boolean stateHasBeenManuallyOverriddenSinceLastSeen(ScheduledState scheduledState) {
        if (manualOverrideTracker.wasJustTurnedOn(scheduledState.getId())) {
            return false;
        }
        ScheduledState lastSeenState = getLastSeenState(scheduledState);
        if (lastSeenState == null) {
            return false;
        }
        if (scheduledState.isGroupState()) {
            return api.getGroupStates(scheduledState.getId()).stream().anyMatch(this::isGroupStateDifferent);
        } else {
            return lastSeenState.lightStateDiffers(api.getLightState(scheduledState.getId()));
        }
    }

    private boolean isGroupStateDifferent(LightState lightState) {
        ScheduledState lastSeenLightState = getLastSeenState(lightState.getId());
        if (lastSeenLightState != null) {
            return lastSeenLightState.lightStateDiffers(lightState);
        }
        return allSeenGroupStatesDiffer(lightState);
    }

    private boolean allSeenGroupStatesDiffer(LightState lightState) {
        return api.getAssignedGroups(lightState.getId())
                  .stream()
                  .map(this::getLastSeenState)
                  .filter(Objects::nonNull)
                  .allMatch(lastSeenGroupState -> lastSeenGroupState.lightStateDiffers(lightState));
    }

    private ScheduledState getLastSeenState(ScheduledState currentState) {
        return getLastSeenState(currentState.getId());
    }

    private ScheduledState getLastSeenState(String id) {
        List<ScheduledState> lightStatesForId = getLightStatesForId(id);
        if (lightStatesForId == null) {
            return null;
        }
        return lightStatesForId.stream()
                               .filter(state -> state.getLastSeen() != null)
                               .max(Comparator.comparing(ScheduledState::getLastSeen))
                               .orElse(null);
    }

    private void putAdditionalInterpolatedStateIfNeeded(ScheduledState state) {
        Interpolation interpolation = getInterpolatedPutCallIfNeeded(state.getSnapshot(state.getLastDefinedStart()));
        if (interpolation == null) {
            return;
        }
        ScheduledState previousState = interpolation.previousState();
        ScheduledState lastSeenState = getLastSeenState(state);
        if ((lastSeenState == previousState || state.isSameState(lastSeenState) && state.isSplitState())
            && !manualOverrideTracker.wasJustTurnedOn(state.getId())) {
            return; // skip interpolations if the previous or current state was the last state set without any power cycles
        }
        PutCall interpolatedPutCall = interpolation.putCall();
        LOG.trace("Perform interpolation from previous state: {}", previousState);
        Integer interpolationTransitionTime = getInterpolationTransitionTime(previousState);
        interpolatedPutCall.setTransitionTime(interpolationTransitionTime);
        putState(previousState, interpolatedPutCall);
        sleepIfNeeded(interpolationTransitionTime);
    }

    private Interpolation getInterpolatedPutCallIfNeeded(ScheduledStateSnapshot stateSnapshot) {
        if (!stateSnapshot.hasTransitionBefore()) {
            return null;
        }
        ZonedDateTime dateTime = currentTime.get();
        ScheduledStateSnapshot previousStateSnapshot = getPreviousState(stateSnapshot);
        if (previousStateSnapshot == null) {
            return null;
        }
        PutCall interpolatedPutCall =  new StateInterpolator(stateSnapshot, previousStateSnapshot, dateTime, true)
                .getInterpolatedPutCall();
        if (interpolatedPutCall == null) {
            return null;
        }
        return new Interpolation(previousStateSnapshot.getScheduledState(), interpolatedPutCall);
    }

    private record Interpolation(ScheduledState previousState, PutCall putCall) {
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
        return getLightStatesForId(state.getId());
    }

    private List<ScheduledState> getLightStatesForId(String id) {
        return lightStates.get(id);
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
        return !state.isOff() && state.hasOtherPropertiesThanOn() || state.isForced();
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
        stateScheduler.scheduleAtFixedRate(
                startTimeProvider::clearCaches, 3, 3, TimeUnit.DAYS);
    }

    LightEventListener getHueEventListener() {
        return lightEventListener;
    }

    SceneEventListener getSceneEventListener() {
        return sceneEventListener;
    }

    ManualOverrideTracker getManualOverrideTracker() {
        return manualOverrideTracker;
    }
}
