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
import at.sv.hue.api.hass.area.HassAreaRegistry;
import at.sv.hue.api.hass.area.HassAreaRegistryImpl;
import at.sv.hue.api.hass.area.HassWebSocketClientImpl;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Command(name = "HueScheduler", version = "0.12.4", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

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
    @Option(names = "--require-scene-activation",
            defaultValue = "${env:REQUIRE_SCENE_ACTIVATION:-false}",
            description = "When enabled, states will only be applied when triggered by a synced scene activation. " +
                          "After scene activation, the current state and subsequent states will be applied until " +
                          "lights are turned off or manually modified. Use in combination with --enable-scene-sync. " +
                          "Default: ${DEFAULT-VALUE}")
    boolean requireSceneActivation;
    @Option(names = "--enable-scene-sync",
            defaultValue = "${env:ENABLE_SCENE_SYNC:-false}",
            description = "Enable the creating of Hue scenes that always match the state of a scheduled room or zone." +
                          " Default: ${DEFAULT-VALUE}")
    boolean enableSceneSync;
    @Option(names = "--scene-sync-name",
            defaultValue = "${env:SCENE_SYNC_NAME:-HueScheduler}",
            description = "The name of the synced scene. Related to '--enable-scene-sync'." +
                          " Default: ${DEFAULT-VALUE}")
    String sceneSyncName;
    @Option(names = "--scene-sync-interval",
            defaultValue = "${env:SCENE_SYNC_INTERVAL:-2}",
            description = "The interval for syncing interpolated states to scenes in minutes. Related to '--enable-scene-sync'." +
                          " Default: ${DEFAULT-VALUE}")
    int sceneSyncIntervalInMinutes;
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
    @Option(names = "--event-stream-read-timeout", paramLabel = "<timout>",
            defaultValue = "${env:EVENT_STREAM_READ_TIMEOUT:-120}",
            description = "The read timeout of the API v2 SSE event stream in minutes. " +
                          "The connection is automatically restored after a timeout. Default: ${DEFAULT-VALUE} minutes.")
    int eventStreamReadTimeoutInMinutes;
    @Option(
            names = "--api-cache-invalidation-interval", paramLabel = "<interval>",
            defaultValue = "${env:API_CACHE_INVALIDATION_INTERVAL:-30}",
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
    @Option(names = "--insecure",
            defaultValue = "${env:INSECURE:-false}",
            description = "Disables certificate validation for older bridges using self-signed certificates." +
                          " Default: ${DEFAULT-VALUE}")
    private boolean insecure;
    private HueApi api;
    private StateScheduler stateScheduler;
    private final ManualOverrideTracker manualOverrideTracker;
    private final LightEventListener lightEventListener;
    private StartTimeProvider startTimeProvider;
    private Supplier<ZonedDateTime> currentTime;
    private SceneEventListenerImpl sceneEventListener;
    private ScheduledStateRegistry stateRegistry;
    private int sceneSyncDelayInSeconds = 5;

    public HueScheduler() {
        currentTime = ZonedDateTime::now;
        manualOverrideTracker = new ManualOverrideTrackerImpl();
        lightEventListener = new LightEventListenerImpl(manualOverrideTracker,
                deviceId -> api.getAffectedIdsByDevice(deviceId),
                id -> sceneEventListener.wasRecentlyAffectedBySyncedScene(id));
    }

    public HueScheduler(HueApi api, StateScheduler stateScheduler,
                        StartTimeProvider startTimeProvider, Supplier<ZonedDateTime> currentTime,
                        double requestsPerSecond, boolean controlGroupLightsIndividually,
                        boolean disableUserModificationTracking, boolean requireSceneActivation,
                        String defaultInterpolationTransitionTimeString,
                        int powerOnRescheduleDelayInMs, int bridgeFailureRetryDelayInSeconds,
                        int minTrBeforeGapInMinutes, int sceneActivationIgnoreWindowInSeconds, boolean interpolateAll,
                        boolean enableSceneSync, String sceneSyncName, int sceneSyncIntervalInMinutes,
                        int sceneSyncDelayInSeconds) {
        this();
        this.api = api;
        ZonedDateTime initialTime = currentTime.get();
        this.sceneEventListener = new SceneEventListenerImpl(api,
                () -> Duration.between(initialTime, currentTime.get()).toNanos(),
                sceneActivationIgnoreWindowInSeconds, sceneSyncName::equals, lightEventListener);
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        this.requestsPerSecond = requestsPerSecond;
        this.controlGroupLightsIndividually = controlGroupLightsIndividually;
        this.disableUserModificationTracking = disableUserModificationTracking;
        this.requireSceneActivation = requireSceneActivation;
        this.defaultInterpolationTransitionTimeString = defaultInterpolationTransitionTimeString;
        this.powerOnRescheduleDelayInMs = powerOnRescheduleDelayInMs;
        this.bridgeFailureRetryDelayInSeconds = bridgeFailureRetryDelayInSeconds;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.sceneActivationIgnoreWindowInSeconds = sceneActivationIgnoreWindowInSeconds;
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        this.interpolateAll = interpolateAll;
        this.enableSceneSync = enableSceneSync;
        this.sceneSyncName = sceneSyncName;
        this.sceneSyncIntervalInMinutes = sceneSyncIntervalInMinutes;
        this.sceneSyncDelayInSeconds = sceneSyncDelayInSeconds;
        apiCacheInvalidationIntervalInMinutes = 15;
        stateRegistry = new ScheduledStateRegistry(currentTime, api);
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
        String websocketOrigin = HassApiUtils.getHassWebsocketOrigin(apiHost);
        HassAreaRegistry areaRegistry = new HassAreaRegistryImpl(
                new HassWebSocketClientImpl(websocketOrigin, accessToken, httpClient, 5));
        api = new HassApiImpl(apiHost, new HttpResourceProviderImpl(httpClient), areaRegistry, rateLimiter);
        sceneEventListener = new SceneEventListenerImpl(api, Ticker.systemTicker(),
                sceneActivationIgnoreWindowInSeconds,
                sceneName -> HassApiUtils.matchesSceneSyncName(sceneName, sceneSyncName), lightEventListener);
        new HassEventStreamReader(websocketOrigin, accessToken, httpClient,
                new HassEventHandler(lightEventListener, sceneEventListener)).start();
        stateRegistry = new ScheduledStateRegistry(currentTime, api);
    }

    private void setupHueApi() {
        OkHttpClient httpsClient = createHueHttpsClient();
        RateLimiter rateLimiter = RateLimiter.create(requestsPerSecond);
        api = new HueApiImpl(new HttpResourceProviderImpl(httpsClient), apiHost, rateLimiter, apiCacheInvalidationIntervalInMinutes);
        sceneEventListener = new SceneEventListenerImpl(api, Ticker.systemTicker(),
                sceneActivationIgnoreWindowInSeconds, sceneSyncName::equals, lightEventListener);
        new HueEventStreamReader(apiHost, accessToken, httpsClient, new HueEventHandler(lightEventListener, sceneEventListener, api),
                eventStreamReadTimeoutInMinutes).start();
        stateRegistry = new ScheduledStateRegistry(currentTime, api);
    }

    private void createAndStart() {
        startTimeProvider = createStartTimeProvider(latitude, longitude, elevation);
        stateScheduler = createStateScheduler();
        defaultInterpolationTransitionTime = parseInterpolationTransitionTime(defaultInterpolationTransitionTimeString);
        assertInputIsReadable();
        assertConnectionAndStart();
    }

    private OkHttpClient createHueHttpsClient() {
        try {
            return HueHttpsClientFactory.createHttpsClient(apiHost, accessToken, insecure);
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
                .forEach(stateRegistry::addState);
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        scheduleSolarDataInfoLog();
        stateRegistry.values().stream()
                     .flatMap(states -> setupInitialStartup(states, now).stream())
                     .sorted(Comparator.comparing(ScheduledStateSnapshot::getId)
                                       .thenComparing(ScheduledStateSnapshot::getDefinedStart))
                     .forEach(snapshot -> initialSchedule(snapshot, now));
        scheduleApiCacheClear();
    }

    /**
     * Prepare the given states. To correctly schedule cross-over states, i.e., states that already started yesterday,
     * we initially calculate all end times using yesterday, and reschedule them if needed afterward.
     */
    private List<ScheduledStateSnapshot> setupInitialStartup(List<ScheduledState> states, ZonedDateTime now) {
        MDC.put("context", "init");
        ZonedDateTime yesterday = now.minusDays(1);
        states.forEach(state -> {
            state.setPreviousStateLookup(stateRegistry::getPreviousState);
            state.setNextStateLookup(stateRegistry::getNextStateAfter);
        });
        return states.stream().map(state -> state.getSnapshot(yesterday)).toList();
    }

    private void initialSchedule(ScheduledStateSnapshot snapshot, ZonedDateTime now) {
        ZonedDateTime yesterday = now.minusDays(1);
        if (snapshot.endsBefore(now)) {
            reschedule(snapshot); // no cross-over state, reschedule normally today
        } else if (doesNotStartOn(snapshot, yesterday)) {
            schedule(snapshot, snapshot.getDelayUntilStart(now)); // state was already scheduled at a future time, reuse calculated start time
        } else {
            schedule(snapshot, 0); // schedule cross-over states, i.e., states already starting yesterday immediately
        }
    }

    private boolean doesNotStartOn(ScheduledStateSnapshot snapshot, ZonedDateTime dateTime) {
        return !snapshot.isScheduledOn(DayOfWeek.from(dateTime));
    }

    private void schedule(ScheduledStateSnapshot snapshot, long delayInMs) {
        if (snapshot.isNullState()) return;
        long overlappingDelayInMs = getPotentialOverlappingDelayInMs(snapshot);
        LOG.debug("Schedule: {} in {}", snapshot, Duration.ofMillis(delayInMs + overlappingDelayInMs).withNanos(0));
        stateScheduler.schedule(() -> {
            MDC.put("context", snapshot.getContextName());
            if (snapshot.endsBefore(currentTime.get())) {
                LOG.debug("Already ended: {}", snapshot);
                reschedule(snapshot);
                return;
            }
            if (shouldSyncScene(snapshot)) {
                scheduleAsyncSceneSync(snapshot);
                MDC.put("context", snapshot.getContextName());
            }
            if (requireSceneActivation && wasNotTurnedOnBySyncedScene(snapshot)) {
                LOG.debug("Ignore state: {}", snapshot);
                createOnPowerOnCopyAndReschedule(snapshot);
                // todo: should we rest the "justTurnedOnFlag?" -- also for other reschedules?
                return;
            }
            LOG.info("Set: {}", snapshot);
            try {
                if (wasNotJustTurnedOn(snapshot) &&     // todo: wasJustTurnedOn is not working if we have gaps in the schedule
                    (lightHasBeenManuallyOverriddenBefore(snapshot) || lightIsOffAndDoesNotTurnOn(snapshot))) {
                    LOG.info("Off or manually overridden: Skip update");
                    createOnPowerOnCopyAndReschedule(snapshot);
                    return;
                }
                if (shouldTrackUserModification(snapshot) &&
                    (turnedOnThroughNormalScene(snapshot) || stateHasBeenManuallyOverriddenSinceLastSeen(snapshot))) {
                    LOG.info("Manually overridden or scene turn-on: Pause updates until turned off and on again");
                    manualOverrideTracker.onManuallyOverridden(snapshot.getId());
                    createOnPowerOnCopyAndReschedule(snapshot);
                    return;
                }
                boolean performedInterpolation = putAdditionalInterpolatedStateIfNeeded(snapshot);
                putState(snapshot, performedInterpolation);
            } catch (BridgeConnectionFailure | ApiFailure e) {
                logException(e);
                retry(snapshot, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            }
            snapshot.recordLastSeen(currentTime.get());
            manualOverrideTracker.onAutomaticallyAssigned(snapshot.getId());
            if (snapshot.isOff()) {
                LOG.info("Turned off");
            }
            createOnPowerOnCopyAndReschedule(snapshot);
        }, currentTime.get().plus(delayInMs + overlappingDelayInMs, ChronoUnit.MILLIS), snapshot.getEnd());
    }

    private void createOnPowerOnCopyAndReschedule(ScheduledStateSnapshot snapshot) {
        if (snapshot.isRetryAfterPowerOnState()) {
            retryWhenBackOn(snapshot);
            return;
        }
        if (snapshot.isInsideSplitCallWindow(currentTime.get())) {
            ScheduledStateSnapshot nextSplitSnapshot = createTemporaryFollowUpSplitState(snapshot);
            schedule(nextSplitSnapshot, snapshot.getNextInterpolationSplitDelayInMs(currentTime.get()));
        }
        if (shouldRetryOnPowerOn(snapshot)) {
            ScheduledStateSnapshot powerOnCopy = createPowerOnCopy(snapshot);
            LOG.trace("Created power-on runnable: {}", powerOnCopy);
            retryWhenBackOn(powerOnCopy);
        }
        reschedule(snapshot);
    }

    private long getPotentialOverlappingDelayInMs(ScheduledStateSnapshot snapshot) {
        try {
            return Duration.ofSeconds(stateRegistry.countOverlappingGroupStatesWithMoreLights(snapshot)).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean shouldSyncScene(ScheduledStateSnapshot state) {
        return enableSceneSync && !state.isTemporary() && !state.isSkipSceneSync();
    }

    private boolean wasNotJustTurnedOn(ScheduledStateSnapshot state) {
        return !wasJustTurnedOn(state);
    }

    private boolean wasJustTurnedOn(ScheduledStateSnapshot state) {
        return manualOverrideTracker.wasJustTurnedOn(state.getId());
    }

    private void scheduleAsyncSceneSync(ScheduledStateSnapshot state) {
        if (sceneSyncDelayInSeconds == 0) {
            syncScene(state);
        } else {
            stateScheduler.schedule(() -> {
                syncScene(state);
            }, currentTime.get().plusSeconds(sceneSyncDelayInSeconds), state.getEnd());
        }
    }

    private void syncScene(ScheduledStateSnapshot state) {
        MDC.put("context", state.getContextName() + " (scene sync)");
        try {
            stateRegistry.getAssignedGroups(state)
                         .forEach(groupInfo -> syncScene(groupInfo.groupId(), stateRegistry.getPutCalls(groupInfo.groupLights())));
            if (state.performsInterpolation(currentTime.get())) {
                scheduleNextSceneSync(state);
            }
        } catch (Exception e) {
            LOG.error("Scene sync failed: '{}'. Retry in {}min", e.getLocalizedMessage(), sceneSyncIntervalInMinutes);
            scheduleNextSceneSync(state);
        }
    }

    private void syncScene(String groupId, List<PutCall> putCalls) {
        api.createOrUpdateScene(groupId, sceneSyncName, putCalls);
    }

    private void scheduleNextSceneSync(ScheduledStateSnapshot stateSnapshot) {
        ZonedDateTime end = stateSnapshot.getEnd();
        stateScheduler.schedule(() -> {
            if (currentTime.get().isAfter(end)) {
                return;
            }
            syncScene(stateSnapshot);
        }, currentTime.get().plusMinutes(sceneSyncIntervalInMinutes), end);
    }

    private boolean lightHasBeenManuallyOverriddenBefore(ScheduledStateSnapshot state) {
        return manualOverrideTracker.isManuallyOverridden(state.getId());
    }

    private boolean lightIsOffAndDoesNotTurnOn(ScheduledStateSnapshot state) {
        return !state.isOn() && (lastSeenAsOff(state.getId()) || isCurrentlyOff(state));
    }

    private boolean lastSeenAsOff(String id) {
        return manualOverrideTracker.isOff(id);
    }

    private boolean isCurrentlyOff(ScheduledStateSnapshot state) {
        boolean off = isGroupOrLightOff(state);
        if (off) {
            manualOverrideTracker.onLightOff(state.getId());
        }
        return off;
    }

    private boolean isGroupOrLightOff(ScheduledStateSnapshot state) {
        boolean off;
        if (state.isGroupState()) {
            off = api.isGroupOff(state.getId());
        } else {
            off = api.isLightOff(state.getId());
        }
        return off;
    }

    private boolean shouldTrackUserModification(ScheduledStateSnapshot state) {
        return !disableUserModificationTracking && !state.isForced();
    }

    private boolean wasNotTurnedOnBySyncedScene(ScheduledStateSnapshot snapshot) {
        return !manualOverrideTracker.wasTurnedOnBySyncedScene(snapshot.getId());
    }

    private boolean turnedOnThroughNormalScene(ScheduledStateSnapshot state) {
        return wasJustTurnedOn(state) && sceneEventListener.wasRecentlyAffectedByNormalScene(state.getId());
    }

    private boolean stateHasBeenManuallyOverriddenSinceLastSeen(ScheduledStateSnapshot state) {
        if (wasJustTurnedOn(state)) {
            return false;
        }
        if (state.hasGapBefore()) {
            return false; // ignore modifications if there is a gap in the schedule
        }
        ScheduledState lastSeenState = stateRegistry.getLastSeenState(state);
        if (lastSeenState == null) {
            return false;
        }
        if (state.isGroupState()) {
            return api.getGroupStates(state.getId()).stream().anyMatch(this::isGroupStateDifferent);
        } else {
            return lastSeenState.lightStateDiffers(api.getLightState(state.getId()));
        }
    }

    private boolean isGroupStateDifferent(LightState lightState) {
        ScheduledState lastSeenLightState = stateRegistry.getLastSeenState(lightState.getId());
        if (lastSeenLightState != null) {
            return lastSeenLightState.lightStateDiffers(lightState);
        }
        return allSeenGroupStatesDiffer(lightState);
    }

    private boolean allSeenGroupStatesDiffer(LightState lightState) { // todo: I think this does not work well with our new overlapping groups logik
        return api.getAssignedGroups(lightState.getId())
                  .stream()
                  .map(stateRegistry::getLastSeenState)
                  .filter(Objects::nonNull)
                  .allMatch(lastSeenGroupState -> lastSeenGroupState.lightStateDiffers(lightState));
    }

    private boolean putAdditionalInterpolatedStateIfNeeded(ScheduledStateSnapshot state) {
        PutCall interpolatedPutCall = state.getInterpolatedPutCallIfNeeded(currentTime.get());
        if (interpolatedPutCall == null) {
            return false;
        }
        ScheduledStateSnapshot previousState = state.getPreviousState();
        ScheduledState lastSeenState = stateRegistry.getLastSeenState(state);
        if ((lastSeenState == previousState.getScheduledState() || state.isSameState(lastSeenState) && state.isSplitState())
            && wasNotJustTurnedOn(state)) {
            return false; // skip interpolations if the previous or current state was the last state set without any power cycles
        }
        LOG.trace("Perform interpolation from previous state: {}", previousState);
        Integer interpolationTransitionTime = getInterpolationTransitionTime(previousState);
        interpolatedPutCall.setTransitionTime(interpolationTransitionTime);
        putState(previousState, interpolatedPutCall);
        sleepIfNeeded(interpolationTransitionTime);
        return true;
    }

    private Integer getInterpolationTransitionTime(ScheduledStateSnapshot previousState) {
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

    private void reschedule(ScheduledStateSnapshot snapshot) {
        if (snapshot.isTemporary()) return;
        ZonedDateTime now = currentTime.get();
        ScheduledStateSnapshot nextSnapshot = snapshot.getNextDaySnapshot(now);
        schedule(nextSnapshot, nextSnapshot.getDelayUntilStart(now));
    }

    private long getMs(long seconds) {
        return seconds * 1000L;
    }

    private void putState(ScheduledStateSnapshot state, boolean performedInterpolation) {
        ZonedDateTime now = currentTime.get();
        if (state.isInsideSplitCallWindow(now)) {
            PutCall interpolatedSplitPutCall = state.getNextInterpolatedSplitPutCall(now);
            if (interpolatedSplitPutCall == null) {
                return;
            }
            performPutApiCall(state, interpolatedSplitPutCall);
        } else {
            putState(state, getPutCallWithAdjustedTr(state, now, performedInterpolation));
        }
    }

    private static ScheduledStateSnapshot createTemporaryFollowUpSplitState(ScheduledStateSnapshot state) {
        ScheduledState temporaryCopy = ScheduledState.createTemporaryCopy(state.getScheduledState());
        ScheduledStateSnapshot nextSplitSnapshot = temporaryCopy.getSnapshot(state.getDefinedStart());
        nextSplitSnapshot.overwriteEnd(state.getEnd());
        return nextSplitSnapshot;
    }

    private void putState(ScheduledStateSnapshot state, PutCall putCall) {
        if (state.isGroupState() && controlGroupLightsIndividually) {
            for (PutCall call : stateRegistry.getPutCalls(state)) {
                try {
                    performPutApiCall(state, call);
                } catch (ApiFailure e) {
                    LOG.trace("Unsupported api call for light id {}: {}", call.getId(), e.getLocalizedMessage());
                }
            }
        } else {
            performPutApiCall(state, putCall);
        }
    }

    private void performPutApiCall(ScheduledStateSnapshot state, PutCall putCall) {
        LOG.trace("{}", putCall);
        state.recordLastPutCall(putCall);
        api.putState(putCall);
    }

    private PutCall getPutCallWithAdjustedTr(ScheduledStateSnapshot state, ZonedDateTime now, boolean performedInterpolation) {
        PutCall putCall;
        if (shouldUseFullPicture(state, performedInterpolation)) {
            putCall = state.getFullPicturePutCall(now);
        } else {
            putCall = state.getPutCall(now);
        }
        if (putCall.getTransitionTime() == null) {
            return putCall;
        }
        ScheduledStateSnapshot nextState = state.getNextState();
        Duration duration = Duration.between(now, nextState.getStart()).abs();
        Duration tr = Duration.ofMillis(putCall.getTransitionTime() * 100);
        long differenceInMinutes = duration.minus(tr).toMinutes();
        int requiredGap = state.getRequiredGap();
        if (differenceInMinutes < requiredGap) {
            putCall.setTransitionTime(getAdjustedTransitionTime(duration, requiredGap, nextState));
        }
        return putCall;
    }

    private boolean shouldUseFullPicture(ScheduledStateSnapshot state, boolean performedInterpolation) {
        if (performedInterpolation) {
            return false;
        }
        return wasJustTurnedOn(state) || isFirstTimeStateSeen(state);
    }

    private boolean isFirstTimeStateSeen(ScheduledStateSnapshot state) {
        return stateRegistry.getLastSeenState(state) == null;
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

    private void logException(RuntimeException e) {
        if (e instanceof BridgeConnectionFailure) {
            LOG.warn("Api not reachable, retrying in {}s.", bridgeFailureRetryDelayInSeconds);
        } else {
            LOG.error("Api call failed: '{}'. Retrying in {}s.", e.getLocalizedMessage(), bridgeFailureRetryDelayInSeconds);
        }
    }

    private void retry(ScheduledStateSnapshot snapshot, long delayInMs) {
        schedule(snapshot, delayInMs);
    }

    private ScheduledStateSnapshot createPowerOnCopy(ScheduledStateSnapshot state) {
        ScheduledState powerOnCopy = ScheduledState.createTemporaryCopy(state.getScheduledState());
        powerOnCopy.setRetryAfterPowerOnState(true);
        ScheduledStateSnapshot snapshot = powerOnCopy.getSnapshot(state.getDefinedStart());
        snapshot.overwriteEnd(state.calculateNextPowerOnEnd(currentTime.get()));
        return snapshot;
    }

    private static boolean shouldRetryOnPowerOn(ScheduledStateSnapshot state) {
        return !state.isOff() && state.hasOtherPropertiesThanOn() || state.isForced();
    }

    private void retryWhenBackOn(ScheduledStateSnapshot snapshot) {
        snapshot.setSkipSceneSync(true);
        lightEventListener.runWhenTurnedOn(snapshot.getId(), () -> schedule(snapshot, powerOnRescheduleDelayInMs));
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
        // todo: remove when all api clients support event based cache invalidation
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
