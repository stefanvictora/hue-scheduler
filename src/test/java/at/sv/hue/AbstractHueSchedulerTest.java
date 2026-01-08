package at.sv.hue;

import at.sv.hue.api.AffectedId;
import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import at.sv.hue.api.ManualOverrideTracker;
import at.sv.hue.api.PutCall;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Slf4j
public class AbstractHueSchedulerTest {
    protected static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    protected static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
    protected static final int ID = 1;
    protected static final int DEFAULT_BRIGHTNESS = 50;
    protected static final int DEFAULT_CT = 400; // very warm. [153-500]
    protected static final double DEFAULT_X = 0.2862;
    protected static final double DEFAULT_Y = 0.4311;
    protected static final LightCapabilities NO_CAPABILITIES = LightCapabilities.builder().build();
    protected static final int MAX_TRANSITION_TIME = ScheduledState.MAX_TRANSITION_TIME;
    protected static final int MAX_TRANSITION_TIME_MS = ScheduledState.MAX_TRANSITION_TIME_MS;
    protected static final int BRIGHTNESS_OVERRIDE_THRESHOLD = 24;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss ");
    private static final int BRIGHTNESS_OVERRIDE_THRESHOLD_PERCENT = 10;
    private static final int COLOR_TEMPERATURE_OVERRIDE_THRESHOLD_KELVIN = 350;
    private static final double COLOR_OVERRIDE_THRESHOLD = 0.06;

    protected final int syncFailureRetryInMinutes = 3;
    protected final String sceneSyncName = "synced-scene";
    private final String unsyncedSceneName = "user-scene";

    protected ManualOverrideTracker manualOverrideTracker;
    protected HueScheduler scheduler;
    protected ZonedDateTime now;
    protected ZonedDateTime initialNow;
    protected LightCapabilities defaultCapabilities;
    protected String nowTimeString;
    protected StartTimeProviderImpl startTimeProvider;
    protected boolean controlGroupLightsIndividually;
    protected HueApi mockedHueApi;
    protected String defaultInterpolationTransitionTimeInMs;
    protected int minTrGap = 0; // in minutes
    protected boolean interpolateAll;
    protected int expectedSceneUpdates;
    protected int sceneSyncDelayInSeconds;
    protected int sceneActivationIgnoreWindowInSeconds;
    protected boolean autoFillGradient;
    private TestStateScheduler stateScheduler;
    private int connectionFailureRetryDelay;
    private boolean disableUserModificationTracking;
    private boolean requireSceneActivation;
    private InOrder orderVerifier;
    private InOrder sceneSyncOrderVerifier;
    private int expectedPutCalls;
    private int expectedGroupPutCalls;
    private int expectedScenePutCalls;
    private boolean enableSceneSync = false;
    private boolean supportsOffLightUpdates = false;

    @BeforeEach
    void setUp() {
        mockedHueApi = mock(HueApi.class);
        resetMockedApi();
        setCurrentAndInitialTimeTo(ZonedDateTime.of(2021, 1, 1, 0, 0, 0,
                0, ZoneId.of("Europe/Vienna")));
        startTimeProvider = new StartTimeProviderImpl(new SunTimesProviderImpl(48.20, 16.39, 165));
        stateScheduler = new TestStateScheduler();
        nowTimeString = now.toLocalTime().toString();
        connectionFailureRetryDelay = 5;
        Double[][] gamut = {{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
        defaultCapabilities = LightCapabilities.builder().ctMin(153).ctMax(500)
                                               .colorGamutType("C")
                                               .colorGamut(gamut)
                                               .effects(List.of("candle"))
                                               .gradientModes(List.of("interpolated_palette",
                                                       "interpolated_palette_mirrored", "random_pixelated",
                                                       "segmented_palette"))
                                               .maxGradientPoints(5)
                                               .capabilities(EnumSet.allOf(Capability.class)).build();
        controlGroupLightsIndividually = false;
        disableUserModificationTracking = true;
        requireSceneActivation = false;
        defaultInterpolationTransitionTimeInMs = null;
        interpolateAll = false;
        sceneSyncDelayInSeconds = 0;
        sceneActivationIgnoreWindowInSeconds = 5;
        autoFillGradient = false;
        create();
    }

    @AfterEach
    void tearDown() {
        ensureScheduledStates(0);
        assertAllPutCallsAsserted();
        assertAllGroupPutCallsAsserted();
        assertAllScenePutCallsAsserted();
        assertAllSceneUpdatesAsserted();
    }

    protected void resetMockedApi() {
        Mockito.reset(mockedHueApi);
        expectedPutCalls = 0;
        expectedGroupPutCalls = 0;
        expectedScenePutCalls = 0;
        expectedSceneUpdates = 0;
        orderVerifier = inOrder(mockedHueApi);
        sceneSyncOrderVerifier = inOrder(mockedHueApi);
    }

    protected void enableUserModificationTracking() {
        disableUserModificationTracking = false;
        create();
    }

    protected void enableSupportForOffLightUpdates() {
        supportsOffLightUpdates = true;
        create();
    }

    protected void enableSceneSync() {
        enableSceneSync = true;
        create();
    }

    protected void requireSceneActivation() {
        requireSceneActivation = true;
        create();
    }

    protected void create() {
        scheduler = new HueScheduler(mockedHueApi, stateScheduler, startTimeProvider,
                () -> now, 10.0, controlGroupLightsIndividually, disableUserModificationTracking,
                requireSceneActivation, defaultInterpolationTransitionTimeInMs, 0, connectionFailureRetryDelay,
                minTrGap, BRIGHTNESS_OVERRIDE_THRESHOLD_PERCENT, COLOR_TEMPERATURE_OVERRIDE_THRESHOLD_KELVIN,
                COLOR_OVERRIDE_THRESHOLD, 3.8, 150, 0.06,
                sceneActivationIgnoreWindowInSeconds, interpolateAll,
                enableSceneSync, sceneSyncName, syncFailureRetryInMinutes, sceneSyncDelayInSeconds, autoFillGradient,
                supportsOffLightUpdates);
        manualOverrideTracker = scheduler.getManualOverrideTracker();
    }

    protected void addDefaultState(int id, ZonedDateTime startTime) {
        addState(id, startTime, AbstractHueSchedulerTest.DEFAULT_BRIGHTNESS, AbstractHueSchedulerTest.DEFAULT_CT);
    }

    protected void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct) {
        addState(id, startTime, brightness, ct, null);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct, Boolean on) {
        addKnownLightIdsWithDefaultCapabilities(id);
        addState(id, startTime, "bri:" + brightness, "ct:" + ct, "on:" + on);
    }

    protected void addDefaultGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        mockGroupLightsForId(groupId, lights);
        mockDefaultGroupCapabilities(groupId);
        addState("g" + groupId, start, "bri:" + AbstractHueSchedulerTest.DEFAULT_BRIGHTNESS, "ct:" + AbstractHueSchedulerTest.DEFAULT_CT);
    }

    protected void addStateNow(Object id, String... properties) {
        addState(id, now, properties);
    }

    protected void addState(Object id, ZonedDateTime startTime, String... properties) {
        addState(id, startTime.toLocalTime().toString(), properties);
    }

    protected void addState(Object id, String startTimeString, String... properties) {
        String nonNullProperties = Arrays.stream(properties)
                                         .filter(p -> !p.contains("null"))
                                         .collect(Collectors.joining("\t"));
        addState(id + "\t" + startTimeString + "\t" + nonNullProperties);
    }

    protected void addState(String input) {
        scheduler.addState(input);
    }

    protected void addDefaultState() {
        addState(AbstractHueSchedulerTest.ID, now, AbstractHueSchedulerTest.DEFAULT_BRIGHTNESS, AbstractHueSchedulerTest.DEFAULT_CT);
    }

    protected void addNullState(ZonedDateTime start) {
        addState(AbstractHueSchedulerTest.ID, start, null, null);
    }

    protected void addOffState() {
        addState(1, now, null, null, false);
    }

    /* Start and expected runnables */

    protected void startScheduler() {
        scheduler.start();
    }

    protected List<ScheduledRunnable> startScheduler(int expectedStates) {
        startScheduler();

        return ensureScheduledStates(expectedStates);
    }

    protected List<ScheduledRunnable> startScheduler(ExpectedRunnable... expectedRunnables) {
        startScheduler();

        return ensureScheduledStates(expectedRunnables);
    }

    protected List<ScheduledRunnable> ensureScheduledStates(ExpectedRunnable... expectedRunnables) {
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(expectedRunnables.length);
        for (int i = 0; i < scheduledRunnables.size(); i++) {
            ExpectedRunnable expectedRunnable = expectedRunnables[i];
            assertScheduleStart(scheduledRunnables.get(i), expectedRunnable.start(), expectedRunnable.end());
        }
        return scheduledRunnables;
    }

    protected void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start, ZonedDateTime endExclusive) {
        assertScheduleStart(state, start);
        assertEnd(state, endExclusive);
    }

    protected List<ScheduledRunnable> ensureScheduledStates(int expectedSize) {
        List<ScheduledRunnable> scheduledRunnables = stateScheduler.getScheduledStates();
        assertThat(scheduledRunnables).hasSize(expectedSize);
        stateScheduler.clear();
        return scheduledRunnables;
    }

    protected ScheduledRunnable startWithDefaultState() {
        addDefaultState();
        startScheduler();

        return ensureScheduledStates(1).getFirst();
    }

    protected ScheduledRunnable startAndGetSingleRunnable(ZonedDateTime scheduledStart, ZonedDateTime endExclusive) {
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(scheduledStart);
        assertEnd(scheduledRunnable, endExclusive);
        return scheduledRunnable;
    }

    protected ScheduledRunnable startAndGetSingleRunnable(ZonedDateTime scheduledStart) {
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, scheduledStart);
        return scheduledRunnable;
    }

    protected ScheduledRunnable startAndGetSingleRunnable() {
        startScheduler();

        return ensureScheduledStates(1).getFirst();
    }

    protected ScheduledRunnable ensureNextDayRunnable() {
        return ensureNextDayRunnable(initialNow);
    }

    protected ScheduledRunnable ensureNextDayRunnable(ZonedDateTime now) {
        return ensureRunnable(now.plusDays(1), now.plusDays(2));
    }

    protected ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart, ZonedDateTime endExclusive) {
        ScheduledRunnable state = ensureRunnable(scheduleStart);
        assertEnd(state, endExclusive);
        return state;
    }

    protected ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart) {
        ScheduledRunnable state = ensureScheduledStates(1).getFirst();
        assertScheduleStart(state, scheduleStart);
        return state;
    }

    protected ScheduledRunnable ensureConnectionFailureRetryState() {
        return ensureRunnable(now.plusSeconds(connectionFailureRetryDelay));
    }

    private void assertEnd(ScheduledRunnable state, ZonedDateTime endExclusive) {
        Duration between = Duration.between(endExclusive.minusSeconds(1), state.getEnd());
        assertThat(state)
                .extracting(ScheduledRunnable::getEnd)
                .withFailMessage("Schedule end differs.\nExpected: %s\nActual  : %s\nDifference: %s\nStart   : %s",
                        endExclusive.minusSeconds(1), state.getEnd(), between, state.getStart())
                .isEqualTo(endExclusive.minusSeconds(1));
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start) {
        Duration between = Duration.between(start, state.getStart());
        assertThat(state)
                .extracting(ScheduledRunnable::getStart)
                .withFailMessage("Schedule start differs.\nExpected: %s\nActual  : %s\nDifference: %s\nEnd     : %s",
                        start, state.getStart(), between, state.getEnd())
                .isEqualTo(start);
    }

    /* Run and expected put calls */

    protected void runAndAssertNextDay(ScheduledRunnable state) {
        advanceTimeAndRunAndAssertPutCalls(state, AbstractHueSchedulerTest.defaultPutCall());

        ensureNextDayRunnable();
    }

    protected void advanceTimeAndRunAndAssertPutCalls(ScheduledRunnable scheduledRunnable, PutCall.PutCallBuilder... putCallBuilders) {
        setCurrentTimeTo(scheduledRunnable);

        runAndAssertPutCalls(scheduledRunnable, putCallBuilders);
    }

    protected void runAndAssertPutCalls(ScheduledRunnable state, PutCall.PutCallBuilder... expectedPutCallBuilders) {
        state.run();

        assertPutCalls(expectedPutCallBuilders);
    }

    protected void assertPutCalls(PutCall.PutCallBuilder... putCallBuilders) {
        for (PutCall.PutCallBuilder putCallBuilder : putCallBuilders) {
            assertPutCall(putCallBuilder.build());
        }
        assertAllPutCallsAsserted();
    }

    private void assertPutCall(PutCall putCall) {
        expectedPutCalls++;
        orderVerifier.verify(mockedHueApi, calls(1)).putState(putCall);
    }

    protected void advanceTimeAndRunAndAssertGroupPutCalls(ScheduledRunnable runnable, PutCall.PutCallBuilder... putCallBuilders) {
        setCurrentTimeTo(runnable);

        runAndAssertGroupPutCalls(runnable, putCallBuilders);
    }

    protected void runAndAssertGroupPutCalls(ScheduledRunnable runnable, PutCall.PutCallBuilder... putCallBuilders) {
        runnable.run();

        assertGroupPutCalls(putCallBuilders);
    }

    protected void assertGroupPutCalls(PutCall.PutCallBuilder... putCallBuilders) {
        for (PutCall.PutCallBuilder putCallBuilder : putCallBuilders) {
            expectedGroupPutCalls++;
            orderVerifier.verify(mockedHueApi, calls(1)).putGroupState(putCallBuilder.build());
        }

        assertAllGroupPutCallsAsserted();
    }

    protected ExpectedRunnable expectedRunnable(ZonedDateTime start, ZonedDateTime endExclusive) {
        return new ExpectedRunnable(start, endExclusive);
    }

    protected ExpectedRunnable expectedPowerOnEnd(ZonedDateTime endExclusive) {
        return expectedRunnable(now, endExclusive);
    }

    protected static PutCall.PutCallBuilder expectedGroupPutCall(int id) {
        return AbstractHueSchedulerTest.expectedPutCall("/groups/" + id);
    }

    protected static PutCall.PutCallBuilder expectedPutCall(int id) {
        return AbstractHueSchedulerTest.expectedPutCall("/lights/" + id);
    }

    protected static PutCall.PutCallBuilder expectedPutCall(Object id) {
        return PutCall.builder().id(id.toString());
    }

    protected int tr(String tr) {
        return InputConfigurationParser.parseTransitionTime("tr", tr);
    }

    protected static PutCall.PutCallBuilder defaultPutCall() {
        return AbstractHueSchedulerTest.expectedPutCall(AbstractHueSchedulerTest.ID).bri(AbstractHueSchedulerTest.DEFAULT_BRIGHTNESS).ct(AbstractHueSchedulerTest.DEFAULT_CT);
    }

    protected static PutCall.PutCallBuilder defaultGroupPutCall() {
        return AbstractHueSchedulerTest.expectedGroupPutCall(AbstractHueSchedulerTest.ID).bri(AbstractHueSchedulerTest.DEFAULT_BRIGHTNESS).ct(AbstractHueSchedulerTest.DEFAULT_CT);
    }

    protected void setCurrentTimeToAndRun(ScheduledRunnable scheduledRunnable) {
        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();
    }

    protected void setCurrentTimeTo(ScheduledRunnable scheduledRunnable) {
        setCurrentTimeTo(scheduledRunnable.getStart());
    }

    protected void setCurrentTimeTo(ZonedDateTime newTime) {
        if (now != null && newTime.isBefore(now)) {
            throw new IllegalArgumentException("New time is before now: " + newTime);
        }
        if (!newTime.equals(now)) {
            MDC.put("context", "test");
            MDC.put("time", TIME_FORMATTER.format(newTime));
            log.info("Advanced time: +{} ({})", Duration.between(now, newTime), DayOfWeek.from(newTime));
        }
        now = newTime;
    }

    protected void setCurrentAndInitialTimeTo(ZonedDateTime dateTime) {
        initialNow = now = dateTime;
        MDC.put("context", "test");
        MDC.put("time", TIME_FORMATTER.format(dateTime));
        log.info("Initial time: {} ({})", now, DayOfWeek.from(now));
    }

    protected void setupTimeWithDayOfWeek(DayOfWeek dayOfWeek) {
        setupTimeWithDayOfWeek(dayOfWeek, LocalTime.MIDNIGHT);
    }

    private void setupTimeWithDayOfWeek(DayOfWeek dayOfWeek, LocalTime time) {
        setCurrentAndInitialTimeTo(now.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time));
    }

    protected void advanceCurrentTime(Duration duration) {
        setCurrentTimeTo(now.plus(duration));
    }

    /* Light state tracking */

    protected void setLightStateResponse(int id, LightState.LightStateBuilder lightStateBuilder) {
        setLightStateResponse("/lights/" + id, lightStateBuilder);
    }

    protected void setLightStateResponse(String id, LightState.LightStateBuilder lightStateBuilder) {
        when(mockedHueApi.getLightState(id)).thenReturn(lightStateBuilder.build());
    }

    protected LightState.LightStateBuilder expectedState() {
        return LightState.builder()
                         .on(true)
                         .lightCapabilities(defaultCapabilities);
    }

    protected void setLightStateResponse(int id, boolean on, String effect) {
        LightState.LightStateBuilder lightStateBuilder = LightState.builder()
                                                                   .brightness(AbstractHueSchedulerTest.DEFAULT_BRIGHTNESS)
                                                                   .colorTemperature(AbstractHueSchedulerTest.DEFAULT_CT)
                                                                   .effect(effect)
                                                                   .on(on)
                                                                   .lightCapabilities(defaultCapabilities);
        setLightStateResponse(id, lightStateBuilder);
    }

    protected void setGroupStateResponses(int id, LightState.LightStateBuilder... lightStatesBuilder) {
        LightState[] lightStates = Arrays.stream(lightStatesBuilder)
                                         .map(LightState.LightStateBuilder::build)
                                         .toArray(LightState[]::new);
        when(mockedHueApi.getGroupStates("/groups/" + id)).thenReturn(Arrays.asList(lightStates));
    }

    /* MOCKS */

    protected void addKnownLightIdsWithDefaultCapabilities(Integer... ids) {
        Arrays.stream(ids).forEach(this::mockDefaultLightCapabilities);
    }

    protected void mockLightIdForName(String name, int id) {
        when(mockedHueApi.getLightIdentifierByName(name)).thenReturn(new Identifier("/lights/" + id, name));
    }

    protected void mockGroupLightsForId(int groupId, Integer... lights) {
        List<String> lightIds = Arrays.stream(lights)
                                      .map(String::valueOf)
                                      .map(id -> "/lights/" + id)
                                      .toList();
        when(mockedHueApi.getGroupLights("/groups/" + groupId)).thenReturn(lightIds);
        for (Integer light : lights) {
            mockAssignedGroups(light, List.of(groupId));
        }
    }

    protected void mockGroupIdForName(String name, int id) {
        when(mockedHueApi.getGroupIdentifierByName(name)).thenReturn(new Identifier("/groups/" + id, name));
    }

    protected void mockLightCapabilities(int id, LightCapabilities.LightCapabilitiesBuilder capabilitiesBuilder) {
        mockLightCapabilities("/lights/" + id, capabilitiesBuilder.build());
    }

    protected void mockLightCapabilities(String id, LightCapabilities capabilities) {
        String name = "test light " + id.substring(id.lastIndexOf('/') + 1);
        when(mockedHueApi.getLightIdentifier(id)).thenReturn(new Identifier(id, name));
        when(mockedHueApi.getLightCapabilities(id)).thenReturn(capabilities);
    }

    protected void mockDefaultLightCapabilities(int id) {
        mockLightCapabilities("/lights/" + id, defaultCapabilities);
    }

    protected void mockDefaultGroupCapabilities(int id) {
        mockGroupCapabilities(id, defaultCapabilities);
    }

    protected void mockGroupCapabilities(int id, LightCapabilities.LightCapabilitiesBuilder capabilitiesBuilder) {
        mockGroupCapabilities(id, capabilitiesBuilder.build());
    }

    protected void mockGroupCapabilities(int id, LightCapabilities capabilities) {
        String groupId = "/groups/" + id;
        when(mockedHueApi.getGroupIdentifier(groupId)).thenReturn(new Identifier(groupId, "test group " + id));
        when(mockedHueApi.getGroupCapabilities(groupId)).thenReturn(capabilities);
    }

    protected void mockAssignedGroups(int lightId, Integer... groups) {
        mockAssignedGroups(lightId, Arrays.asList(groups));
    }

    protected void mockAssignedGroups(int lightId, List<Integer> groups) {
        List<String> groupIds = groups.stream()
                                      .map(String::valueOf)
                                      .map(id -> "/groups/" + id)
                                      .toList();
        lenient().when(mockedHueApi.getAssignedGroups("/lights/" + lightId)).thenReturn(groupIds);
    }

    protected void mockPutStateThrowable(Throwable throwable) {
        doThrow(throwable).when(mockedHueApi).putState(any());
    }

    protected void mockSceneLightStates(int groupId, String sceneName, ScheduledLightState.ScheduledLightStateBuilder... builder) {
        List<ScheduledLightState> states = Arrays.stream(builder)
                                                 .map(ScheduledLightState.ScheduledLightStateBuilder::build)
                                                 .toList();
        when(mockedHueApi.getSceneLightStates("/groups/" + groupId, sceneName)).thenReturn(states);
    }

    protected void mockIsLightOff(int id, boolean value) {
        mockIsLightOff("/lights/" + id, value);
    }

    protected void mockIsLightOff(String id, boolean value) {
        when(mockedHueApi.isLightOff(id)).thenReturn(value);
    }

    protected void mockIsGroupOff(int id, boolean value) {
        when(mockedHueApi.isGroupOff("/groups/" + id)).thenReturn(value);
    }

    protected void mockSceneSyncFailure(String groupId) {
        doThrow(ApiFailure.class).when(mockedHueApi).createOrUpdateScene(eq(groupId), eq(sceneSyncName), any());
    }

    /* Events */

    protected List<ScheduledRunnable> simulateLightOnEvent(ExpectedRunnable... expectedRunnables) {
        return simulateLightOnEvent("/lights/" + AbstractHueSchedulerTest.ID, expectedRunnables);
    }

    protected List<ScheduledRunnable> simulateLightOnEvent(String id, ExpectedRunnable... expectedRunnables) {
        simulateLightOnEvent(id);
        return ensureScheduledStates(expectedRunnables);
    }

    protected void simulateLightOnEvent(String id) {
        scheduler.getHueEventListener().onLightOn(id);
    }

    protected void simulateLightOffEvent(String id) {
        scheduler.getHueEventListener().onLightOff(id);
    }

    protected ScheduledRunnable simulateLightOnEventExpectingSingleScheduledState() {
        simulateLightOnEvent("/lights/" + AbstractHueSchedulerTest.ID);
        return ensureScheduledStates(1).getFirst();
    }

    protected ScheduledRunnable simulateLightOnEventExpectingSingleScheduledState(ZonedDateTime endExclusive) {
        return simulateLightOnEvent(expectedPowerOnEnd(endExclusive)).getFirst();
    }

    protected void simulateSceneActivated(String groupId, String... containedLights) {
        simulateSceneWithNameActivated(groupId, unsyncedSceneName, containedLights);
    }

    protected void simulateSyncedSceneActivated(String groupId, String... containedLights) {
        simulateSceneWithNameActivated(groupId, sceneSyncName, containedLights);
    }

    protected void simulateSceneWithNameActivated(String groupId, String sceneName, String... containedLights) {
        List<String> affectedIds = new ArrayList<>(Arrays.asList(containedLights));
        affectedIds.add(groupId);
        simulateSceneWithNameActivated(sceneName, affectedIds.stream()
                                                             .map(id -> new AffectedId(id, true))
                                                             .toList().toArray(AffectedId[]::new));
    }

    protected void simulateSceneWithNameActivated(String sceneName, AffectedId... affectedIds) {
        String sceneId = "/scene/mocked_scene_" + sceneName;
        when(mockedHueApi.getAffectedIdsByScene(sceneId)).thenReturn(Arrays.asList(affectedIds));
        when(mockedHueApi.getSceneName(sceneId)).thenReturn(sceneName);

        scheduler.getSceneEventListener().onSceneActivated(sceneId);
    }

    /* API Assertions */

    private void assertAllPutCallsAsserted() {
        verify(mockedHueApi, times(expectedPutCalls)).putState(any());
    }

    protected void assertAllScenePutCallsAsserted() {
        verify(mockedHueApi, times(expectedScenePutCalls)).putSceneState(any(), anyList());
    }

    private void assertAllGroupPutCallsAsserted() {
        verify(mockedHueApi, times(expectedGroupPutCalls)).putGroupState(any(PutCall.class));
    }

    protected void assertAllSceneUpdatesAsserted() {
        verify(mockedHueApi, times(expectedSceneUpdates)).createOrUpdateScene(any(), any(), any());
    }

    /* Scene Sync Assertions */

    protected void assertSceneUpdate(String groupId, PutCall.PutCallBuilder... expectedPutCalls) {
        expectedSceneUpdates++;
        List<PutCall> putCalls = Arrays.stream(expectedPutCalls).map(PutCall.PutCallBuilder::build).toList();
        sceneSyncOrderVerifier.verify(mockedHueApi, calls(1)).createOrUpdateScene(groupId, sceneSyncName, putCalls);
    }

    protected void advanceTimeAndRunAndAssertScenePutCalls(ScheduledRunnable runnable, int groupId,
                                                           PutCall.PutCallBuilder... putCallBuilders) {
        setCurrentTimeTo(runnable);

        runnable.run();

        assertScenePutCalls(groupId, putCallBuilders);

        assertAllScenePutCallsAsserted();
    }

    protected void assertScenePutCalls(int groupId, PutCall.PutCallBuilder... putCallBuilders) {
        if (putCallBuilders.length == 0) {
            return;
        }
        List<PutCall> putCalls = Arrays.stream(putCallBuilders)
                                       .map(PutCall.PutCallBuilder::build)
                                       .toList();
        String groupIdString = "/groups/" + groupId;
        expectedScenePutCalls++;
        orderVerifier.verify(mockedHueApi, calls(1)).putSceneState(groupIdString, putCalls);
    }
}
