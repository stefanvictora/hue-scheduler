package at.sv.hue;

import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.GroupInfo;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.ManualOverrideTracker;
import at.sv.hue.api.PutCall;
import at.sv.hue.time.InvalidStartTimeExpression;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.MDC;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@Slf4j
class HueSchedulerTest {

    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss ");
    private static final int ID = 1;
    private static final int DEFAULT_BRIGHTNESS = 50;
    private static final int DEFAULT_CT = 400; // very warm. [153-500]
    private static final double DEFAULT_X = 0.2862;
    private static final double DEFAULT_Y = 0.4311;
    private static final LightCapabilities NO_CAPABILITIES = LightCapabilities.builder().build();
    private static final int MAX_TRANSITION_TIME = ScheduledState.MAX_TRANSITION_TIME;
    private static final int MAX_TRANSITION_TIME_MS = ScheduledState.MAX_TRANSITION_TIME_MS;
    private static final int BRIGHTNESS_OVERRIDE_THRESHOLD = 24;
    private static final int BRIGHTNESS_OVERRIDE_THRESHOLD_PERCENT = 10;
    private static final int COLOR_TEMPERATURE_OVERRIDE_THRESHOLD_KELVIN = 350;
    private static final double COLOR_OVERRIDE_THRESHOLD = 8.0;
    private final String sceneSyncName = "synced-scene";
    private final String unsyncedSceneName = "user-scene";
    private final int syncFailureRetryInMinutes = 3;

    private TestStateScheduler stateScheduler;
    private ManualOverrideTracker manualOverrideTracker;
    private HueScheduler scheduler;
    private ZonedDateTime now;
    private ZonedDateTime initialNow;
    private LightCapabilities defaultCapabilities;
    private String nowTimeString;
    private int connectionFailureRetryDelay;
    private StartTimeProviderImpl startTimeProvider;
    private boolean controlGroupLightsIndividually;
    private boolean disableUserModificationTracking;
    private boolean requireSceneActivation;
    private HueApi mockedHueApi;
    private InOrder orderVerifier;
    private InOrder sceneSyncOrderVerifier;
    private int expectedPutCalls;
    private int expectedGroupPutCalls;
    private int expectedScenePutCalls;
    private String defaultInterpolationTransitionTimeInMs;
    private int minTrGap = 0; // in minutes
    private boolean interpolateAll;
    private boolean enableSceneSync = false;
    private boolean supportsOffLightUpdates = false;
    private int expectedSceneUpdates;
    private int sceneSyncDelayInSeconds;
    private int sceneActivationIgnoreWindowInSeconds;

    private void setCurrentTimeToAndRun(ScheduledRunnable scheduledRunnable) {
        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();
    }

    private void setCurrentTimeTo(ScheduledRunnable scheduledRunnable) {
        setCurrentTimeTo(scheduledRunnable.getStart());
    }

    private void setCurrentTimeTo(ZonedDateTime newTime) {
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

    private void setCurrentAndInitialTimeTo(ZonedDateTime dateTime) {
        initialNow = now = dateTime;
        MDC.put("context", "test");
        MDC.put("time", TIME_FORMATTER.format(dateTime));
        log.info("Initial time: {} ({})", now, DayOfWeek.from(now));
    }

    private void setupTimeWithDayOfWeek(DayOfWeek dayOfWeek) {
        setupTimeWithDayOfWeek(dayOfWeek, LocalTime.MIDNIGHT);
    }

    private void setupTimeWithDayOfWeek(DayOfWeek dayOfWeek, LocalTime time) {
        setCurrentAndInitialTimeTo(now.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time));
    }

    private void advanceCurrentTime(Duration duration) {
        setCurrentTimeTo(now.plus(duration));
    }

    private void create() {
        scheduler = new HueScheduler(mockedHueApi, stateScheduler, startTimeProvider,
                () -> now, 10.0, controlGroupLightsIndividually, disableUserModificationTracking,
                requireSceneActivation, defaultInterpolationTransitionTimeInMs, 0, connectionFailureRetryDelay,
                minTrGap, BRIGHTNESS_OVERRIDE_THRESHOLD_PERCENT, COLOR_TEMPERATURE_OVERRIDE_THRESHOLD_KELVIN,
                COLOR_OVERRIDE_THRESHOLD, 3.8, 150, 3.0,
                sceneActivationIgnoreWindowInSeconds, interpolateAll,
                enableSceneSync, sceneSyncName, syncFailureRetryInMinutes, sceneSyncDelayInSeconds,
                supportsOffLightUpdates);
        manualOverrideTracker = scheduler.getManualOverrideTracker();
    }

    private void addDefaultState(int id, ZonedDateTime startTime) {
        addState(id, startTime, DEFAULT_BRIGHTNESS, DEFAULT_CT);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct) {
        addState(id, startTime, brightness, ct, null);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct, Boolean on) {
        addKnownLightIdsWithDefaultCapabilities(id);
        addState(id, startTime, "bri:" + brightness, "ct:" + ct, "on:" + on);
    }

    private void addDefaultGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        mockGroupLightsForId(groupId, lights);
        mockDefaultGroupCapabilities(groupId);
        addState("g" + groupId, start, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
    }

    private void addStateNow(Object id, String... properties) {
        addState(id, now, properties);
    }

    private void addState(Object id, ZonedDateTime startTime, String... properties) {
        addState(id, startTime.toLocalTime().toString(), properties);
    }

    private void addState(Object id, String startTimeString, String... properties) {
        String nonNullProperties = Arrays.stream(properties)
                                         .filter(p -> !p.contains("null"))
                                         .collect(Collectors.joining("\t"));
        addState(id + "\t" + startTimeString + "\t" + nonNullProperties);
    }

    private void addState(String input) {
        scheduler.addState(input);
    }

    private void startScheduler() {
        scheduler.start();
    }

    private List<ScheduledRunnable> startScheduler(int expectedStates) {
        startScheduler();

        return ensureScheduledStates(expectedStates);
    }

    private List<ScheduledRunnable> startScheduler(ExpectedRunnable... expectedRunnables) {
        startScheduler();

        return ensureScheduledStates(expectedRunnables);
    }

    private List<ScheduledRunnable> ensureScheduledStates(ExpectedRunnable... expectedRunnables) {
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(expectedRunnables.length);
        for (int i = 0; i < scheduledRunnables.size(); i++) {
            ExpectedRunnable expectedRunnable = expectedRunnables[i];
            assertScheduleStart(scheduledRunnables.get(i), expectedRunnable.start, expectedRunnable.end);
        }
        return scheduledRunnables;
    }

    private List<ScheduledRunnable> ensureScheduledStates(int expectedSize) {
        List<ScheduledRunnable> scheduledRunnables = stateScheduler.getScheduledStates();
        assertThat(scheduledRunnables).hasSize(expectedSize);
        stateScheduler.clear();
        return scheduledRunnables;
    }

    private void mockLightIdForName(String name, int id) {
        when(mockedHueApi.getLightIdentifierByName(name)).thenReturn(new Identifier("/lights/" + id, name));
    }

    private void mockGroupLightsForId(int groupId, Integer... lights) {
        List<String> lightIds = Arrays.stream(lights)
                                      .map(String::valueOf)
                                      .map(id -> "/lights/" + id)
                                      .toList();
        when(mockedHueApi.getGroupLights("/groups/" + groupId)).thenReturn(lightIds);
        for (Integer light : lights) {
            mockAssignedGroups(light, List.of(groupId));
        }
    }

    private void mockGroupIdForName(String name, int id) {
        when(mockedHueApi.getGroupIdentifierByName(name)).thenReturn(new Identifier("/groups/" + id, name));
    }

    private void addDefaultState() {
        addState(ID, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
    }

    private void addNullState(ZonedDateTime start) {
        addState(ID, start, null, null);
    }

    private void addOffState() {
        addState(1, now, null, null, false);
    }

    private void runAndAssertNextDay(ScheduledRunnable state) {
        advanceTimeAndRunAndAssertPutCalls(state, defaultPutCall());

        ensureNextDayRunnable();
    }

    private void advanceTimeAndRunAndAssertPutCalls(ScheduledRunnable scheduledRunnable, PutCall.PutCallBuilder... putCallBuilders) {
        setCurrentTimeTo(scheduledRunnable);

        runAndAssertPutCalls(scheduledRunnable, putCallBuilders);
    }

    private void runAndAssertPutCalls(ScheduledRunnable state, PutCall.PutCallBuilder... expectedPutCallBuilders) {
        state.run();

        assertPutCalls(expectedPutCallBuilders);
    }

    private void assertPutCalls(PutCall.PutCallBuilder... putCallBuilders) {
        for (PutCall.PutCallBuilder putCallBuilder : putCallBuilders) {
            assertPutCall(putCallBuilder.build());
        }
        assertAllPutCallsAsserted();
    }

    private void assertPutCall(PutCall putCall) {
        expectedPutCalls++;
        orderVerifier.verify(mockedHueApi, calls(1)).putState(putCall);
    }

    private void setLightStateResponse(int id, LightState.LightStateBuilder lightStateBuilder) {
        setLightStateResponse("/lights/" + id, lightStateBuilder);
    }

    private void setLightStateResponse(String id, LightState.LightStateBuilder lightStateBuilder) {
        when(mockedHueApi.getLightState(id)).thenReturn(lightStateBuilder.build());
    }

    private void setLightStateResponse(int id, boolean on, String effect) {
        LightState.LightStateBuilder lightStateBuilder = LightState.builder()
                                                                   .brightness(DEFAULT_BRIGHTNESS)
                                                                   .colorTemperature(DEFAULT_CT)
                                                                   .effect(effect)
                                                                   .on(on)
                                                                   .lightCapabilities(defaultCapabilities);
        setLightStateResponse(id, lightStateBuilder);
    }

    private void setGroupStateResponses(int id, LightState.LightStateBuilder... lightStatesBuilder) {
        LightState[] lightStates = Arrays.stream(lightStatesBuilder)
                                         .map(LightState.LightStateBuilder::build)
                                         .toArray(LightState[]::new);
        when(mockedHueApi.getGroupStates("/groups/" + id)).thenReturn(Arrays.asList(lightStates));
    }

    private ScheduledRunnable ensureNextDayRunnable() {
        return ensureNextDayRunnable(initialNow);
    }

    private ScheduledRunnable ensureNextDayRunnable(ZonedDateTime now) {
        return ensureRunnable(now.plusDays(1), now.plusDays(2));
    }

    private ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart, ZonedDateTime endExclusive) {
        ScheduledRunnable state = ensureRunnable(scheduleStart);
        assertEnd(state, endExclusive);
        return state;
    }

    private ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart) {
        ScheduledRunnable state = ensureScheduledStates(1).getFirst();
        assertScheduleStart(state, scheduleStart);
        return state;
    }

    private ScheduledRunnable ensureConnectionFailureRetryState() {
        return ensureRunnable(now.plusSeconds(connectionFailureRetryDelay));
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start, ZonedDateTime endExclusive) {
        assertScheduleStart(state, start);
        assertEnd(state, endExclusive);
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

    private void addKnownLightIdsWithDefaultCapabilities(Integer... ids) {
        Arrays.stream(ids).forEach(this::mockDefaultLightCapabilities);
    }

    private void mockLightCapabilities(int id, LightCapabilities.LightCapabilitiesBuilder capabilitiesBuilder) {
        mockLightCapabilities("/lights/" + id, capabilitiesBuilder.build());
    }

    private void mockLightCapabilities(String id, LightCapabilities capabilities) {
        String name = "test light " + id.substring(id.lastIndexOf('/') + 1);
        when(mockedHueApi.getLightIdentifier(id)).thenReturn(new Identifier(id, name));
        when(mockedHueApi.getLightCapabilities(id)).thenReturn(capabilities);
    }

    private void mockDefaultLightCapabilities(int id) {
        mockLightCapabilities("/lights/" + id, defaultCapabilities);
    }

    private void mockDefaultGroupCapabilities(int id) {
        mockGroupCapabilities(id, defaultCapabilities);
    }

    private void mockGroupCapabilities(int id, LightCapabilities.LightCapabilitiesBuilder capabilitiesBuilder) {
        mockGroupCapabilities(id, capabilitiesBuilder.build());
    }

    private void mockGroupCapabilities(int id, LightCapabilities capabilities) {
        String groupId = "/groups/" + id;
        when(mockedHueApi.getGroupIdentifier(groupId)).thenReturn(new Identifier(groupId, "test group " + id));
        when(mockedHueApi.getGroupCapabilities(groupId)).thenReturn(capabilities);
    }

    private void mockAssignedGroups(int lightId, Integer... groups) {
        mockAssignedGroups(lightId, Arrays.asList(groups));
    }

    private void mockAssignedGroups(int lightId, List<Integer> groups) {
        List<String> groupIds = groups.stream()
                                      .map(String::valueOf)
                                      .map(id -> "/groups/" + id)
                                      .toList();
        lenient().when(mockedHueApi.getAssignedGroups("/lights/" + lightId)).thenReturn(groupIds);
    }

    private ScheduledRunnable startWithDefaultState() {
        addDefaultState();
        startScheduler();

        return ensureScheduledStates(1).getFirst();
    }

    private ScheduledRunnable startAndGetSingleRunnable(ZonedDateTime scheduledStart, ZonedDateTime endExclusive) {
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(scheduledStart);
        assertEnd(scheduledRunnable, endExclusive);
        return scheduledRunnable;
    }

    private ScheduledRunnable startAndGetSingleRunnable(ZonedDateTime scheduledStart) {
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, scheduledStart);
        return scheduledRunnable;
    }

    private ScheduledRunnable startAndGetSingleRunnable() {
        startScheduler();

        return ensureScheduledStates(1).getFirst();
    }

    private List<ScheduledRunnable> simulateLightOnEvent(ExpectedRunnable... expectedRunnables) {
        return simulateLightOnEvent("/lights/" + ID, expectedRunnables);
    }

    private List<ScheduledRunnable> simulateLightOnEvent(String id, ExpectedRunnable... expectedRunnables) {
        simulateLightOnEvent(id);
        return ensureScheduledStates(expectedRunnables);
    }

    private void simulateLightOnEvent(String id) {
        scheduler.getHueEventListener().onLightOn(id);
    }

    private void simulateLightOffEvent(String id) {
        scheduler.getHueEventListener().onLightOff(id);
    }

    private ScheduledRunnable simulateLightOnEventExpectingSingleScheduledState() {
        simulateLightOnEvent("/lights/" + ID);
        return ensureScheduledStates(1).getFirst();
    }

    private ScheduledRunnable simulateLightOnEventExpectingSingleScheduledState(ZonedDateTime endExclusive) {
        return simulateLightOnEvent(expectedPowerOnEnd(endExclusive)).getFirst();
    }

    private void mockPutStateThrowable(Throwable throwable) {
        doThrow(throwable).when(mockedHueApi).putState(any());
    }

    private void enableUserModificationTracking() {
        disableUserModificationTracking = false;
        create();
    }

    private void enableSupportForOffLightUpdates() {
        supportsOffLightUpdates = true;
        create();
    }

    private void enableSceneSync() {
        enableSceneSync = true;
        create();
    }

    private void requireSceneActivation() {
        requireSceneActivation = true;
        create();
    }

    private void resetMockedApi() {
        Mockito.reset(mockedHueApi);
        expectedPutCalls = 0;
        expectedGroupPutCalls = 0;
        expectedScenePutCalls = 0;
        expectedSceneUpdates = 0;
        orderVerifier = inOrder(mockedHueApi);
        sceneSyncOrderVerifier = inOrder(mockedHueApi);
    }

    private void assertAllPutCallsAsserted() {
        verify(mockedHueApi, times(expectedPutCalls)).putState(any());
    }

    private void assertAllScenePutCallsAsserted() {
        verify(mockedHueApi, times(expectedScenePutCalls)).putSceneState(any(), anyList());
    }

    private void assertAllGroupPutCallsAsserted() {
        verify(mockedHueApi, times(expectedGroupPutCalls)).putGroupState(any(PutCall.class));
    }

    private void assertAllSceneUpdatesAsserted() {
        verify(mockedHueApi, times(expectedSceneUpdates)).createOrUpdateScene(any(), any(), any());
    }

    private int tr(String tr) {
        return InputConfigurationParser.parseTransitionTime("tr", tr);
    }

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
                                               .effects(List.of("effect"))
                                               .gradientModes(List.of("GRADINET_MODE_1", "GRADIENT_MODE_2"))
                                               .maxGradientPoints(5)
                                               .capabilities(EnumSet.allOf(Capability.class)).build();
        controlGroupLightsIndividually = false;
        disableUserModificationTracking = true;
        requireSceneActivation = false;
        defaultInterpolationTransitionTimeInMs = null;
        interpolateAll = false;
        sceneSyncDelayInSeconds = 0;
        sceneActivationIgnoreWindowInSeconds = 5;
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

    @Test
    void run_groupState_looksUpContainingLights_addsState() {
        addDefaultGroupState(9, now, 1, 2, 3);

        startAndGetSingleRunnable(now);
    }

    @Test
    void run_groupState_andLightState_sameId_treatedDifferently_endIsCalculatedIndependently() {
        addDefaultGroupState(1, now, 1);
        addDefaultState(1, now);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1))
        );

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(), defaultGroupPutCall());

        ensureNextDayRunnable();
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_becauseOfDayWrapAround() {
        addDefaultState(1, now.plusHours(1));

        ScheduledRunnable runnable = startAndGetSingleRunnable(now, now.plusHours(1));

        advanceTimeAndRunAndAssertPutCalls(runnable, defaultPutCall());

        ensureRunnable(now.plusHours(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_theNextWithCorrectDelay() {
        addDefaultState(1, now.plusHours(1));
        addDefaultState(1, now.plusHours(2));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(), defaultPutCall());

        ensureRunnable(now.plusHours(2), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        setCurrentTimeTo(now.plusHours(2));
        addDefaultState(11, now.minusHours(1));

        startAndGetSingleRunnable(now, initialNow.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_sameId_differentTimes_correctlyScheduled() {
        addDefaultState(22, now);
        addDefaultState(22, now.plusHours(1));
        addDefaultState(22, now.plusHours(2));

        startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        setCurrentTimeTo(now.plusHours(3)); // 03:00
        addDefaultState(13, initialNow.plusHours(4));  // 04:00 -> scheduled in one hour
        addDefaultState(13, initialNow.plusHours(2)); // 02:00 -> scheduled immediately
        addDefaultState(13, initialNow.plusHours(1)); // 01:00 -> scheduled next day

        startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), initialNow.plusDays(1).plusHours(1)),
                expectedRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2))
        );
    }

    @Test
    void parse_unknownLightId_exception() {
        when(mockedHueApi.getLightIdentifier("/lights/1")).thenThrow(new LightNotFoundException("Light not found"));

        assertThrows(LightNotFoundException.class, () -> addStateNow("1"));
    }

    @Test
    void parse_unknownGroupId_exception() {
        when(mockedHueApi.getGroupIdentifier("/groups/1")).thenThrow(new GroupNotFoundException("Group not found"));

        assertThrows(GroupNotFoundException.class, () -> addStateNow("g1"));
    }

    @Test
    void parse_group_brightness_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, NO_CAPABILITIES);

        assertThrows(BrightnessNotSupported.class, () -> addStateNow("g7", "bri:254"));
    }

    @Test
    void parse_group_colorTemperature_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, NO_CAPABILITIES);

        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("g7", "ct:500"));
    }

    @Test
    void parse_group_color_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, NO_CAPABILITIES);

        assertThrows(ColorNotSupported.class, () -> addStateNow("g7", "x:1", "y:1"));
    }

    @Test
    void parse_parsesInputLine_createsMultipleStates_canHandleGroups() {
        int groupId = 9;
        mockGroupLightsForId(groupId, 77);
        addKnownLightIdsWithDefaultCapabilities(1, 2);
        mockDefaultGroupCapabilities(groupId);
        addStateNow("1, 2,g" + groupId, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1))
        );

        runAndAssertNextDay(scheduledRunnables.get(1));
    }

    @Test
    void parse_alsoSupportsFourSpacesInsteadOfTabs() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1    " + nowTimeString + "    bri:" + DEFAULT_BRIGHTNESS + "    ct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_multipleTabs_parsedCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1\t12:00\t\tbri:75%\t\tct:3400");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_missingPart_throws() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThrows(InvalidConfigurationLine.class, () -> addState("1  12:00  bri"));
    }

    @Test
    void parse_trimsProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1\t" + nowTimeString + "\t bri:" + DEFAULT_BRIGHTNESS + "  \tct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_x_canHandleSpaces() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "x: 0.1234 ", "y:0.5678 ");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_missingParts_atLeastIdAndTimeNeedsToBeSet() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidConfigurationLine.class, () -> addState("1\t"));
    }

    @Test
    void parse_unknownFlag_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(UnknownStateProperty.class, () -> addStateNow("1", "UNKNOWN:1"));
    }

    @Test
    void parse_missingTime_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidStartTimeExpression.class, () -> {
            addState("1\tct:" + DEFAULT_CT);
            startScheduler();
        });
    }

    @Test
    void parse_setsTransitionTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:" + 5);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).ct(null).transitionTime(5)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_hassEntityId_light_correctlyParsed() {
        mockLightCapabilities("light.test", defaultCapabilities);
        addStateNow("light.test", "bri:" + DEFAULT_BRIGHTNESS);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall("light.test").bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1));
        verify(mockedHueApi).getLightIdentifier("light.test");
    }

    @Test
    void parse_hassEntityId_detectsPowerOffAndOn() {
        mockLightCapabilities("light.test", defaultCapabilities);
        addStateNow("light.test", "bri:" + DEFAULT_BRIGHTNESS);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        mockIsLightOff("light.test", true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable); // no put calls, as light off

        ensureRunnable(initialNow.plusDays(1)); // next day

        mockIsLightOff("light.test", false);

        // simulate power on

        List<ScheduledRunnable> powerOnRunnable = simulateLightOnEvent("light.test",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable.getFirst(),
                expectedPutCall("light.test").bri(DEFAULT_BRIGHTNESS)
        );
    }

    @Test
    void parse_hassEntityId_detectsManualOverrides_brightness() {
        enableUserModificationTracking();
        mockLightCapabilities("light.test", defaultCapabilities);
        addState("light.test", "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState("light.test", "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall("light.test").bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setLightStateResponse("light.test", expectedState().brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_hassEntityId_detectsManualOverrides_ct() {
        enableUserModificationTracking();
        mockLightCapabilities("light.test", defaultCapabilities);
        addState("light.test", "00:00", "ct:" + DEFAULT_CT);
        addState("light.test", "01:00", "ct:" + (DEFAULT_CT + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall("light.test").ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setLightStateResponse("light.test", expectedState().colormode(ColorMode.CT).colorTemperature(DEFAULT_CT + 65)); // overridden
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:5s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(50)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_minutes() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:100min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(60000)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_hours() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:1h");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(36000)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_hoursAndMinutesAndSeconds() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:1H20min5s10");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(48060)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTimeBefore_withSunTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:10");
        addState("1", "civil_dusk", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:sunset+10"); // tr-before:16:24:29
        ZonedDateTime sunset = startTimeProvider.getStart("sunset", now); // 16:14:29
        ZonedDateTime civilDusk = startTimeProvider.getStart("civil_dusk", now); // 16:47:46
        ZonedDateTime nextDaySunset = startTimeProvider.getStart("sunset", now.plusDays(1));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, sunset.plusMinutes(10)),
                expectedRunnable(sunset.plusMinutes(10), now.plusDays(1))
        );

        int expectedTransitionTime = (int) (Duration.between(sunset.plusMinutes(10), civilDusk).toMillis() / 100L);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(10), // interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(expectedTransitionTime)
        );

        ensureRunnable(nextDaySunset.plusMinutes(10), initialNow.plusDays(2));
    }

    @Test
    void parse_canParseTransitionTimeBefore_withAbsoluteTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "01:00", "bri:1");
        addState("1", "12:00", "bri:254", "tr-before:01:00");

        startScheduler(
                expectedRunnable(now, now.plusHours(1)), //from previous day
                expectedRunnable(now.plusHours(1), now.plusHours(1)) // zero length state
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_withAbsoluteTime_simple() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1"); // 00:00-10:00
        addState("1", "12:00", "bri:254", "tr-before:10:00"); // 10:00-12:00

        startScheduler(
                expectedRunnable(now, now.plusHours(10)),
                expectedRunnable(now.plusHours(10), now.plusDays(1))
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_withAbsoluteTime_afterStartTime_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1");
        addState("1", "12:00", "bri:254", "tr-before:13:00"); // ignores tr-before

        startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );
    }


    @Test
    void parse_canParseInterpolateProperty_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1", "interpolate:true");
        addState("1", "12:00", "bri:254", "interpolate:true");
        addState("1", "14:00", "bri:100", "interpolate:true");
        addState("1", "15:00", "bri:50", "interpolate:true");
        addState("1", "20:00", "bri:5", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)), // second state
                expectedRunnable(now.plusHours(12), now.plusHours(14)), // third state
                expectedRunnable(now.plusHours(14), now.plusHours(15)), // fourth state
                expectedRunnable(now.plusHours(15), now.plusHours(20)), // fifth state
                expectedRunnable(now.plusHours(20), now.plusDays(1)) // first state
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(100), // interpolated call
                expectedPutCall(1).bri(50).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void parse_interpolateProperty_andTrBefore_prefersTrBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1");
        addState("1", "12:00", "bri:254", "interpolate:true", "tr-before:1h"); // ignores interpolate:true

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(11)),
                expectedRunnable(now.plusHours(11), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(1), // interpolated call
                expectedPutCall(1).bri(254).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_defaultInterpolateSet_interpolatesAllStatesUnlessExplicitlyDisabled() {
        interpolateAll = true;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1"); // 12:00 (previous day) - 00:00
        addState("1", "01:00", "bri:254"); // 00:00-12:00
        addState("1", "12:00", "bri:100", "interpolate:false"); // zero length state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)), // second state
                expectedRunnable(now.plusHours(12), now.plusDays(1)), // cross over state
                expectedRunnable(now.plusHours(12), now.plusHours(12)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(1), // interpolated call
                expectedPutCall(1).bri(254).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusHours(12)); // next day
    }

    @Test
    void parse_canParseInterpolateProperty_singleState_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1", "interpolate:true");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_canParseInterpolateProperty_withSunTimes_correctlyUsesSunTimesFromDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime noon = startTimeProvider.getStart("noon", now);
        ZonedDateTime sunset = startTimeProvider.getStart("sunset", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        addState("1", "sunrise", "bri:1", "interpolate:true");
        addState("1", "noon", "bri:254", "interpolate:true");
        addState("1", "sunset", "bri:100", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, sunrise), // from previous day
                expectedRunnable(sunrise, noon),
                expectedRunnable(noon, sunset)
        );
        ScheduledRunnable previousDaySunriseState = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(previousDaySunriseState,
                expectedPutCall(1).bri(50), // interpolated call
                expectedPutCall(1).bri(47).transitionTime(tr("33min31s"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plus(1891000 + 120000, ChronoUnit.MILLIS), sunrise), // next split start
                expectedRunnable(sunset, nextDaySunrise)
        );
    }

    // todo: should we allow day crossovers also for absolute times in tr-before (see next test)? We need this for interpolate:true now anyways

    @Test
    void parse_canParseTransitionTimeBefore_negativeDuration_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime goldenHour = startTimeProvider.getStart("golden_hour", now);
        ZonedDateTime nextDayGoldenHour = startTimeProvider.getStart("golden_hour", now.plusDays(1));
        ZonedDateTime nextNextDayGoldenHour = startTimeProvider.getStart("golden_hour", now.plusDays(2));

        addState("1", "golden_hour", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:sunset"); // referenced t-before time is AFTER start

        ScheduledRunnable crossOverState = startAndGetSingleRunnable(now, goldenHour);

        // no transition time as state already reached
        advanceTimeAndRunAndAssertPutCalls(crossOverState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS));

        ScheduledRunnable trBeforeRunnable = ensureRunnable(goldenHour, nextDayGoldenHour);

        // ignored transition time, as negative
        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS));

        ensureRunnable(nextDayGoldenHour, nextNextDayGoldenHour);
    }

    @Test
    void parse_canParseTransitionTimeBefore_maxDuration_doesNotPerformAdditionalInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10");
        addState(1, now.plusHours(1).plusMinutes(50), "bri:200", "tr-before:" + MAX_TRANSITION_TIME);

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10))
        );
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(10),
                expectedPutCall(1).bri(200).transitionTime(MAX_TRANSITION_TIME));

        ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(10)); // next day
    }

    @Test
    void parse_canParseTransitionTimeBefore_longDuration_noPreviousState_doesNotAdjustStart_asNoInterpolationIsPossible() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now); // null state
        addState(1, now.plusHours(3).plusMinutes(40), "bri:200", "tr-before:210min");

        ScheduledRunnable trRunnable = startAndGetSingleRunnable(now.plusHours(3).plusMinutes(40), now.plusDays(1)); // no tr-before adjustment happened

        advanceTimeAndRunAndAssertPutCalls(trRunnable, expectedPutCall(1).bri(200));

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void parse_canParseTransitionTimeBefore_longDuration_splitsCallIntoMultipleParts_interpolatesPropertiesCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");
        // 210min = 3h30min -> split to: 100min + 100min + 10min
        // Start with 40 brightness, the target is 250.
        // After 100 minutes (first split) we should have 140, after 200 minutes (second split) 240, after the final split we reach 250

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(initialNow.plusMinutes(10), initialNow.plusDays(1)), // tr-runnable
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10))
        );
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();

        // first split

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(initialBrightness), // previous state call as interpolation start
                expectedPutCall(1).bri(initialBrightness + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // scheduled second split of transition
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable followUpRunnable = followUpRunnables.getFirst();

        // run follow-up call for the second split

        advanceTimeAndRunAndAssertPutCalls(followUpRunnable,
                // no interpolation as "initialBrightness + 100" already set at end of first part
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME) // second split of transition
        );

        ScheduledRunnable finalSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled third split of transition

        // simulate power-on, ten minutes later: adjusts calls from above

        advanceCurrentTime(Duration.ofMinutes(5));
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.minusMinutes(5)),
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS).minusMinutes(5))
        );

        powerOnRunnables.get(0).run(); // already ended -> no put calls
        // creates another power-on runnable but with adjusted end -> see "already ended" runnable for second power-on
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(initialBrightness + 105), // adjusted to five minutes after
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME - 3000)
        );

        ensureScheduledStates(0);

        // run final split call

        advanceTimeAndRunAndAssertPutCalls(finalSplit,
                // no interpolation as "initialBrightness + 200" already set at end of second part
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000) // remaining 10 minutes
        );
        // simulate second power on, five minutes later: adjusts calls from above

        List<ScheduledRunnable> finalPowerOns = simulateLightOnEvent(
                expectedPowerOnEnd(now),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );
        advanceCurrentTime(Duration.ofMinutes(5));

        finalPowerOns.get(0).run(); // already ended -> no put calls

        runAndAssertPutCalls(finalPowerOns.get(1),
                expectedPutCall(1).bri(initialBrightness + 205),
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(3000) // remaining five minutes
        );

        // move to defined start -> no tr-before transition time left

        advanceCurrentTime(Duration.ofMinutes(5)); // at defined start

        ScheduledRunnable normalPowerOn = simulateLightOnEventExpectingSingleScheduledState(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(normalPowerOn,
                expectedPutCall(1).bri(initialBrightness + 210) // no transition anymore
        );
    }

    @Test
    void parse_transitionTimeBefore_usingInterpolate_longTransition_correctSplitCalls() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(30), "bri:" + (initialBrightness + 210), "interpolate:true");
        // 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();

        assertScheduleStart(trRunnable, now, initialNow.plusDays(1));

        // first split

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(initialBrightness), // previous state call as interpolation start
                expectedPutCall(1).bri(initialBrightness + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // scheduled second split of transition
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable followUpRunnable = followUpRunnables.getFirst();

        // run follow-up call for the second split

        advanceTimeAndRunAndAssertPutCalls(followUpRunnable,
                // no interpolation as "initialBrightness + 100" already set at end of first part
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME) // second split of transition
        );

        ScheduledRunnable finalSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled third split of transition

        advanceTimeAndRunAndAssertPutCalls(finalSplit,
                // no interpolation as "initialBrightness + 200" already set at end of second part
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(tr("10min")) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_manuallyOverridden_stillCorrectlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(2), "bri:160", "tr-before:110min");
        // 110min = 1h50min -> split to: 100min + 10min

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start
        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start directly with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();
        assertScheduleStart(trRunnable, now, initialNow.plusDays(1));

        trRunnable.run(); // no interaction, as directly skipped

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // power-on event

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(
                now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)); // first split power on

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // previous state call as interpolation start
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        // no user modification since split call -> proceeds normally

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 100 - 2)); // same as split call

        advanceTimeAndRunAndAssertPutCalls(secondSplit,
                // no interpolation, as "DEFAULT_BRIGHTNESS + 100" already set before
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 110).transitionTime(tr("10min")) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsFirstSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min"); // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        setLightStateResponse(1, expectedState().brightness(initialBrightness + BRIGHTNESS_OVERRIDE_THRESHOLD)); // user modification
        setCurrentTimeToAndRun(trRunnable); // detects manual override

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // run second split; still overridden
        advanceTimeAndRunAndAssertPutCalls(secondSplit);

        ScheduledRunnable finalSplit = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)) // scheduled final split of transition
        ).getFirst();

        // power-on event, skips first split, as not relevant anymore

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.minus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)),  // already ended
                expectedPowerOnEnd(now), // already ended; power first split
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // already ended

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(2),
                expectedPutCall(1).bri(initialBrightness + 100), // end of first split, which was skipped
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME) // second split of transition
        );

        // second power on, right at the start of the gap

        advanceCurrentTime(Duration.ofMillis(MAX_TRANSITION_TIME_MS).minusMinutes(2));
        ScheduledRunnable powerOn = simulateLightOnEventExpectingSingleScheduledState(now.plusMinutes(2));

        advanceTimeAndRunAndAssertPutCalls(powerOn,
                expectedPutCall(1).bri(initialBrightness + 198),
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(tr("2min"))
        );

        // final split

        setLightStateResponse(1, expectedState().brightness(initialBrightness + 200)); // same as second split
        advanceTimeAndRunAndAssertPutCalls(finalSplit,
                // no interpolation, as "initialBrightness + 200" already set at end of second split
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(tr("10min"))// remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsSecondSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // run tr-runnable -> detects manual override

        setLightStateResponse(1, expectedState().brightness(initialBrightness + BRIGHTNESS_OVERRIDE_THRESHOLD)); // user modification
        setCurrentTimeToAndRun(trRunnable); // detects manual override

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // power-on event -> retries first split

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now), // first state, already ended
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)) // powerOn first split
        );

        powerOnRunnables.get(0).run(); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(initialBrightness), // previous state call as interpolation start
                expectedPutCall(1).bri(initialBrightness + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        // second split -> detects second manual override

        setLightStateResponse(1, expectedState().brightness(initialBrightness + 100 + BRIGHTNESS_OVERRIDE_THRESHOLD)); // second modification
        setCurrentTimeToAndRun(secondSplit); // detects manual override

        ScheduledRunnable finalSplit = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)) // scheduled final split of transition
        ).getFirst();

        // final split; still overridden. skip second split
        advanceTimeAndRunAndAssertPutCalls(finalSplit);

        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.minus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(now), // already ended; second split
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(1)); // already ended

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(2),
                expectedPutCall(1).bri(initialBrightness + 200), // end of the second part, which was skipped
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsFirstAndSecondSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        setCurrentTimeTo(trRunnable);
        setLightStateResponse(1, expectedState().brightness(initialBrightness + BRIGHTNESS_OVERRIDE_THRESHOLD)); // user modification
        trRunnable.run(); // detects manual override

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // second split -> still overridden
        advanceTimeAndRunAndAssertPutCalls(secondSplit);

        ScheduledRunnable finalSplit = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)) // next split
        ).getFirst();

        // final split -> still overridden
        advanceTimeAndRunAndAssertPutCalls(finalSplit);

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // initial state, already ended
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // first split power on, already ended
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS)), // second split power on, already ended
                expectedPowerOnEnd(initialNow.plusDays(1)) // final split power on
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(2)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(3),
                expectedPutCall(1).bri(initialBrightness + 200), // end of the second part, which was skipped
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_powerOnInGaps_correctlyHandled() {
        minTrGap = 2;
        int MAX_TRANSITION_TIME_WITH_BUFFER = MAX_TRANSITION_TIME - minTrGap * 600;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // Run tr-runnable

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(initialBrightness + 100 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER) // first split of transition
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // scheduled second split of transition
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // Power on, right at the start of the gap

        advanceCurrentTime(Duration.ofMillis(MAX_TRANSITION_TIME_MS).minusMinutes(minTrGap));
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // first state, already ended
                expectedPowerOnEnd(now.plusMinutes(minTrGap)) // first split again
        );

        powerOnRunnables.get(0).run(); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                // performs just the interpolation call
                expectedPutCall(1).bri(initialBrightness + 100 - 2)
        );

        // Power on, one minute in the gap

        advanceCurrentTime(Duration.ofMinutes(1));
        ScheduledRunnable inGapPowerRunnable = simulateLightOnEventExpectingSingleScheduledState(now.plusMinutes(minTrGap - 1));

        advanceTimeAndRunAndAssertPutCalls(inGapPowerRunnable,
                // performs just the interpolation call
                expectedPutCall(1).bri(initialBrightness + 100 - 1)
        );

        advanceTimeAndRunAndAssertPutCalls(secondSplit,
                expectedPutCall(1).bri(initialBrightness + 100),
                expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER) // first split of transition
        );

        ScheduledRunnable thirdSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1));

        // Advance to middle of second split, and cause power on -> does not over extend the transition, even though its less than the max transition length

        advanceCurrentTime(Duration.ofMillis(MAX_TRANSITION_TIME_MS / 2));

        List<ScheduledRunnable> powerOnRunnables2 = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended power on for first split
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS))
        );

        Duration untilThirdSplit = Duration.between(now, thirdSplit.getStart()).minusMinutes(minTrGap);
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables2.get(1),
                expectedPutCall(1).bri(initialBrightness + 150), // interpolated call
                expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime((int) (untilThirdSplit.toMillis() / 100)) // transition till start of third and final split (minus buffer)
        );

        // Third and final split

        advanceTimeAndRunAndAssertPutCalls(thirdSplit,
                expectedPutCall(1).bri(initialBrightness + 200),
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(tr("10min")) // remaining 10 minutes
        );
    }

    @Test
    void parse_transitionTimeBeforeBefore_longDuration_24Hours_zeroLengthStateBefore_performsInterpolationOverWholeDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:254", "tr-before:24h");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length state
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(1),
                expectedPutCall(1).bri(19).transitionTime(MAX_TRANSITION_TIME)
        );

        ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void parse_transitionTimeBefore_moreThan24Hours_25hours_overAdjusts_treatedAs23Hours_correctlyScheduled() { // todo: this is not officially supported, but there is no validation right now
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr-before:25h");

        startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1).minusHours(1)) // strange artifact
        );
    }

    @Test
    void parse_transitionTImeBefore_longDuration_lightIsConsideredOffForSplitCall_skipsFurtherSplitCallsUntilPowerOn() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:254", "tr-before:24h");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length state
        );
        ScheduledRunnable firstSplit = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(firstSplit,
                expectedPutCall(1).bri(1), // interpolated call
                expectedPutCall(1).bri(19).transitionTime(MAX_TRANSITION_TIME)
        );

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpStates.getFirst();

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(secondSplit); // no split call
        mockIsLightOff(1, false);

        // still scheduled next split call
        ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // power on event -> uses powerOn from second split

        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)) // power on second split
        );

        powerOnEvents.get(0).run(); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnEvents.get(1),
                expectedPutCall(1).bri(19), // end of first split
                expectedPutCall(1).bri(36).transitionTime(MAX_TRANSITION_TIME)
        );
    }

    @Test
    void parse_transitionTimeBefore_shiftsGivenStartByThatTime_afterPowerCycle_sameStateAgainWithTransitionTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // previous call for interpolation start
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // previous call for interpolation start
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("10min"))
        );
    }

    @Test
    void parse_transitionTimeBefore_allowsBackToBack_zeroLengthState_usedOnlyForInterpolation() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS); // 00:00, zero length
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min"); // 00:00, same start as initial
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 20)); // 10:00, zero length
        addState(1, "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 30), "interpolate:true"); // 10:00 same start as second zero length

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(10)), // 00:10 zero length state
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // 00:00 zero length state, scheduled next day
        );
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable zeroLengthStateAtTen = scheduledRunnables.get(1);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(2);

        // first tr-before runnable
        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // power on after 5 minutes -> correct interpolation

        advanceCurrentTime(Duration.ofMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(initialNow.plusMinutes(10));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 5),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        // run 00:10 zero length runnable -> no interaction

        advanceTimeAndRunAndAssertPutCalls(zeroLengthStateAtTen);

        ensureRunnable(now.plusDays(1), now.plusDays(1)); // next day, again zero length

        // second tr-before

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_viaInterpolate_usesTransitionFromPreviousState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "tr:2");
        addState(1, "00:30", "bri:" + (DEFAULT_BRIGHTNESS + 30), "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2));
    }

    @Test
    void parse_interpolate_effect_noBrightness_noInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "effect:effect");
        addState(1, "00:30", "bri:" + DEFAULT_BRIGHTNESS, "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).effect(Effect.builder().effect("effect").build())
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(30)); // next day
    }

    @Test
    void parse_interpolate_effect_withBrightness_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "effect:effect");
        addState(1, "00:30", "bri:100", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).effect(Effect.builder().effect("effect").build()),
                expectedPutCall(1).bri(100).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_performsInterpolationsAfterPowerCycles_usesTransitionFromPreviousState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr:1"); // this transition is used in interpolated call
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day

        ScheduledRunnable powerOnRunnable1 = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable1,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ScheduledRunnable powerOnRunnable2 = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable2,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );
    }

    @Test
    void parse_transitionTimeBefore_ifPreviousHasNotTransitionTime_usesDefault() {
        defaultInterpolationTransitionTimeInMs = "2"; // default value
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS); // no explicit transition time set
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(2),  // uses default
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_usesPreviousTransitionTime_zeroValue_correctlyReused() {
        defaultInterpolationTransitionTimeInMs = "2"; // default value
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr:0"); // reuses this value
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(0), // reused previous value
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_multipleTrBeforeAfterEachOther_repeatedNextDay_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min"); // 00:05 -> 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min"); // 00:10 -> 00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(1);

        // first tr-before, 00:00

        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10), // interpolated call from "previous" state from day before
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay1 = ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // second tr-before

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        // no interpolation as previous state = last seen state
        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay2 = ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(5)); // next day

        // repeat next day, same calls expected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));

        // no interpolation as previous state = last seen state
        advanceTimeAndRunAndAssertPutCalls(nextDay1,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay3 = ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));

        // no interpolation as previous state = last seen state
        advanceTimeAndRunAndAssertPutCalls(nextDay2,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay4 = ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(5)); // next day

        // repeat next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));

        advanceTimeAndRunAndAssertPutCalls(nextDay3,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // turn on again, 2 minutes after start

        advanceCurrentTime(Duration.ofMinutes(2));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(2)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(2).plusMinutes(5))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(4),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 6), // adjusted interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min"))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(nextDay4);
        advanceCurrentTime(Duration.ofMinutes(5)); // "turned on" at defined start, i.e., no interpolation and transition time expected

        runAndAssertPutCalls(nextDay4,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureRunnable(now.plusDays(1).minusMinutes(5), now.plusDays(2).minusMinutes(10)); // next day
    }

    @Test
    void parse_transitionTimeBefore_withDayOfWeek_backToBack_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min"); // only on TU and WE: 00:05 -> 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min", "days:MO,TU"); // 00:10 -> 00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)), // first state from day before
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                // no interpolated call, as second state is not scheduled on Sunday (the day before)
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // no tr since state from day before is already reached
        );

        ScheduledRunnable firstStateOnMonday = ensureRunnable(now.plusMinutes(5), now.plusMinutes(5)); // zero length

        // first state on monday -> zero length

        advanceTimeAndRunAndAssertPutCalls(firstStateOnMonday); // no put calls, as zero length

        ScheduledRunnable firstStateOnTuesday = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(5)); // next day (Tuesday), adjusted start

        ScheduledRunnable zeroLengthPowerOn = simulateLightOnEventExpectingSingleScheduledState(now);// power on but zero length state, no interaction
        advanceTimeAndRunAndAssertPutCalls(zeroLengthPowerOn);

        // second state on monday

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                // expectedPutCall(1).bri(DEFAULT_BRIGHTNESS),  -> no interpolation anymore since last turnedOn tracking change
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ScheduledRunnable secondStateOnTuesday = ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day (Tuesday)

        // [Tuesday] run next day, first state now has previous state and adjusts start

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnTuesday,
                // no interpolation, as "DEFAULT_BRIGHTNESS + 10" was already set before
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ScheduledRunnable firstStateOnWednesday = ensureRunnable(now.plusDays(1), now.plusDays(2).plusMinutes(5)); // next day (Wednesday), now full day, as there is no additional state
        // since there is no previous state for the first state on Thursday, the Wednesday state ends at 00:05 again, instead of 00:00

        // simulate light on event, which schedules the same state again -> but now with interpolations

        List<ScheduledRunnable> powerOnRunnables2 = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(now.plusMinutes(5))
        );

        // same state as firstStateOnTuesday
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables2.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        // second state

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(secondStateOnTuesday,
                // no interpolation, as "DEFAULT_BRIGHTNESS" was already set
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(7).plusMinutes(5), initialNow.plusDays(8)); // next week (Monday)

        // [Wednesday] run next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnWednesday,
                // no interpolation as "DEFAULT_BRIGHTNESS + 10" was already set
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ensureRunnable(now.plusDays(1).plusMinutes(5), now.plusDays(2).plusMinutes(5)); // next day (Thursday)
    }

    @Test
    void parse_transitionTimeBefore_withDayOfWeek_backToBack_onlyOnOneDay_twoDaysInTheFuture_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min", "days:WE"); // 00:05 -> never adjusted as no previous state, effectively zero length
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min", "days:MO,WE"); // 00:10 -> only on WE adjusted to 00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)), // second state today (Monday), no adjustment
                expectedRunnable(now.plusDays(2).plusMinutes(5), now.plusDays(2).plusMinutes(5)) // first state on Wednesday, zero length
        );
        ScheduledRunnable secondStateOnMonday = scheduledRunnables.get(0);
        ScheduledRunnable firstStateOnWednesday = scheduledRunnables.get(1);

        // second tr-before

        advanceTimeAndRunAndAssertPutCalls(secondStateOnMonday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no transition, as no previous state
        );

        ScheduledRunnable secondStateOnWednesday = ensureRunnable(initialNow.plusDays(2).plusMinutes(5), initialNow.plusDays(3)); // on Wednesday

        // move to Wednesday: first tr-before, zero length

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnWednesday); // no interaction as zero length

        ensureRunnable(now.plusDays(7), now.plusDays(7)); // next Wednesday, again zero length

        // second tr-before again -> adjusted transition time

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(secondStateOnWednesday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // interpolated call from zero length previous state
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(7).plusMinutes(10), initialNow.plusDays(8)); // next monday
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnMonday_todayIsMonday_crossesOverToPreviousDay_stillScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "days:SU");
        addState(1, "00:00", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:MO");

        startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(6), now.plusDays(7).minusMinutes(10))
        );
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnTuesday_todayIsMonday_crossesOverToPreviousDay_scheduledAtEndOfDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now, "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:TU");

        startScheduler(
                expectedRunnable(now, now.plusDays(1).minusMinutes(10)),
                expectedRunnable(now.plusDays(1).minusMinutes(10), now.plusDays(2))
        );
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnSunday_todayIsMonday_crossesOverToPreviousDay_scheduledNextWeek() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "days:SA");
        addState(1, "00:00", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:SU");

        startScheduler(
                expectedRunnable(now.plusDays(5), now.plusDays(6).minusMinutes(10)),
                expectedRunnable(now.plusDays(6).minusMinutes(10), now.plusDays(7))
        );
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_backToBack_onOneDayOfWeek_correctGapAdded() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min", "days:SA"); // 00:05 -> 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min"); // 00:10 -> adjusted to 00:05 ONLY on SA

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // day cross over of second state
                expectedRunnable(now.plusDays(5), now.plusDays(5).plusMinutes(5)) // state scheduled next Saturday
        );
        ScheduledRunnable dayCrossOverState = scheduledRunnables.get(0);
        ScheduledRunnable firstStateOnSaturday = scheduledRunnables.get(1);

        // cross over state (second tr-before runnable from Sunday)

        advanceTimeAndRunAndAssertPutCalls(dayCrossOverState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ScheduledRunnable secondStateOnMonday = ensureRunnable(now.plusMinutes(10), now.plusDays(1).plusMinutes(10)); // rescheduled second state

        // no interpolated call, as state is not scheduled on Sunday (the day before)
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(secondStateOnMonday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day

        // state on saturday with correct transition and end

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnSaturday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")) // gap added
        );

        ensureRunnable(now.plusDays(7), now.plusDays(7).plusMinutes(5));
    }

    @Test
    void parse_transitionTimeBefore_crossesDayLine_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:" + DEFAULT_CT, "tr-before:20min"); // 00:00 -> 23:50
        addState(1, "12:00", "ct:" + (DEFAULT_CT + 20));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1).minusMinutes(20))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).ct(DEFAULT_CT) // no interpolation, as state is already reached
        );

        ScheduledRunnable firstStateNextDay = ensureRunnable(initialNow.plusDays(1).minusMinutes(20), initialNow.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).ct(DEFAULT_CT + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2).minusMinutes(20));

        advanceTimeAndRunAndAssertPutCalls(firstStateNextDay,
                expectedPutCall(1).ct(DEFAULT_CT).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(2).minusMinutes(20), initialNow.plusDays(2).plusHours(12));
    }

    @Test
    void parse_dayCrossOver_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(20), "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1).minusMinutes(20));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ScheduledRunnable runnable = ensureRunnable(now.plusDays(1).minusMinutes(20), now.plusDays(2).minusMinutes(20));

        setCurrentTimeTo(now.plusDays(1).plusHours(1));
        runAndAssertPutCalls(runnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(2).minusMinutes(20), initialNow.plusDays(3).minusMinutes(20));
    }

    @Test
    void parse_transitionTimeBefore_overNight_doesNotOverAdjustTransitionTime_returnsNullInstead() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(50));
        addState(1, "23:50", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "23:55", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceCurrentTime(Duration.ofMinutes(10)); // here we cross over to tomorrow

        runAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no transition time expected
        );

        ensureNextDayRunnable(initialNow);
    }

    @Test
    void parse_transitionTimeBefore_longDuration_dayCrossOver_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2));
        ZonedDateTime sunset = startTimeProvider.getStart("sunset", now); // 16:14:29
        ZonedDateTime nextDaySunset = startTimeProvider.getStart("sunset", now.plusDays(1));
        addState(1, "sunset", "bri:10"); // 16:14:29
        addState(1, "sunrise", "bri:254", "tr-before:8h"); // sunrise - 8h = 23:42:13 (previous day)

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, sunset), // sunrise started yesterday
                expectedRunnable(sunset, nextDaySunrise.minusHours(8)) // sunset from today
        );
        ScheduledRunnable crossOverState = scheduledRunnables.get(0);
        ScheduledRunnable sunsetState = scheduledRunnables.get(1);

        setCurrentTimeTo(crossOverState);

        ZonedDateTime trBeforeStart = sunrise.minusHours(8);
        Duration initialStartOffset = Duration.between(trBeforeStart, now);
        long adjustedSplitDuration = MAX_TRANSITION_TIME * 100 - initialStartOffset.toMillis();
        long adjustedNextStart = MAX_TRANSITION_TIME_MS - initialStartOffset.toMillis();

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).bri(19), // interpolated call
                expectedPutCall(1).bri(61).transitionTime((int) (adjustedSplitDuration / 100)) // first split call
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(adjustedNextStart, ChronoUnit.MILLIS), sunset), // next split call
                expectedRunnable(nextDaySunrise.minusHours(8), nextDaySunset) // sunrise rescheduled for the next day
        );
        ScheduledRunnable firstSplitCall = followUpRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(firstSplitCall,
                expectedPutCall(1).bri(112).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable secondSplitCall = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        advanceTimeAndRunAndAssertPutCalls(secondSplitCall,
                expectedPutCall(1).bri(163).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable thirdSplitCall = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        advanceTimeAndRunAndAssertPutCalls(thirdSplitCall,
                expectedPutCall(1).bri(213).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable fourthSplitCall = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        setCurrentTimeTo(fourthSplitCall);
        Duration finalSplitDuration = Duration.between(now, sunrise);

        advanceTimeAndRunAndAssertPutCalls(fourthSplitCall,
                expectedPutCall(1).bri(254).transitionTime((int) (finalSplitDuration.toMillis() / 100L))
        );

        // power on event

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(firstSplitCall.getStart()), // already ended
                expectedPowerOnEnd(secondSplitCall.getStart()), // already ended
                expectedPowerOnEnd(thirdSplitCall.getStart()), // already ended
                expectedPowerOnEnd(fourthSplitCall.getStart()), // already ended
                expectedPowerOnEnd(sunset)
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(4),
                expectedPutCall(1).bri(213), // interpolated call
                expectedPutCall(1).bri(254).transitionTime((int) (finalSplitDuration.toMillis() / 100L))
        );

        // sunset state

        advanceTimeAndRunAndAssertPutCalls(sunsetState, expectedPutCall(1).bri(10));

        ensureRunnable(nextDaySunset, nextNextDaySunrise.minusHours(8));
    }

    @Test
    void parse_transitionTimeBefore_backToBack_alreadyEnoughGapInBetween_noAdjustmentsMade() {
        minTrGap = 2;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10"); // zero length
        addState(1, "01:00", "bri:100", "tr-before:1h"); // gap not relevant
        addState(1, "01:30", "bri:254", "tr-before:28min"); // gap of 2 minutes added

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1).plusMinutes(minTrGap)),
                expectedRunnable(now.plusHours(1).plusMinutes(minTrGap), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(1);

        // first tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(firstTrRunnable,
                expectedPutCall(1).bri(10), // interpolated call
                expectedPutCall(1).bri(100).transitionTime(36000) // no additional buffer used
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1).plusMinutes(minTrGap)); // next day

        // second tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(secondTrRunnable,
                expectedPutCall(1).bri(254).transitionTime(16800) // no additional buffer used
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_withModificationTracking_backToBack_smallTrBefore_lessThanTwoMinutes_adjustedTrToEnsureGap() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10"); // zero length
        addState(1, "00:02", "bri:100", "tr-before:2min"); // 00:00-00:02
        addState(1, "00:04", "bri:254", "tr-before:2min"); // 00:02-00:04
        addState(1, "00:05", "bri:100", "tr-before:1min"); // 00:04-00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(4)),
                expectedRunnable(now.plusMinutes(4), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(1);
        ScheduledRunnable thirdTrRunnable = scheduledRunnables.get(2);

        // first

        advanceTimeAndRunAndAssertPutCalls(firstTrRunnable,
                expectedPutCall(1).bri(10),  // interpolated call
                expectedPutCall(1).bri(100) // removed tr to ensure gap, does not use zero
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(2));

        // second

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(secondTrRunnable,
                expectedPutCall(1).bri(254) // removed tr to ensure gap, does not use zero
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(2));

        // third

        setLightStateResponse(1, expectedState().brightness(254));
        advanceTimeAndRunAndAssertPutCalls(thirdTrRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("1min"))
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTime_withModificationTracking_backToBack_ensuresGap() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "tr:2min"); // to small gap
        addState(1, "00:05", "bri:150", "tr:6min"); // too long tr, which would cause overlap
        addState(1, "00:10", "bri:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10) // removes tr
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        setLightStateResponse(1, expectedState().brightness(10));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("1min")) // ensures min gap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(5));

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(150).transitionTime(tr("3min")) // shortened tr and ensures gap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(10));
    }

    @Test
    void parse_transitionTime_withModificationTracking_backToBack_customGap_usesGap() {
        minTrGap = 5; // 5 minutes
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:7min");
        addState(1, "00:07", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(7)),
                expectedRunnable(now.plusMinutes(7), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")) // adjusts to custom gap
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(7));
    }

    @Test
    void parse_transitionTime_withUserModificationTracking_backToBack_stateHasForceProperty_doesNotAddGap() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "force:true", "tr:3min"); // would overlap
        addState(1, "00:04", "bri:100", "force:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(4)),
                expectedRunnable(now.plusMinutes(4), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")) // does not remove tr
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("2min")) // still adjusts tr to avoid overlap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(4));
    }

    @Test
    void parse_transitionTime_noUserModificationTracking_backToBack_doesNotAddGap_stillShortensIfOverlap() {
        minTrGap = 2;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "tr:4min"); // would overlap
        addState(1, "00:05", "bri:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")) // does not remove tr
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("3min")) // shortened tr to avoid overlap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(5));
    }

    @Test
    void parse_transitionTimeBefore_backToBack_noGapsInBetween_ignoresPreviousDay_adjustsTr() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100", "tr-before:1h"); // 23:00-00:00
        addState(1, "00:30", "bri:254", "tr-before:30min"); // 00:00-00:30

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)), // second state scheduled first as other already ended at 00:00
                expectedRunnable(now.plusDays(1).minusHours(1), now.plusDays(1))
        );
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(1);

        // second tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(secondTrRunnable,
                expectedPutCall(1).bri(100),
                expectedPutCall(1).bri(254).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2).minusHours(1)); // next day

        // first tr-before runnable, 23:00

        setLightStateResponse(1, expectedState().brightness(254));
        advanceTimeAndRunAndAssertPutCalls(firstTrRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("58min")) // gap added
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_longDuration_backToBack_noGapsInBetween_considersPreviousDay_adjustsTrBefore() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100", "tr-before:23h"); // 01:00, starts yesterday
        addState(1, "01:00", "bri:254", "tr-before:30min"); // 01:00 -> 00:30

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(1))
        );
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(1);

        // first tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                // no interpolation, as state already started
                expectedPutCall(1).bri(100)
        );

        ScheduledRunnable nextDayFirst = ensureRunnable(now.plusHours(1), initialNow.plusDays(1).plusMinutes(30));

        // second tr-before runnable

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                expectedPutCall(1).bri(254).transitionTime(tr("28min")) // gap added
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusHours(1));

        // next day first

        setLightStateResponse(1, expectedState().brightness(254));
        advanceTimeAndRunAndAssertPutCalls(nextDayFirst,
                expectedPutCall(1).bri(243).transitionTime(MAX_TRANSITION_TIME - minTrGap * 600) // first split call
        );

        ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1).plusMinutes(30)),
                expectedRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2).plusMinutes(30)) // next day
        );
    }

    @Test
    void parse_transitionTimeBefore_group_lightTurnedOnLater_stillBeforeStart_transitionTimeIsShortenedToRemainingTimeBefore() {
        mockGroupLightsForId(1, 5);
        addKnownLightIdsWithDefaultCapabilities(1);
        mockDefaultGroupCapabilities(1);
        addState("g1", "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceCurrentTime(Duration.ofMinutes(15));

        runAndAssertGroupPutCalls(scheduledRunnables.get(1),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 5), // interpolated call
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_lightTurnedAfterStart_trBeforeIgnored_normalTransitionTimeUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "tr:3s");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceCurrentTime(Duration.ofMinutes(20));

        runAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("3s"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_nullState_performsNoInterpolations_doesNotAdjustStart() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState(1, now.plusMinutes(2)); // null state, detected as previous state -> treated as no interpolation possible
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min"); // tr-before ignored as no previous state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(40), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(40), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_nullState_splitCall_performsNotInterpolation_doesNotAdjustStart() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState(1, "01:00"); // null state, picked as previous
        addState(1, "12:00", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10h"); // ignored, as no previous state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS - 10)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2)); // next day

        // simulate power on -> no power on for tr-before state scheduled

        simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_previousIsOnState_withBrightness_removedForInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "on:true", "bri:10");
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(10), // no "on" property
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_previousIsOnStateOnly_notInterpolationAtAll_noAdjustmentOfStart() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "on:true"); // just a single "on"
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(40)),
                expectedRunnable(now.plusMinutes(40), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(50).on(true) // full picture on initial startup
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(40)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                // no interpolation call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // no transition, as start was not adjusted
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(40), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOnState_alsoAddsOnPropertyToInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:10"); // no explicit "on"
        addState(1, now.plusMinutes(40), "on:true", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(10).on(true), // added "on" property
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).on(true).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOffState_doesNotAddOffPropertyToInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:10"); // no explicit "off"
        addState(1, now.plusMinutes(40), "on:false", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(10), // no "off" added
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).on(false).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOffState_interpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10");
        addState(1, now.plusMinutes(10), "on:false", "interpolate:true", "force:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10),
                expectedPutCall(1).on(false).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // turn lights on after 5 minutes

        advanceCurrentTime(Duration.ofMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)) // only until defined start
        ).getFirst();

        powerOnRunnable.run();

        assertPutCalls(
                expectedPutCall(1).bri(5), // interpolated
                expectedPutCall(1).on(false).transitionTime(tr("5min"))
        );
    }

    @Test
    void parse_interpolate_offState_treatedAsZeroBrightness_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "ct:200");
        addState(1, now.plusHours(2), "on:false", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).ct(200),
                expectedPutCall(1).bri(17).ct(200).transitionTime(MAX_TRANSITION_TIME) // repeats unchanged ct, no "on:false"
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).on(false).transitionTime(tr("20min"))
        );
    }

    @Test
    void parse_interpolate_offState_withAdditionalCTProperty_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "ct:200");
        addState(1, now.plusHours(2), "on:false", "ct:500", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).ct(200),
                expectedPutCall(1).bri(17).ct(450).transitionTime(MAX_TRANSITION_TIME)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).on(false).ct(500).transitionTime(tr("20min"))
        );
    }

    @Test
    void parse_offState_noTransition_noFullPictureUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false");
        addState(1, now.plusMinutes(10), "bri:100", "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).on(false)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void parse_offState_zeroTransition_noFullPictureUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "tr:0");
        addState(1, now.plusMinutes(10), "bri:100", "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).on(false).transitionTime(0)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    // todo: what about on:false but with no previous property; can we still interpolate? Do we need to fetch the current state of the light? Or should we just ignore it?

    @Test
    void parse_interpolate_noCTAtTarget_stillIncludedInSplitCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10", "ct:200");
        addState(1, now.plusHours(2), "bri:100", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10).ct(200),
                expectedPutCall(1).bri(85).ct(200).transitionTime(MAX_TRANSITION_TIME) // still include unchanged; don't perform optimzations
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnExactlyAtTrBeforeStart_performsNoInterpolation_setsExactPreviousStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_noInterpolationWhenPreviousStatesSetCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");
        addState(1, now.plusMinutes(60), "bri:" + (DEFAULT_BRIGHTNESS + 30), "ct:" + (DEFAULT_CT + 30), "tr-before:10min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(1);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(2);

        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT), // sets previous state again
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(1).plusMinutes(50)); // next day

        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                // does not set previous state again
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT + 30).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(50), initialNow.plusDays(2)); // next day

        // after power on -> perform interpolation again

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(50)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT + 30).transitionTime(tr("10min"))
        );
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfter_performsCorrectInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:400");
        addState(1, now.plusHours(3), "ct:200", "tr-before:30min");

        setCurrentTimeTo(now.plusHours(2).plusMinutes(45)); // 15 minutes after start

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(300),  // correct interpolated ct value
                expectedPutCall(1).ct(200).transitionTime(tr("15min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3).minusMinutes(30), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnExactlyAtDefinedStart_noAdditionalPutCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(40)); // at defined start

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfterStart_performsInterpolation_andMakesAdditionalPut() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 10),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_groupStates_lightTurnedOnAfterStart_performsInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 7, 8, 9);
        mockGroupCapabilities(5,
                LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.COLOR_TEMPERATURE)));
        addState("g5", now, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState("g5", now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30)); // 10 minutes after start

        runAndAssertGroupPutCalls(trBeforeRunnable,
                expectedGroupPutCall(5).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 10),
                expectedGroupPutCall(5).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfterStart_correctRounding() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(32).plusSeconds(18));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 12).ct(DEFAULT_CT + 12),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(4620)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_noCtAtTarget_onlyInterpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_interpolate_rgb_xy() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "color:#558af3");
        addState(1, now.plusMinutes(40), "x:0.2108", "y:0.2496", "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(229).x(0.2021).y(0.2142),
                expectedPutCall(1).x(0.2108).y(0.2496).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_longDuration_multipleStates_multipleProperty_previousStateHasNoBrightness_noLongTransition_noStartAdjustment() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:166", "x:0.4", "y:0.5", "tr:1"); // 00:00-12:00
        addState(1, "12:00", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:00:00"); // 12:00 -> no overlapping properties, not start adjustment

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceCurrentTime(Duration.ofMinutes(30));

        runAndAssertPutCalls(firstState,
                expectedPutCall(1).bri(50).ct(166).x(0.4).y(0.5).transitionTime(1) // full picture on intial startup
        );

        ScheduledRunnable firstStateNextDay = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(secondState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2));

        advanceTimeAndRunAndAssertPutCalls(firstStateNextDay,
                expectedPutCall(1).ct(166).x(0.4).y(0.5).transitionTime(1)
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(12));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_noCtAtPrevious_onlyInterpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 20), // full picture used
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_xAndY_performsInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "x:0.2", "y:0.2");
        addState(1, now.plusMinutes(40), "x:0.4", "y:0.4", "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).x(0.2603).y(0.4027),
                expectedPutCall(1).x(0.4).y(0.4).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_fromTheSameDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(35), "ct:" + (DEFAULT_CT - 10));
        addState(1, now, "ct:" + DEFAULT_CT); // should be picked as previous state
        addState(1, now.plusMinutes(40), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);
        assertScheduleStart(trBeforeRunnable, now.plusMinutes(20), now.plusDays(1).minusMinutes(35));

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(DEFAULT_CT + 10),
                expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(6000)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2).minusMinutes(35));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_fromTheDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(35), "ct:" + (DEFAULT_CT - 15));
        addState(1, now.minusMinutes(10), "ct:" + (DEFAULT_CT - 10), "days:DO");
        addState(1, now.minusMinutes(5), "ct:" + DEFAULT_CT); // this should be picked as previous state
        addState(1, now.plusMinutes(40), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(4);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);
        assertScheduleStart(trBeforeRunnable, now.plusMinutes(20), now.plusDays(1).minusMinutes(35));

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(DEFAULT_CT + 10),
                expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(6000)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2).minusMinutes(35));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_currentStateStartedTheDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(50), "ct:" + (DEFAULT_CT - 15)); // 23:10
        addState(1, now.minusMinutes(40), "ct:" + DEFAULT_CT); // 23:20, should be picked as previous state
        addState(1, now, "ct:" + (DEFAULT_CT + 20), "tr-before:20min"); // 23:40
        setCurrentAndInitialTimeTo(now.minusMinutes(10)); // 23:50

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusMinutes(40)),
                expectedRunnable(now.plusDays(1).minusMinutes(40), now.plusDays(1).minusMinutes(30)),
                expectedRunnable(now.plusDays(1).minusMinutes(30), now.plusDays(1).minusMinutes(10))
        );
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(DEFAULT_CT + 10),
                expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).minusMinutes(10), initialNow.plusDays(2).minusMinutes(40));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_usesFullPictureForInterpolation_alsoOnInitialStartup() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "ct:200");
        addState(1, now.plusMinutes(10), "bri:200", "tr-before:5min"); // tr-before ignored, since no overlapping properties
        addState(1, now.plusMinutes(30), "ct:250", "tr-before:10min"); // uses ct from first state for interpolation

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        // first state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(200).ct(200) // full picture on initial startup
        );

        ScheduledRunnable firstNextDay = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // power on: uses full picture

        List<ScheduledRunnable> firstPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.plusMinutes(10))
        );

        advanceTimeAndRunAndAssertPutCalls(firstPowerOnRunnables.getFirst(),
                expectedPutCall(1).bri(200).ct(200)
        );

        // second state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(200) // no full picture anymore
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(1).plusMinutes(20)); // next day

        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)),
                expectedPowerOnEnd(initialNow.plusMinutes(20))
        );

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(1),
                expectedPutCall(1).ct(200).bri(200)
        );

        // third state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).ct(250).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2)); // next day

        // power-on directly at start: uses previous state

        List<ScheduledRunnable> thirdPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(20)),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(thirdPowerOnRunnables.get(1),
                expectedPutCall(1).ct(200).bri(200),
                expectedPutCall(1).ct(250).transitionTime(tr("10min"))
        );

        // power-on five minutes after: performs interpolation

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> fourthPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(fourthPowerOnRunnables.getFirst(),
                expectedPutCall(1).ct(225).bri(200),
                expectedPutCall(1).ct(250).transitionTime(tr("5min"))
        );

        // first next day

        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).ct(200) // no full picture
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)); // next day
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnMonday_normallyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnMonday_startsLaterInDay_endsAtEndOfDay_noTemporaryState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Mo");

        startAndGetSingleRunnable(now.plusHours(12), now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnMondayAndTuesday_correctEndAtNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now.plusHours(12), now.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_multipleStates_twoOnMondayOnly_twoOnSundayAndMonday_usesCorrectOneFromSundayAsWraparound_correctEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "07:00", "ct:" + DEFAULT_CT, "days:Mo");
        addState(1, "09:00", "ct:" + DEFAULT_CT, "days:So,Mo");
        addState(1, "10:00", "ct:" + 200, "days:So,Mo"); // cross over state
        addState(1, "14:00", "ct:" + DEFAULT_CT, "days:Mo");
        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(7)),
                expectedRunnable(now.plusHours(7), now.plusHours(9)),
                expectedRunnable(now.plusHours(9), now.plusHours(10)),
                expectedRunnable(now.plusHours(14), now.plusDays(1)) // ends at end of day
        );
        ScheduledRunnable crossOverState = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).ct(200)
        );

        ensureRunnable(now.plusHours(10), now.plusHours(14)); // rescheduled cross over state
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnMondayAndTuesday_anotherStateBeforeOnTuesday_mondayStateEndsAtStartOfTuesdayOnlyState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + 153, "days:Mo, Tu");
        addState(1, "11:00", "ct:" + 200, "days:Tu");

        startScheduler(
                expectedRunnable(now.plusHours(12), now.plusDays(1).plusHours(11)),
                expectedRunnable(now.plusDays(1).plusHours(11), now.plusDays(1).plusHours(12))
        );
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnTuesday_schedulesStateNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Tu");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(12), now.plusDays(2).with(LocalTime.MIDNIGHT));
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnlyOnMonday_schedulesStateInSixDays() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");

        startAndGetSingleRunnable(now.plusDays(6), now.plusDays(7).with(LocalTime.MIDNIGHT));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnTuesdayAndWednesday_startsDuringDay_scheduledInOneDay_correctEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "11:00", "ct:" + DEFAULT_CT, "days:Tu, We");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(11), now.plusDays(2).plusHours(11));
    }

    @Test
    void parse_weekdayScheduling_todayIsWednesday_stateOnTuesdayAndWednesday_scheduledNow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.WEDNESDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Tu, We");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsWednesday_stateOnTuesdayAndWednesday_startsDuringDay_wrapAroundFromTuesdayScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.WEDNESDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Tu, We");

        ScheduledRunnable crossOverState = startAndGetSingleRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(now.plusHours(12), now.plusDays(1)); // rescheduled first state
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnMondayAndWednesday_scheduledTomorrow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo, We");

        startAndGetSingleRunnable(now.plusDays(1), now.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnMondayAndWednesday_middleOfDay_scheduledTomorrow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Mo, We");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(12), now.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsSunday_stateOnThursdayAndFriday_scheduledNextThursday() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.SUNDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Th, Fr");

        startAndGetSingleRunnable(now.plusDays(4), now.plusDays(5));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayOnly_nextDaySchedulingIsOnWeekLater() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndTuesday_scheduledNormallyOnNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureNextDayRunnable();
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndWednesday_skipToTuesday_endDefinedAtDayEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,We");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceCurrentTime(Duration.ofDays(1));

        scheduledRunnable.run(); // already ended

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(3));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_multipleStates_firstOnMondayAndWednesday_secondOnWednesdayOnly_recalculatesEndCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,We");
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:We");
        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(2).plusHours(12), now.plusDays(3))
        );

        setCurrentTimeTo(initialNow.plusDays(1)); // Tuesday

        scheduledRunnables.getFirst().run(); // already ended, state is not scheduled on Tuesday

        ScheduledRunnable nextRunnable = ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(12));

        setCurrentTimeTo(initialNow.plusDays(2).plusHours(12)); // start of second state

        nextRunnable.run();  // already ended

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_multipleStates_nextStartIsCorrectlyCalculated() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Tu, We");
        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1).plusHours(12), now.plusDays(2).plusHours(12))
        );

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(11)); // Tuesday, AM

        scheduledRunnables.getFirst().run(); // already ended, state is not scheduled on Tuesday; schedule next week

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_invalidDayParameter_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow(1, "ct:" + DEFAULT_CT, "days:INVALID"));
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_twoLetterEnglish() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Tu,We,Th,Fr,Sa,Su");
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_threeLetterEnglish() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mon,Tue,Wen,Wed,Thu,Fri,Sat,Sun");
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_twoLetterGerman() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Di,Mi,Do,Fr,Sa,So");
    }

    @Test
    void parse_canHandleBrightnessInPercent_minValue() {
        assertAppliedBrightness("1%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_trims() {
        assertAppliedBrightness("1 %", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_zeroPercentage_specialHandling_alsoReturnsMinValue() {
        assertAppliedBrightness("0%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_halfPercentage_specialHandling_alsoReturnsMinValue() {
        assertAppliedBrightness("0.5%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_negativePercentage_specialHandling_alsoReturnsMinValue() {
        assertAppliedBrightness("-0.5%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_tooHigh_specialHandling_returnsMaxValue() {
        assertAppliedBrightness("100.5%", 254);
    }

    @Test
    void parse_canHandleBrightnessInPercent_alsoDoubleValues() {
        assertAppliedBrightness("1.5%", 2);
    }

    @Test
    void parse_canHandleBrightnessInPercent_alsoDoubleValues2() {
        assertAppliedBrightness("10.89%", 26);
    }

    @Test
    void parse_canHandleBrightnessInPercent_maxValue() {
        assertAppliedBrightness("100%", 254);
    }

    private void assertAppliedBrightness(String input, int expected) {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + input);

        ScheduledRunnable runnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).bri(expected)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_appliesGamutCorrection() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "x:0.8", "y:0.2");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).x(0.6915).y(0.3083)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_forGroups() {
        mockGroupLightsForId(1, ID);
        double x = 0.4;
        double y = 0.3;
        mockDefaultGroupCapabilities(1);
        addStateNow("g1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnable,
                expectedGroupPutCall(ID).x(x).y(y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 125;
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(bri).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 100;
        addStateNow("1", "bri:" + customBrightness, "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color: 94, 186, 125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 125;
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(bri).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 200;
        addStateNow("1", "bri:" + customBrightness, "color:94,186,125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_effect_unsupportedValue_exception_listsValidValues() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder()
                                                            .effects(List.of("effect1", "effect2"))
                                                            .build());

        assertThatThrownBy(() -> addStateNow("1", "effect:INVALID"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessage("Unsupported value for effect property: 'INVALID'. Supported effects: [effect1, effect2]");
    }

    @Test
    void parse_effect_lightDoesNotSupportEffects_exception() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder().build());

        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "effect:effect"));
    }

    @Test
    void parse_canHandleEffect_supportedEffect() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder()
                                                            .effects(List.of("colorloop"))
                                                            .build());
        addStateNow(1, "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).effect(Effect.builder().effect("colorloop").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_withParameters_ct() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "ct:350", "effect:effect");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).effect(Effect.builder()
                                                 .effect("effect")
                                                 .ct(350)
                                                 .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_withParameters_xy() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "x:0.3", "y:0.4", "effect:effect");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).effect(Effect.builder()
                                                 .effect("effect")
                                                 .x(0.3)
                                                 .y(0.4)
                                                 .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_effect_group_exception() {
        mockGroupLightsForId(1, 1);
        mockGroupCapabilities(1, LightCapabilities.builder()
                                                  .effects(List.of("colorloop"))
                                                  .build());
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("g1", "effect:colorloop"));
    }

    @Test
    void parse_canHandleEffect_none() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder().effects(List.of("prism")).build());
        addStateNow(1, "effect:none");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).effect(Effect.builder().effect("none").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_colorInput_x_y_butLightDoesNotSupportColor_exception() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS)));
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "color:#ffbaff"));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_maxValue_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 6500;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(153)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_minValue_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 2000;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(500)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_ct_butLightDoesNotSupportCt_exception() {
        mockLightCapabilities(1, LightCapabilities.builder());
        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("1", "ct:200"));
    }

    @Test
    void parse_detectsOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "on:" + true);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(true)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_sunset_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        ZonedDateTime sunset = getSunset(now);
        ZonedDateTime nextDaySunset = getSunset(now.plusDays(1));
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(sunset.minusHours(1));
        addDefaultState(1, now); // one hour before sunset
        addState(1, "sunset", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                defaultPutCall()
        );

        ensureRunnable(nextDaySunset, initialNow.plusDays(2));
    }

    @Test
    void parse_sunrise_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1)); // 07:42:11
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2)); // 07:42:05
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureRunnable(nextDaySunrise, nextNextDaySunrise);
    }

    @Test
    void parse_sunset_singleState_callsStartTimeProvider_calculatesInitialAndNextEndCorrectly() {
        ZonedDateTime sunset = getSunset(now);
        ZonedDateTime nextDaySunset = getSunset(now.plusDays(1));
        ZonedDateTime nextNextDaySunset = getSunset(now.plusDays(2));
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(sunset);
        addState(1, "sunset", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunset);

        setCurrentTimeTo(nextDaySunset.minusMinutes(5));

        runAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ScheduledRunnable nextDayState = ensureRunnable(nextDaySunset, nextNextDaySunset);

        setCurrentTimeTo(nextNextDaySunset.minusMinutes(5));

        runAndAssertPutCalls(nextDayState,
                defaultPutCall()
        );

        ensureRunnable(nextNextDaySunset, getSunset(initialNow.plusDays(3)));
    }

    private ZonedDateTime getSunset(ZonedDateTime time) {
        return startTimeProvider.getStart("sunset", time);
    }

    @Test
    void parse_sunrise_updatesStartTimeCorrectlyIfEndingNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1)); // 07:42:11
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2)); // 07:42:05
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        setCurrentTimeTo(nextDaySunrise.minusMinutes(5));

        runAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureRunnable(nextDaySunrise, nextNextDaySunrise);
    }

    @Test
    void parse_sunrise_updatesStartTimeCorrectlyIfEndingNextDay_timeIsAfterNextStart_rescheduledImmediately() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1)); // 07:42:11
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2)); // 07:42:05
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        setCurrentTimeTo(sunrise.plusDays(1)); // this is after the next day sunrise, should schedule immediately again

        scheduledRunnable.run();

        ensureRunnable(now, nextNextDaySunrise);
    }

    @Test
    void parse_nullState_treatedCorrectly_notAddedAsState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "sunrise", "ct:" + DEFAULT_CT);
        addState(1, "sunrise+10");
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_nullState_interpolateProperty_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:" + DEFAULT_CT);
        addState(1, "12:00", "interpolate:true");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(12));
    }

    @Test
    void parse_nullState_trBefore_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:" + DEFAULT_CT);
        addState(1, "12:00", "tr-before:12h");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(12));
    }

    @Test
    void parse_nullState_backToBack_transition_doesNotAddGap_stillCorrectsTooLongDuration() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "tr:1h20min");
        addState(1, "01:00");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusHours(1));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void parse_nullState_backToBack_transition_correctsTooLongDuration_alsoWhenUsingSeconds() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "tr:1min10s"); // 10 seconds too long
        addState(1, "00:01");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusMinutes(1));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("1min")) // shortened
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(1));
    }

    @Test
    void nullState_createsGap_resetsModificationTracking_stillAppliedDespiteModification() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "02:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "04:00");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now.plusHours(2), now.plusHours(4))
        );

        simulateLightOnEvent(); // no waiting states
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - 10)); // manual modification

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // still applied despite manual modification
        );

        ScheduledRunnable nextDay = ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(4)); // next day

        // next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - 15)); // manual modification

        advanceTimeAndRunAndAssertPutCalls(nextDay,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // still applied despite manual modification
        );

        ensureRunnable(initialNow.plusDays(2).plusHours(2), initialNow.plusDays(2).plusHours(4)); // next day
    }

    @Test
    void parse_useLampNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "gKitchen Lamp";
        mockLightIdForName(name, 2);
        mockDefaultLightCapabilities(2);
        when(mockedHueApi.getGroupIdentifierByName(name)).thenThrow(new GroupNotFoundException("Group not found"));
        addStateNow(name, "ct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_unknownLampName_exception() {
        String unknownLightName = "Unknown Light";
        when(mockedHueApi.getGroupIdentifierByName(unknownLightName)).thenThrow(new GroupNotFoundException("Group not found"));
        when(mockedHueApi.getLightIdentifierByName(unknownLightName)).thenThrow(new LightNotFoundException("Light not found"));

        assertThrows(LightNotFoundException.class, () -> addStateNow(unknownLightName, "ct:" + DEFAULT_CT));
    }

    @Test
    void parse_useGroupNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "Kitchen";
        int id = 12345;
        mockGroupIdForName(name, id);
        mockGroupLightsForId(id, 1, 2);
        mockDefaultGroupCapabilities(id);
        addStateNow(name, "ct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_invalidBrightnessValue_tooLow_usesMinValue() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "bri:0");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).bri(1)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_invalidBrightnessValue_tooHigh_usesMaxValue() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "bri:255");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).bri(254)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_light_invalidCtValue_ctOnlyLight_tooLow_usesMinValue() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500)
                                                  .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        addStateNow(1, "ct:152");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(153)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_invalidCtValue_ctOnlyLight_tooHigh_usesMaxValue() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        addStateNow(1, "ct:501");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(500)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_tooWarmCtValue_mired_lightAlsoSupportsColor_convertsToXY() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.COLOR)));
        addStateNow(1, "ct:501");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).x(0.5395).y(0.4098)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_tooWarmCtValue_kelvin_lightAlsoSupportsColor_convertsToXY() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.COLOR)));
        addStateNow(1, "ct:1000");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).x(0.608).y(0.3554)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_kelvin_convertsToMired_usesCTSinceInRange() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.COLOR)));
        addStateNow(1, "ct:2000");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(500)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_group_invalidCtValue_tooLow_usesMinValue() {
        mockGroupCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:99");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnable,
                expectedGroupPutCall(1).ct(100)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_lowerThanDefault_noException() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));

        addStateNow("1", "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_alsoForGroups_lowerThanDefault_noException() {
        mockGroupCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ct_group_noMinAndMax_noValidationPerformed() {
        mockGroupCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:10");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_higherThanDefault_noException() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(1000).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));

        addStateNow("1", "ct:1000");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_brightness_forOnOffLight_exception() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)));

        assertThrows(BrightnessNotSupported.class, () -> addStateNow(1, "bri:250"));
    }

    @Test
    void parse_on_forOnOffLight() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)));

        addStateNow(1, "on:true");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_invalidXAndYValue_xTooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "x:1.1", "y:0.1"));
    }

    @Test
    void parse_invalidXAndYValue_yTooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "x:0", "y:0"));
    }

    @Test
    void parse_invalidXAndY_onlyX_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "x:0.1"));
    }

    @Test
    void parse_invalidXAndY_onlyY_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "y:0.1"));
    }

    @Test
    void parse_invalidTransitionTime_tooLow_invalidProperty_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "tr:" + -1));
    }

    @Test
    void parse_invalidTransitionTime_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addStateNow("1", "tr:" + MAX_TRANSITION_TIME + 1));
    }

    @Test
    void parse_invalidPropertyValue_ct_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "ct:INVALID"));
    }

    @Test
    void parse_invalidPropertyValue_tr_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "tr:INVALIDs"));
    }

    @Test
    void parse_invalidPropertyValue_x_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "x:INVALID"));
    }

    @Test
    void parse_invalidPropertyValue_rgb_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "color:12,13"));
    }

    @Test
    void run_execution_reachable_startsAgainNextDay_repeats() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(nextDayState,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addDefaultGroupState(1, now, 1, 2, 3);
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnable,
                defaultGroupPutCall()
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Disabled
    @Test
    void run_execution_groupState_controlIndividuallyFlagSet_multipleSinglePutCalls() { // todo: do we want to further support this?
        controlGroupLightsIndividually = true;
        create();
        addDefaultGroupState(10, now, 1, 2, 3);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, true, null);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall().id("/lights/1"),
                defaultPutCall().id("/lights/2"),
                defaultPutCall().id("/lights/3")
        );

        ScheduledRunnable nextDay = ensureRunnable(now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(nextDay,
                defaultPutCall().id("/lights/1"),
                defaultPutCall().id("/lights/2"),
                defaultPutCall().id("/lights/3")
        );

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(0));
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, nextMorning, DEFAULT_BRIGHTNESS + 100, DEFAULT_CT);
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusHours(8)),
                expectedRunnable(now.plusHours(8), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(8));

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run(); // does not call any API, as its past its end

        ensureScheduledStates(0);
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addDefaultState(1, now);
        addDefaultState(1, now.minusHours(1));
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)),
                expectedRunnable(now.plusDays(1).minusHours(1), now.plusDays(1))
        );
        ScheduledRunnable nextDayState = initialStates.get(1);

        advanceTimeAndRunAndAssertPutCalls(nextDayState,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(2).minusHours(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addDefaultState(1, now);
        addDefaultState(1, now.plusHours(1));
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));

        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(1));
    }

    @Test
    void run_execution_setsStateAgainAfterPowerOnEvent() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                defaultPutCall()
        );
    }

    @Test
    void run_execution_multipleStates_stopsPowerOnEventIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(ID, secondStateStart, brightness2, ct2);
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusSeconds(10)),
                expectedRunnable(now.plusSeconds(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusSeconds(10)); // next day runnable

        setCurrentTimeTo(secondStateStart);

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable.run();  /* this aborts without any api calls, as the current state already ended */

        /* run and assert second state: */

        advanceTimeAndRunAndAssertPutCalls(initialStates.get(1),
                expectedPutCall(ID).bri(brightness2).ct(ct2)
        );

        ensureRunnable(secondStateStart.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_allStatesInTheFuture_schedulesLastStateImmediately_setsEndToFirstStateOfDay() {
        addDefaultState(1, now.plusMinutes(5));
        addDefaultState(1, now.plusMinutes(10));
        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)), // cross over
                expectedRunnable(now.plusMinutes(5), now.plusMinutes(10)) // first state
        );

        // run cross over state
        advanceTimeAndRunAndAssertPutCalls(states.getFirst(),
                defaultPutCall()
        );

        ensureRunnable(now.plusMinutes(10), now.plusDays(1).plusMinutes(5)); // second state, schedule after crossover

        setCurrentTimeTo(now.plusMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable.run();

        // no next day runnable, as it was just a temporary copy
    }

    @Test
    void run_execution_multipleStates_reachable_stopsRescheduleIfNextIntervallStarts() {
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusMinutes(10);
        addDefaultState(ID, secondStateStart);
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10));

        setCurrentTimeTo(secondStateStart);
        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable); // aborts and does not call any api calls

        advanceTimeAndRunAndAssertPutCalls(nextDayRunnable,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10));
    }

    @Test
    void run_execution_powerOnRunnableScheduledAfterStateIsSet() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable(); // next day

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void run_execution_triesAgainAfterPowerOn() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        runAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                defaultPutCall()
        );
    }

    @Test
    void run_execution_putApiConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        mockPutStateThrowable(new BridgeConnectionFailure("Failed test connection"));
        setCurrentTimeToAndRun(scheduledRunnable); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putInvalidApiResponse_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        mockPutStateThrowable(new ApiFailure("Invalid response"));
        setCurrentTimeToAndRun(scheduledRunnable); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_getConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        mockPutStateThrowable(new BridgeConnectionFailure("Failed test connection"));
        // fails on GET, retries
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putSuccessful_triesAgainAfterPowerOn() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                defaultPutCall()
        );
    }

    @Test
    void run_execution_nullState_notExecuted_justUsedToProvideEndToPreviousState() {
        addDefaultState();
        addNullState(now.plusMinutes(5));
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(5));

        scheduledRunnable.run(); // no API calls, as already ended

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(5));
    }

    @Test
    void run_execution_nullState_allInTheFuture_stillIgnored() {
        addDefaultState(1, now.plusMinutes(5));
        addNullState(now.plusMinutes(10));
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void run_execution_off_reachable_turnedOff_noConfirm() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(false)
        );

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_off() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(false)
        );

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_offState_doesNotRetryAfterPowerOn() {
        addOffState();

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(false)
        );

        ensureNextDayRunnable();

        simulateLightOnEvent();
    }

    @Test
    void run_execution_offState_interpolate_doesRetry_butOnlyUntilStateIsReached() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100");
        addState(1, "00:30", "on:false", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100),
                expectedPutCall(1).on(false).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day

        advanceCurrentTime(Duration.ofMinutes(15));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent(expectedPowerOnEnd(initialNow.plusMinutes(30))).getFirst();
    }

    @Test
    void run_execution_offState_withInvalidAdditionalProperties_doesNotRetryAfterPowerOn() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "bri:10");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(false).bri(10) // bri actually does not make any sense here
        );

        ensureNextDayRunnable();

        simulateLightOnEvent(); // not turned off again
    }

    @Test
    void run_execution_onStateOnly_currentlyOff_doesNotFireOnEventAgainWhenSelfCausedPowerOnEvenIsDetected() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:true");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, expectedState().on(false)); // light is currently off
        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(true)
        );

        ensureNextDayRunnable();

        simulateLightOnEvent(); // self caused power-on event
    }

    @Test
    void run_execution_onStateOnly_currentlyOn_doesNotFireOnEventAgainWhenSelfCausedPowerOnEvenIsDetected() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:true");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, expectedState().on(true)); // light is currently off
        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(true) // we currently don't prevent a second "on" call, even if the light is already on
        );

        ensureNextDayRunnable();

        simulateLightOnEvent(); // when turned off and on again
    }

    @Test
    void run_execution_multipleStates_userChangedStateManuallyBetweenStates_secondStateIsNotApplied_untilPowerCycle_detectsManualChangesAgainAfterwards() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);
        addState(1, now.plusHours(3), DEFAULT_BRIGHTNESS + 30, DEFAULT_CT);
        addState(1, now.plusHours(4), DEFAULT_BRIGHTNESS + 40, DEFAULT_CT);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(5);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);
        ScheduledRunnable fifthState = scheduledRunnables.get(4);

        // first state is set normally
        advanceTimeAndRunAndAssertPutCalls(firstState,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> update skipped and retry scheduled
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD) // modified
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode(ColorMode.CT));

        advanceTimeAndRunAndAssertPutCalls(secondState); // detects change, sets manually changed flag

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        advanceTimeAndRunAndAssertPutCalls(thirdState); // directly skipped since override was detected

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(3)); // next day

        // simulate power on -> sets enforce flag, rerun third state
        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)),
                expectedPowerOnEnd(initialNow.plusHours(2)),
                expectedPowerOnEnd(initialNow.plusHours(3))
        );

        powerOnEvents.get(0).run(); // temporary, already ended
        powerOnEvents.get(1).run(); // already ended

        // re-run third state after power on -> applies state as state is enforced
        advanceTimeAndRunAndAssertPutCalls(powerOnEvents.get(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT)
        );

        // no modification detected, fourth state set normally
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 20)
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode(ColorMode.CT));
        advanceTimeAndRunAndAssertPutCalls(fourthState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3), initialNow.plusDays(1).plusHours(4)); // fourth state, for next day

        // second modification detected, fifth state skipped again
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode(ColorMode.CT));
        setCurrentTimeTo(fifthState);

        fifthState.run(); // detects manual modification again

        ensureRunnable(initialNow.plusDays(1).plusHours(4), initialNow.plusDays(2)); // next day

        verify(mockedHueApi, times(3)).getLightState("/lights/1");
    }

    @Test
    void run_execution_manualOverride_groupState_allLightsWithSameCapabilities_correctlyComparesState() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 10);
        mockDefaultGroupCapabilities(1);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState("g1", now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20));
        addState("g1", now.plusHours(3), "bri:" + (DEFAULT_BRIGHTNESS + 30));
        addState("g1", now.plusHours(4), "bri:" + (DEFAULT_BRIGHTNESS + 40));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(5);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);
        ScheduledRunnable fifthState = scheduledRunnables.get(4);

        // first state is set normally
        advanceTimeAndRunAndAssertGroupPutCalls(firstState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified group state between first and second state -> update skipped and retry scheduled
        LightState.LightStateBuilder userModifiedLightState = expectedState().id("/lights/9")
                                                                             .brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD)
                                                                             .colormode(ColorMode.CT);
        LightState.LightStateBuilder sameAsFirst = expectedState().id("/lights/10")
                                                                  .brightness(DEFAULT_BRIGHTNESS)
                                                                  .colormode(ColorMode.CT);
        setGroupStateResponses(1, sameAsFirst, userModifiedLightState);
        setCurrentTimeTo(secondState);

        secondState.run(); // detects change, sets manually changed flag

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        setCurrentTimeTo(thirdState);

        thirdState.run(); // directly skipped

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(3)); // next day

        // simulate power on -> sets enforce flag, rerun third state
        simulateLightOnEvent("/groups/1");

        List<ScheduledRunnable> powerOnEvents = ensureScheduledStates(3);

        powerOnEvents.get(0).run(); // temporary, already ended
        powerOnEvents.get(1).run(); // already ended

        // re-run third state after power on -> applies state as state is enforced
        advanceTimeAndRunAndAssertGroupPutCalls(powerOnEvents.get(2),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 20)
        );

        // no modification detected (same as third), fourth state set normally
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS + 20).colormode(ColorMode.CT),
                expectedState().id("/lights/10").brightness(DEFAULT_BRIGHTNESS + 20).colormode(ColorMode.CT)
        );

        advanceTimeAndRunAndAssertGroupPutCalls(fourthState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 30)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3), initialNow.plusDays(1).plusHours(4)); // fourth state, for next day

        // second modification detected, fifth state skipped again
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS + 5).colormode(ColorMode.CT),
                expectedState().id("/lights/10").brightness(DEFAULT_BRIGHTNESS + 5).colormode(ColorMode.CT)
        );
        setCurrentTimeTo(fifthState);

        fifthState.run(); // detects manual modification again

        ensureRunnable(initialNow.plusDays(1).plusHours(4), initialNow.plusDays(2)); // next day

        verify(mockedHueApi, times(3)).getGroupStates("/groups/1");
    }

    @Test
    void run_execution_manualOverride_groupState_someLightsNotReachable_ignoredInComparison() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 10);
        mockDefaultGroupCapabilities(1);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        // first state is set normally
        advanceTimeAndRunAndAssertGroupPutCalls(firstState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // user modified group state between first and second state -> not relevant, since light is considered unreachable; even if the light state reports "on:true"
        LightState.LightStateBuilder userModifiedLightState = expectedState().id("/lights/9")
                                                                             .unavailable(true)
                                                                             .brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD)
                                                                             .colormode(ColorMode.CT);
        LightState.LightStateBuilder sameAsFirst = expectedState().id("/lights/10")
                                                                  .brightness(DEFAULT_BRIGHTNESS)
                                                                  .colormode(ColorMode.CT);
        setGroupStateResponses(1, sameAsFirst, userModifiedLightState);

        advanceTimeAndRunAndAssertGroupPutCalls(secondState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no change detected
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void manualOverride_group_overlappingStates_overriddenBySchedule_notDetectedAsOverridden() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 10, 11);
        mockDefaultGroupCapabilities(1);
        addKnownLightIdsWithDefaultCapabilities(9, 10);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(9, now, "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState(10, now, "bri:" + (DEFAULT_BRIGHTNESS - 20));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.get(1),
                expectedPutCall(10).bri(DEFAULT_BRIGHTNESS - 20)
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.get(2),
                expectedPutCall(9).bri(DEFAULT_BRIGHTNESS - 10)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2))
        );

        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - 10), // modified by schedule
                expectedState().id("/lights/10").brightness(DEFAULT_BRIGHTNESS - 20), // modified by schedule
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // unmodified
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no override detected
        );

        // next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingStates_manuallyOverriddenGroupLight_detected() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 11);
        mockDefaultGroupCapabilities(1);
        addKnownLightIdsWithDefaultCapabilities(9);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(9, now, "bri:" + DEFAULT_BRIGHTNESS); // same property

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // individual light: same as group
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1),
                expectedPutCall(9).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS),
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD) // manually modified
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingStates_manuallyOverriddenGroupLight_differentFromSchedule_detected() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 11);
        mockDefaultGroupCapabilities(1);
        addKnownLightIdsWithDefaultCapabilities(9);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(9, now, "bri:" + DEFAULT_BRIGHTNESS); // same property

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // individual light: same as group
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1),
                expectedPutCall(9).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD), // manually modified
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_overriddenBySchedule_notDetectedAsOverridden() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 9, 11);
        mockGroupLightsForId(2, 9, 30);
        mockGroupLightsForId(3, 9, 55);
        mockAssignedGroups(9, Arrays.asList(1, 2, 4, 3)); // group 4 has no schedules
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g2", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g3", now, "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // second group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        // third group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(3).bri(DEFAULT_BRIGHTNESS - 10)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)),
                expectedRunnable(now.plusDays(1), now.plusDays(2)),
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );

        // next group call
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - 10), // overridden by third group schedule
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // no modification
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no manual override detected
        );

        // next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_manuallyOverridden_noMatchingGroup_detectsChanges() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 9, 11);
        mockGroupLightsForId(2, 9, 30);
        mockGroupLightsForId(3, 9, 55);
        mockAssignedGroups(9, Arrays.asList(1, 2, 3));
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g2", now, "bri:" + (DEFAULT_BRIGHTNESS - 20));
        addState("g3", now, "bri:" + (DEFAULT_BRIGHTNESS - 30));
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // second group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(2).bri(DEFAULT_BRIGHTNESS - 20)
        );

        // third group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(3).bri(DEFAULT_BRIGHTNESS - 30)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)),
                expectedRunnable(now.plusDays(1), now.plusDays(2)),
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD), // manually overridden (matches no other group)
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // no modification
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_onlyCurrentGroupScheduled_correctlyDetectsOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 9, 11);
        mockAssignedGroups(9, Arrays.asList(1, 2, 3)); // groups 2 and 3 are not scheduled
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD), // manually overridden
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // no modification
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_onlyCurrentGroupScheduled_noChanges_noOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 9, 11);
        mockAssignedGroups(9, Arrays.asList(1, 2, 3)); // groups 2 and 3 are not scheduled
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10))
        );

        // next group call
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS),
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        // next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void run_execution_manualOverride_stateIsDirectlyRescheduled() {
        enableUserModificationTracking();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden("/lights/" + ID); // start with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureScheduledStates(1);

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void run_execution_group_manualOverride_notScheduledWhenContainedLightIsTurnedOnViaSoftware() {
        enableUserModificationTracking();
        int groupId = 4;
        int lightId = 66;
        mockGroupLightsForId(groupId, lightId);
        mockDefaultGroupCapabilities(groupId);
        mockDefaultLightCapabilities(lightId);

        addState("g" + groupId, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(lightId, now, "ct:" + DEFAULT_CT);
        manualOverrideTracker.onManuallyOverridden("/groups/" + groupId);
        manualOverrideTracker.onManuallyOverridden("/lights/" + lightId);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);

        scheduledRunnables.get(0).run();
        ensureScheduledStates(1); // next day
        scheduledRunnables.get(1).run();
        ensureScheduledStates(1); // next day

        simulateLightOnEvent("/lights/" + lightId); // non-physical light on event

        ensureScheduledStates(1); // only light is rescheduled, assigned group is ignored
    }

    @Test
    void run_execution_twoGroups_manualOverride_bothRescheduledWhenContainedLightIsTurnedOnPhysically() {
        enableUserModificationTracking();
        mockDefaultLightCapabilities(1);
        mockDefaultGroupCapabilities(5);
        mockDefaultGroupCapabilities(6);
        mockGroupLightsForId(5, 1);
        mockGroupLightsForId(6, 1);
        mockAssignedGroups(1, 5, 6); // light is part of two groups
        when(mockedHueApi.getAffectedIdsByDevice("/device/10")).thenReturn(List.of("/lights/1", "/groups/5", "/groups/6"));

        addState(1, now, "ct:" + DEFAULT_CT);
        addState("g5", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g6", now, "bri:" + DEFAULT_BRIGHTNESS);
        manualOverrideTracker.onManuallyOverridden("/lights/1");
        manualOverrideTracker.onManuallyOverridden("/groups/5");
        manualOverrideTracker.onManuallyOverridden("/groups/6");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);

        scheduledRunnables.get(0).run();
        ensureScheduledStates(1); // next day
        scheduledRunnables.get(1).run();
        ensureScheduledStates(1); // next day
        scheduledRunnables.get(2).run();
        ensureScheduledStates(1); // next day

        scheduler.getHueEventListener().onPhysicalOn("/device/10");

        ensureScheduledStates(3); // individual light, as well as contained groups are rescheduled
    }

    @Test
    void run_execution_manualOverride_resetAfterPowerOn() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);

        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first state -> current light state ignored

        setLightStateResponse(1, expectedState().brightness(50)); // ignored for first run
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();


        // second state -> detects manual override

        setLightStateResponse(1, expectedState().brightness(50));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // detects manual override

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        // power on of second state -> resets overridden flag again

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(110)
        );

        // next day -> applied normally again

        setLightStateResponse(1, expectedState().brightness(110));
        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)) // next day
        );
    }

    @Test
    void run_execution_manualOverride_resetAfterPhysicalPowerOn() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        when(mockedHueApi.getAffectedIdsByDevice("/device/354")).thenReturn(List.of("/lights/1"));

        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first state -> current light state ignored

        setLightStateResponse(1, expectedState().brightness(20)); // ignored for first run
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();


        // second state -> detects manual override

        setLightStateResponse(1, expectedState().brightness(20));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // detects manual override

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        // physical power on of second state -> resets overridden flag again

        scheduler.getHueEventListener().onPhysicalOn("/device/354");

        List<ScheduledRunnable> powerOnRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(110)
        );

        // next day -> applied normally again

        setLightStateResponse(1, expectedState().brightness(110));
        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)) // next day
        );
    }

    @Test
    void run_execution_manualOverride_stateIsDirectlyRescheduledOnRun() {
        enableUserModificationTracking();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden("/lights/" + ID); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeToAndRun(scheduledRunnable);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void run_execution_manualOverride_forGroup_stateIsDirectlyRescheduledOnRun() {
        enableUserModificationTracking();

        addDefaultGroupState(2, now, 1);
        manualOverrideTracker.onManuallyOverridden("/groups/2"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeToAndRun(scheduledRunnable);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        simulateLightOnEvent("/groups/2");
        ensureScheduledStates(1);
    }

    @Test
    void run_execution_manualOverride_multipleStates_powerOnAfterNextDayStart_beforeNextState_alreadyRescheduledBefore() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.getFirst();

        firstState.run(); // directly reschedule

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setCurrentTimeTo(initialNow.plusDays(1).plusMinutes(30)); // after next day start of state, but before next day start of second state

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run(); // already ended
    }

    @Test
    void run_execution_manualOverride_multipleStates_powerOnAfterNextDayStart_afterNextState_alreadyRescheduledBefore() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstState = scheduledRunnables.getFirst();

        firstState.run(); // directly reschedule

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1).plusMinutes(30)); // after start of next day for first state, but after start of next state

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run(); // already ended
    }

    @Test
    void run_execution_manualOverride_forDynamicSunTimes_turnedOnEventOnlyNextDay_correctlyReschedulesStateOnSameDay() {
        enableUserModificationTracking();
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2));
        setCurrentAndInitialTimeTo(sunrise);

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "sunrise+60", "bri:" + (DEFAULT_BRIGHTNESS + 10));
        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        setCurrentTimeToAndRun(firstState);

        ensureRunnable(nextDaySunrise, nextDaySunrise.plusHours(1)); // next day

        setCurrentTimeToAndRun(secondState);

        ensureRunnable(nextDaySunrise.plusHours(1), nextNextDaySunrise); // next day

        setCurrentTimeTo(initialNow.plusDays(1).minusHours(1)); // next day, one hour before next schedule
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(sunrise.plusHours(1)), // already ended
                expectedPowerOnEnd(nextDaySunrise)
        );

        powerOnRunnables.getFirst().run(); // already ended
    }

    @Test
    void run_execution_manualOverride_multipleStates_detectsChangesIfMadeDuringCrossOverState() {
        enableUserModificationTracking();

        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + DEFAULT_CT, "force:false"); // force:false = default

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2))

        );
        ScheduledRunnable crossOverState = scheduledRunnables.get(0);
        ScheduledRunnable firstState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT) // runs through normally
        );

        ScheduledRunnable secondState = ensureRunnable(now.plusHours(2), now.plusDays(1).plusHours(1));

        // user modified light state before first state -> update skipped and retry scheduled
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                .colorTemperature(DEFAULT_CT));

        setCurrentTimeToAndRun(firstState); // no update, as modification was detected

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        setCurrentTimeToAndRun(secondState);  // no update, as modification was detected

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2).plusHours(1)); // next day
    }

    @Test
    void run_execution_manualOverride_forceProperty_true_ignored() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10), "force:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(firstState, defaultPutCall());
        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> ignored since force:true is set
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                .colorTemperature(DEFAULT_CT));

        advanceTimeAndRunAndAssertPutCalls(secondState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // enforced despite user changes
        );

        ensureRunnable(initialNow.plusHours(1).plusDays(1), initialNow.plusDays(2)); // for next day
    }

    @Test
    void run_execution_manualOverride_lightConsideredOffAtSecondPutCall_doesNotDetectChangesForThirdState() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(1, now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20));
        addState(1, now.plusHours(3), "bri:" + (DEFAULT_BRIGHTNESS + 30));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusHours(3)),
                expectedRunnable(now.plusHours(3), now.plusDays(1))
        );
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);

        advanceTimeAndRunAndAssertPutCalls(firstState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1)); // for next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS)); // same as first

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(secondState); // no put calls as off

        ensureRunnable(initialNow.plusDays(1).plusHours(1)); // for next day

        mockIsLightOff(1, false);

        advanceTimeAndRunAndAssertPutCalls(thirdState); // still no put call expected, as light has been set to off

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(3))
        );
        // light state is still like first state -> not recognized as override as last seen state has not been updated through second state

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20)
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 20)); // same as third

        advanceTimeAndRunAndAssertPutCalls(fourthState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3)); // for next day
    }

    @Test
    void run_execution_manualOverride_firstCallFailed_powerOn_lastSeenCorrectlySet_usedForModificationTracking_detectedCorrectly() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        mockIsLightOff(1, true);
        // not marked as seen
        advanceTimeAndRunAndAssertPutCalls(firstState);

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1)); // next day

        mockIsLightOff(1, false);

        // Power on -> reruns first state

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now.plusHours(1));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // Second state -> detects manual override

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden

        advanceTimeAndRunAndAssertPutCalls(secondState); // detected as overridden -> no put calls

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_manualOverride_transitionTimeBefore_longDuration_detectsChangesCorrectly() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(6), "bri:" + (DEFAULT_BRIGHTNESS + 50), "tr-before:6h");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 14).transitionTime(MAX_TRANSITION_TIME) // first split call
        );

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpStates.getFirst();

        // second split -> no override detected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 14)); // same as first

        advanceTimeAndRunAndAssertPutCalls(secondSplit,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 28).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable thirdSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // third split -> still no override detected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 28)); // same as second

        advanceTimeAndRunAndAssertPutCalls(thirdSplit,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 42).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable fourthSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // fourth split -> detects override

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 42 + BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden

        advanceTimeAndRunAndAssertPutCalls(fourthSplit); // detects override

        // simulate light on -> re run fourth (and last) split

        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS * 3, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1)) // fourth split again
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnEvents.get(3),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 42), // interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 50).transitionTime(36000) // last split part
        );
    }

    @Test
    void run_execution_offState_manualOverride_offStateIsNotRescheduledWhenOn_skippedAllTogether() {
        enableUserModificationTracking();

        addOffState();
        manualOverrideTracker.onManuallyOverridden("/lights/1"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(nextDayRunnable);
        advanceCurrentTime(Duration.ofMinutes(10)); // some delay

        nextDayRunnable.run();

        ensureRunnable(initialNow.plusDays(2));

        // no power-on events have been scheduled

        simulateLightOnEvent();
    }

    @Test
    void run_execution_offState_forceProperty_reschedulesOffState_alsoIfAlreadyOverridden() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "force:true");
        manualOverrideTracker.onManuallyOverridden("/lights/1"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable); // overridden -> reschedules on power on

        ScheduledRunnable nextDay = ensureRunnable(now.plusDays(1)); // next day

        // first power on after overridden -> turned off again

        ScheduledRunnable powerOnRunnable1 = simulateLightOnEventExpectingSingleScheduledState(now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable1,
                expectedPutCall(1).on(false)
        );

        // next day -> runs through normally

        advanceTimeAndRunAndAssertPutCalls(nextDay,
                expectedPutCall(1).on(false)
        );

        ensureRunnable(now.plusDays(1)); // next day

        // second power on after normal run through -> also rescheduled for power on

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now), // already ended
                expectedPowerOnEnd(now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).on(false)
        );
    }

    @Test
    void run_execution_offState_putReturnedFalse_signaledOff_offStateIsNotRescheduledWhenOn_skippedAllTogether() {
        addOffState();

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        mockIsLightOff(ID, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable); // no put call
        mockIsLightOff(ID, false);

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1)); // next day

        advanceTimeAndRunAndAssertPutCalls(nextDayRunnable);

        ensureRunnable(initialNow.plusDays(2)); // next day

        // no power-on events have been scheduled

        simulateLightOnEvent();
    }

    @Test
    void run_execution_lightIsOff_doesNotMakeAnyCalls_unlessStateHasOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10), "force:true"); // force does not have any effect
        addState(1, "02:00", "on:true", "bri:" + (DEFAULT_BRIGHTNESS + 20));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // second state is skipped

        advanceTimeAndRunAndAssertPutCalls(secondState); // still no put calls, does not check "off" state again

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        // third state has "on:true" property -> is run normally again
        advanceTimeAndRunAndAssertPutCalls(thirdState,
                expectedPutCall(1).on(true).bri(DEFAULT_BRIGHTNESS + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)),
                expectedPowerOnEnd(initialNow.plusHours(2)),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // already ended

        verify(mockedHueApi, times(1)).isLightOff("/lights/1");
        verify(mockedHueApi, never()).isGroupOff("/groups/1");
    }

    @Test
    void run_execution_lightIsOff_apiDoesNotAllowOffUpdates_notApplied() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        ensureScheduledStates(); // does not schedule anything on light off, if API does not off updates

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // light is off, no update

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_stillAppliesUpdates() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        // light is off, still applied

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(60)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_manualOverrides_stillAppliesUpdates() {
        enableSupportForOffLightUpdates();
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:60");
        addState(1, "02:00", "bri:70");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // detects manual override, still on, not applied

        setLightStateResponse(1, expectedState().brightness(50 - BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        // light turned off -> automatically re-applies state

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst());
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.get(1),
                expectedPutCall(1).bri(60)
        );

        // next state applied normally again

        setLightStateResponse(1, expectedState().on(false).brightness(60));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(70)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_lightHasOn_removesOnWhenLightIsTurnedOff() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "on:true");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst(),
                expectedPutCall(1).bri(50) // removes "on:true"
        );
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_usesFullPicture() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "ct:300");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).ct(300) // uses full picture
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst(),
                expectedPutCall(1).bri(50).ct(300) // uses full picture
        );
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_removesTransitionTime() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "tr:5s");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50) // removes transition time
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_lightHasOn_doesNotRemoveTransitionEvenIfCurrentlyOff() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "on:true", "tr:5s");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true).transitionTime(tr("5s")) // keeps transition time
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_lightHasOn_andForce_forcesLightToBeOn() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "on:true", "force:true");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true) // keeps "on:true" because of force:true
        );
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_interpolation_stillAppliesUpdates() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is off for interpolated state: only applies interpolated call

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(50)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // first background interpolation

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).bri(58)
        );

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(initialNow.plusMinutes(40), initialNow.plusHours(2));

        // second background interpolation

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(67)
        );

        ScheduledRunnable nextBackgroundInterpolation2 = ensureRunnable(initialNow.plusMinutes(45), initialNow.plusHours(2));

        // third background interpolation

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation2,
                expectedPutCall(1).bri(75)
        );

        ensureRunnable(initialNow.plusMinutes(50), initialNow.plusHours(2));

        // simulate light turn off after time passing

        setCurrentTimeTo(initialNow.plusMinutes(55));

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.get(1),
                expectedPutCall(1).bri(92)
        );

        // next state applied normally again

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(150)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_apiAllowsOffUpdates_interpolation_lightIsOn_doesNotPerformBackgroundInterpolation() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is on: normal interpolation

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("30min"))
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // first background interpolation: light is still on -> no update

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst());

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(initialNow.plusMinutes(40), initialNow.plusHours(2));

        // second background interpolation: light is off -> updates

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(67)
        );

        ScheduledRunnable nextBackgroundInterpolation2 = ensureRunnable(initialNow.plusMinutes(45), initialNow.plusHours(2));

        setCurrentTimeTo(scheduledRunnables.get(2));

        runAndAssertPutCalls(nextBackgroundInterpolation2); // already ended
    }

    @Test
    void run_execution_apiAllowsOffUpdates_interpolation_lightHasOn_stillSchedulesBackgroundInterpolation_onlyAppliedWhenOff_doesNotReuseOn() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "on:true", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is on: normal interpolation

        mockIsLightOff(1, true); // even if off, on:true forces it to be treated as on
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(50).on(true),
                expectedPutCall(1).bri(100).on(true).transitionTime(tr("30min"))
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // simulate light turned on: applies state again (without on:true this time)

        mockIsLightOff(1, false);
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.getFirst());
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(50),
                expectedPutCall(1).bri(100).transitionTime(tr("30min"))
        );

        // background interpolation: light is still on -> no update

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst());

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(initialNow.plusMinutes(40), initialNow.plusHours(2));

        // background interpolation: light is off -> updates

        mockIsLightOff(1, true);

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(67)
        );

        ensureRunnable(initialNow.plusMinutes(41), initialNow.plusHours(2)); // background interpolation 2
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_interpolation_stillAppliesOnOffAfterSyncedSceneActivation() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is on: normal interpolation

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("30min"))
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // advance to already reached, then simulate synced scene activation

        advanceCurrentTime(Duration.ofMinutes(30));

        simulateSyncedSceneActivated("/groups/another", "/lights/1");

        List<ScheduledRunnable> powerOnRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // synced scene and already reached, skipped

        // turn off light still inside synced scene ignore window

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds).minusSeconds(1));

        simulateLightOffEvent("/lights/1");

        ScheduledRunnable powerOffRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(2))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnable,
                expectedPutCall(1).bri(100) // still applied
        );
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_interpolation_apiFailure_retries_backgroundInterpolationsStopOnceReached() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:5min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(55)),
                expectedRunnable(now.plusMinutes(55), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(55)); // next day

        // Interpolated state: off

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(50)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(56), initialNow.plusDays(1)), // next background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(55), initialNow.plusDays(2)) // next day
        );

        // first background interpolation: api failure -> retried
        doThrow(ApiFailure.class).when(mockedHueApi).putState(any());
        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).bri(60)
        );

        ScheduledRunnable retry = ensureRunnable(now.plusMinutes(syncFailureRetryInMinutes), initialNow.plusDays(1));

        resetMockedApi();

        advanceTimeAndRunAndAssertPutCalls(retry,
                expectedPutCall(1).bri(90)
        );

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(now.plusMinutes(1), initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(100)
        );

        // no further background interpolations scheduled
    }

    @Test
    void run_execution_lightIsOff_apiAllowsOffUpdates_interpolation_requireSceneActivation_notApplied() {
        requireSceneActivation();
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, no scene activation, ignored

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst());

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is off for interpolated state, no scene activation still ignored

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        // no background interpolation scheduled
        ensureRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)); // next day

        // simulate light turn off after time passing

        setCurrentTimeTo(initialNow.plusMinutes(55));

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.get(1)); // no scene activation, ignored

        mockIsLightOff(1, false);
        simulateSyncedSceneActivated("/groups/another", "/lights/1");

        ScheduledRunnable sceneRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(2))
        ).getFirst();

        // turn on synced scene -> applied normally

        advanceTimeAndRunAndAssertPutCalls(sceneRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("5min"))
        );

        // next state applied normally again (turned on by synced scene flag still activate)

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(150)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_groupIsOff_doesNotMakeAnyCalls_unlessStateHasOnProperty() {
        mockGroupLightsForId(1, 1, 2);
        mockDefaultGroupCapabilities(1);
        addState("g1", "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState("g1", "02:00", "on:true", "bri:" + (DEFAULT_BRIGHTNESS + 20));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);

        mockIsGroupOff(1, true);
        advanceTimeAndRunAndAssertGroupPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // second state is skipped

        advanceTimeAndRunAndAssertGroupPutCalls(secondState); // still no put calls, does not check "off" state again

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        // third state has "on:true" property -> is run normally again
        advanceTimeAndRunAndAssertGroupPutCalls(thirdState,
                expectedGroupPutCall(1).on(true).bri(DEFAULT_BRIGHTNESS + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusHours(1)),
                expectedPowerOnEnd(initialNow.plusHours(2)),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnables.get(1)); // already ended

        verify(mockedHueApi, times(1)).isGroupOff("/groups/1");
        verify(mockedHueApi, never()).isLightOff("/lights/1");
    }

    @Test
    void run_execution_groupIsOff_apiAllowsOffUpdates_controlsLightsIndividually() {
        enableSupportForOffLightUpdates();
        mockGroupLightsForId(1, 1, 2);
        mockDefaultGroupCapabilities(1);
        addState("g1", "00:00", "bri:50", "tr:2s");
        addState("g1", "01:00", "bri:60", "tr:5s");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(50).transitionTime(tr("2s"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        mockIsGroupOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),  // off lights updated individually, no transition time
                expectedPutCall(1).bri(60),
                expectedPutCall(2).bri(60)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_lightIsOff_doesNotMakeAnyCalls_ignoresOffCheckOnPowerOn() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        mockIsLightOff(2, true);
        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // power on -> ignores off state

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        // second state, detects off again

        advanceTimeAndRunAndAssertPutCalls(secondState);

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_lightIsOff_detectedViaSingleOffEvent_doesNotMakeAnyCalls_restAfterOnEvent() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        simulateLightOffEvent("/lights/2");

        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // power on -> ignores off state

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        // second state, still treated as on

        advanceTimeAndRunAndAssertPutCalls(secondState,
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_lightIsOff_doesNotApplyNextState_evenIfManualTurnOnAndOffHappensInBetween() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, "00:00", "on:false");
        addState(2, "01:00", "bri:" + DEFAULT_BRIGHTNESS);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(firstState,
                expectedPutCall(2).on(false)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // power on -> does not repeat on:false, since no force:true is present

        simulateLightOnEvent("/lights/2");
        ensureScheduledStates(0); // no power-on states scheduled

        // manually turn-off lights again

        simulateLightOffEvent("/lights/2");

        // second state, still treated as off

        advanceTimeAndRunAndAssertPutCalls(secondState);

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void run_execution_lightOffCheck_connectionFailure_retries() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        // simulate light off call to fail
        when(mockedHueApi.isLightOff("/lights/1")).thenThrow(new BridgeConnectionFailure("Connection error"));

        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        // creates retry state
        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        // reset mock and retry -> now successful
        resetMockedApi();
        mockIsLightOff("/lights/1", false);

        advanceTimeAndRunAndAssertPutCalls(retryState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day
    }

    @Test
    void sceneTurnedOn_apiDoesNotReturnASceneName_noException() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated, with no name -> no exception
        simulateSceneWithNameActivated("/groups/1", null, "/lights/1", "/lights/2");
    }

    @Test
    void sceneTurnedOn_containsLightThatHaveSchedules_insideSceneIgnoreWindow_doesNotApplySchedules() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/2", "/lights/1", "/lights/2");
        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS - 10));

        // wait a bit, but still inside ignore window
        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds - 1));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable); // no put call, as inside the ignore window of the scene turn-on

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no put calls, as state is detected as overridden by scene

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        /* simulate another power-on: now the state is applied and rescheduled for next day */
        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(1),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS + 10)
        );
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_insideIgnoreWindow_doesNotApplyStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate synced scene activated: automatically triggers light on event
        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2");

        // additional light on event; no additional runnable created
        simulateLightOnEvent("/groups/1");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusMinutes(10))).getFirst();

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds - 1)); // inside ignore window

        runAndAssertPutCalls(powerOnRunnable); // no additional update
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_outsideIgnoreWindow_doesApplyStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate synced scene activated
        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusMinutes(10))).getFirst();

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds)); // outside ignore window

        runAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(50)
        );
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_doesNotApplyStateAgain_stillResetsManualOverride_nextDayAppliedNormally() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ScheduledRunnable nextDayRunnable = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();

        // second state: detected as overridden

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // detected as overridden

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // simulate synced scene activated: automatically triggers light on event and resets manual override

        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        runAndAssertPutCalls(powerOnRunnable); // no additional update

        // next day state: now applied again, as override has been reset
        setLightStateResponse(1, expectedState().brightness(60));
        advanceTimeAndRunAndAssertPutCalls(nextDayRunnable,
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)); // next day
    }

    @Test
    void sceneTurnedOn_syncedScene_withInterpolation_appliesStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50), // interpolated
                expectedPutCall(1).bri(60).transitionTime(tr("10min"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // simulate synced scene activated
        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusDays(1))).getFirst();

        runAndAssertPutCalls(powerOnRunnable,
                // no interpolated put call; as this was already set by the scene
                expectedPutCall(1).bri(60).transitionTime(tr("10min"))  // re-applies state due to ongoing interpolation
        );
    }

    @Test
    void sceneTurnedOn_containsLightOfGroupThatHasSchedule_ignoresGroup() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(2);
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 2); // group contains /lights/2
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/1", "/lights/2");
        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS - 10));

        // since its a non synced scene, no direct light on event
        ensureScheduledStates();

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(now.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnable); // no put call, as inside the ignore window of the scene turn-on

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(1)); // no put calls, as state is detected as overridden by scene

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        /* simulate another power-on: now the state is applied */
        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(secondPowerOnRunnables.get(1),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );
    }

    @Test
    void sceneTurnOn_containsLightThatHaveSchedules_lightTurneOnAfterIgnoreWindow_stillApplied() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/1", "/lights/2");
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - 10));

        // wait until ignore window passed
        advanceCurrentTime(Duration.ofSeconds(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        ); // still applied

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        ); // no modification detected

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneTurnedOn_containsLightThatHaveSchedules_forceProperty_stillAppliesSchedules() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(3);
        addState(3, now, "bri:" + DEFAULT_BRIGHTNESS, "force:true");
        addState(3, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(3, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(3).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/40", "/lights/3");

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/3",
                expectedPowerOnEnd(now.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(3).bri(DEFAULT_BRIGHTNESS) // put was forced
        );
    }

    @Test
    void sceneTurnedOn_lightWasAlreadyOn_notModifiedByScene_nextStateScheduledNormally() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        advanceCurrentTime(Duration.ofMinutes(10));
        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/40", "/lights/1");

        // no light on event, light was already on; light has not been modified by scene

        // next runnable: applied normally
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneTurnedOn_lightWasAlreadyOn_modifiedByScene_detectsModification() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        advanceCurrentTime(Duration.ofMinutes(10));
        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/40", "/lights/1");
        // modifies light state
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD));

        // next runnable: detects modification
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no put

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneTurnedOn_noUserModificationTracking_stillScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now.plusMinutes(10));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // not ignored
        );
    }

    @Test
    void sceneSync_groupState_createsAndUpdatesScene_evenIfGroupIsOff_stillSchedulesForNextDay() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:150");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        mockIsGroupOff(1, true);
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // no put call

        // still updates scene
        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150));

        // still schedules next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_groupState_singleState_createsScene() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_groupState_withNullState_createsScene() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 6);
        addState("g2", now, "bri:100");
        addState("g2", now.plusMinutes(10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(100)
        );

        assertSceneUpdate("/groups/2", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );
    }

    // todo: test scene sync with scene states; getting the relevant put calls may not work as expected

    @Test
    void sceneSync_delayGreaterThanZero_createsScheduledTaskForSync() {
        sceneSyncDelayInSeconds = 5;
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:150");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertAllSceneUpdatesAsserted(); // no scene update just yet

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusSeconds(sceneSyncDelayInSeconds), now.plusMinutes(10)), // scene sync schedule
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        setCurrentTimeTo(followUpRunnables.getFirst());
        followUpRunnables.getFirst().run();

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));
    }

    @Test
    void sceneSync_groupState_noSyncOnPowerOn() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:150");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // Power on -> no scene sync

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/1", expectedPowerOnEnd(now.plusMinutes(10))).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnable,
                expectedGroupPutCall(1).bri(100)
        );
    }

    @Test
    void sceneSync_initiallyOff_turnedOnAfterwards_noAdditionalSyncOnPowerOnRetry() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        addState(1, now, "bri:100");
        addState(1, "12:00", "bri:200");

        mockIsLightOff(1, true);

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst()); // light is off, no put call

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(100));

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(12)) // next day
        );

        mockIsLightOff(1, false);

        List<ScheduledRunnable> powerOnRetryRunnables = simulateLightOnEvent(
                expectedRunnable(now, now.plusHours(12))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRetryRunnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        assertAllSceneUpdatesAsserted(); // no additional scene sync on power-on
    }

    @Test
    void sceneSync_initiallyOff_interpolate_turnedOnAfterwards_noAdditionalSyncOnPowerOnRetry_doesNotAffectInterpolatedSceneSyncs() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:150", "interpolate:true");
        addState(1, now.plusMinutes(20), "bri:200");

        mockIsLightOff(1, true);

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst()); // light is off, no put call

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(100));

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), initialNow.plusMinutes(20)), // scene sync schedule
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(20)) // next day
        );

        mockIsLightOff(1, false);

        List<ScheduledRunnable> powerOnRetryRunnables = simulateLightOnEvent(
                expectedRunnable(now, now.plusMinutes(20))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRetryRunnables.getFirst(),
                expectedPutCall(1).bri(100),
                expectedPutCall(1).bri(150).transitionTime(tr("10min"))
        );

        assertAllSceneUpdatesAsserted(); // no additional scene sync on power-on

        // interpolated sync call schedule is not affected and continues independently
        setCurrentTimeTo(followUpRunnables.getFirst());
        followUpRunnables.getFirst().run();

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(110));

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), initialNow.plusMinutes(20)) // next scene sync schedule
        );
    }

    @Test
    void sceneSync_longTransition_usesSplitCall_splitCallDoesNotTriggerSceneSync() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5);
        addState("g1", now, "bri:100");
        addState("g1", now.plusHours(2), "bri:200", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100),
                expectedGroupPutCall(1).bri(183).transitionTime(MAX_TRANSITION_TIME)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(100));

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(9), now.plusDays(1)), // scene sync schedule
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // split call
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // Run split call -> triggers no scene sync

        ScheduledRunnable splitCall = followUpRunnables.get(1);
        advanceTimeAndRunAndAssertGroupPutCalls(splitCall,
                expectedGroupPutCall(1).bri(200).transitionTime(tr("20min"))
        );

        // performs no additional scene sync
    }

    @Test
    void sceneSync_groupState_createsAndUpdatesScene_interpolation_updatesSceneWithInterpolatedState_schedulesAdditionalSceneSync() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 5);
        addState("g2", now, "bri:100");
        addState("g2", now.plusMinutes(10), "bri:150", "interpolate:true");
        addState("g2", now.plusMinutes(20), "bri:200");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(100),
                expectedGroupPutCall(2).bri(150).transitionTime(tr("10min"))
        );

        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(100));

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(20)), // scene sync schedule
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(20)) // next day
        );

        setCurrentTimeTo(followUpRunnables.getFirst());
        followUpRunnables.getFirst().run();

        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(110));

        ScheduledRunnable syncRunnable2 = ensureRunnable(now.plusMinutes(2),
                initialNow.plusMinutes(20)); // next sync, correct end

        // power on
        advanceCurrentTime(Duration.ofMinutes(4));
        mockIsGroupOff(2, false);
        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/2",
                expectedRunnable(now, initialNow.plusMinutes(20))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnable,
                expectedGroupPutCall(2).bri(130),
                expectedGroupPutCall(2).bri(150).transitionTime(tr("4min"))
        );

        setCurrentTimeTo(runnables.get(2)); // exactly at end

        syncRunnable2.run(); // already ended, no additional sync
    }

    @Test
    void sceneSync_usesFullPicture_singleGroup_withNullState_stopsFullPictureCalculation() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 7);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10)); // stops full picture propagation
        addState("g1", now.plusMinutes(20), "ct:500");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        // first state: uses full picture

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100).ct(500) // only because of initial run
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).bri(100).ct(500));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // second state: stops at null state

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).ct(500)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).ct(500));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_usesFullPicture_multipleOverlappingGroups_withNullStateOnSmallerGroup_notInterpretedAsOverride_usesStateFromBiggerGroup() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 7, 8);
        mockGroupLightsForId(2, 7);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addState("g1", now, "bri:110");
        addState("g2", now, "bri:120");
        addState("g1", now.plusMinutes(10), "bri:210");
        addState("g2", now.plusMinutes(10)); // null state, not scheduled but used during full picture to stop propagation

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1 first state
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // g2
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)) // g1 second state
        );

        // g1 first state

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(110)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(7).bri(120));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1 second state

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(1).bri(210)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).bri(210), expectedPutCall(8).bri(210)); // no overrides from g2
        assertSceneUpdate("/groups/2", expectedPutCall(7).bri(210)); // takes state from bigger group g1; does not use full picture

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_sceneUpdateConsidersFullPicture_usesMissingPropertiesFromPreviousStates_alsoForLightOn() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100", "ct:500");
        addState("g1", now.plusMinutes(10), "bri:150");
        addState("g1", now.plusMinutes(20), "x:0.4", "y:0.5");
        addState("g1", now.plusMinutes(30), "x:0.2", "y:0.2");
        addState("g1", now.plusMinutes(40), "bri:200");
        addState("g1", now.plusMinutes(50), "bri:250");
        addState("g1", now.plusMinutes(60), "on:false");
        addState("g1", now.plusMinutes(70), "ct:200");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusMinutes(40)),
                expectedRunnable(now.plusMinutes(40), now.plusMinutes(50)),
                expectedRunnable(now.plusMinutes(50), now.plusMinutes(60)),
                expectedRunnable(now.plusMinutes(60), now.plusMinutes(70)),
            expectedRunnable(now.plusMinutes(70), now.plusDays(1))
        );

        // state 1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100).ct(500)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100).ct(500));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        ScheduledRunnable lightOn1 = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(lightOn1,
                expectedGroupPutCall(1).bri(100).ct(500)
        );

        // state 2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).bri(150) // only bri
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150).ct(500)); // with additional ct from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(1).plusMinutes(20)) // next day
        );

        ScheduledRunnable lightOn2 = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusMinutes(20))
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(lightOn2,
                expectedGroupPutCall(1).bri(150).ct(500) // light on also uses full picture
        );

        // state 3

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(1).x(0.4).y(0.5) // only xy
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150).x(0.4).y(0.5)); // with additional bri but ignored ct from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(1).plusMinutes(30)) // next day
        );

        // state 4

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3),
                expectedGroupPutCall(1).x(0.2).y(0.2) // only xy
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150).x(0.2).y(0.2));

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusMinutes(40)) // next day
        );

        // state 5

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(4),
                expectedGroupPutCall(1).bri(200) // only bri
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(200).x(0.2).y(0.2)); // with additional xy from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(40), initialNow.plusDays(1).plusMinutes(50)) // next day
        );

        // state 6

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(5),
                expectedGroupPutCall(1).bri(250) // only bri
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(250).x(0.2).y(0.2)); // with additional x/y from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(50), initialNow.plusDays(1).plusMinutes(60)) // next day
        );

        // state 7

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(6),
                expectedGroupPutCall(1).on(false) // only on
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).on(false)); // does not use any other properties

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(60), initialNow.plusDays(1).plusMinutes(70)) // next day
        );

        // state 8

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(7),
                expectedGroupPutCall(1).ct(200) // only ct
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).ct(200).bri(250)); // skips "off"

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(70), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_considersOtherOverlappingSchedules_buildsFullPicture() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultLightCapabilities(5);
        mockDefaultLightCapabilities(6);
        mockGroupLightsForId(1, 5, 6, 7);
        addState("5", now, "bri:200");
        addState("6", now, "x:0.2", "y:0.2");
        addState("g1", now, "bri:100", "ct:500");
        addState("5", now.plusMinutes(20), "bri:250");
        addState("6", now.plusMinutes(20), "x:0.4", "y:0.3");
        addState("g1", now.plusMinutes(20), "bri:150", "ct:500");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(20)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(20)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(100).ct(500)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(200), expectedPutCall(6).x(0.2).y(0.2), expectedPutCall(7).bri(100).ct(500));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(20)); // next day

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(3),
                expectedGroupPutCall(1).bri(150).ct(500)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(250), expectedPutCall(6).x(0.4).y(0.3), expectedPutCall(7).bri(150).ct(500));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_considersOtherOverlappingSchedules_otherGroups_andIndividualLights_onlyConsidersGroupsSmallerInSize_buildsFullPicture() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7, 8);
        mockGroupLightsForId(2, 5, 6, 7);
        mockGroupLightsForId(3, 5);
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addKnownLightIdsWithDefaultCapabilities(7);
        addState("g1", now, "bri:100");
        addState("g2", now, "bri:120");
        addState("g3", now, "bri:130");
        addState(7, now, "ct:300");
        addState("g1", now.plusMinutes(10), "bri:111");
        addState("g2", now.plusMinutes(10), "on:false");
        addState("g3", now.plusMinutes(10), "bri:133");
        addState(7, now.plusMinutes(10), "ct:400");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // g2
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)), // g3
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)), // 7
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(2), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(2), now.plusDays(1))
        );

        // g1 bri:100 -> other groups: 2, 3; both are smaller; uses overlapping 5 from 3, 6 from 2, 7 from individual

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300), expectedPutCall(8).bri(100));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g2 bri:120 -> other groups: 1, 3; only 3 and 7 as individual are smaller

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(1),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300), expectedPutCall(8).bri(100));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(130));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1 bri:111; second state

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(4),
                expectedGroupPutCall(1).bri(111)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400), expectedPutCall(8).bri(111));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(133));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // g2 on:false; second state

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(5),
                expectedGroupPutCall(2).on(false)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400), expectedPutCall(8).bri(111));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(133));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_childGroupHasNoSchedule_createsScene() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        addState("g1", now, "bri:110");
        addState("g1", now.plusMinutes(10), "bri:210");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(110)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(110), expectedPutCall(6).bri(110), expectedPutCall(7).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(110), expectedPutCall(6).bri(110));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_parentGroupsHaveNoSchedule_createsIntermediateScenes() {
        enableSceneSync();

        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 5);
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        addState("g3", now, "bri:130");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(3).bri(130)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(130));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(130));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(3).bri(230)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(230));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(230));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(230));

        ensureRunnable(initialNow.plusMinutes(10).plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_individualLights_withOverlappingParentGroups_haveNoSchedule_createsIntermediateScenes() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        mockDefaultLightCapabilities(5);
        addState("5", now, "bri:110");
        addState("5", now.plusMinutes(10), "bri:120");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(5).bri(110)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(110));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_nonOverlappingGroupStates_butBelongToSameRoom_considersAllLightsForRoom() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 7);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1, 3);
        addState("g2", now, "bri:120");
        addState("g3", now, "bri:130");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1))
        );

        // g2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).bri(130));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(120), expectedPutCall(6).bri(120));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // g3

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(3).bri(130)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).bri(130));
        assertSceneUpdate("/groups/3", expectedPutCall(7).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_createsIntermediateParentScenes_parentScenesWithNoScheduleAlsoUseBiggerGroupsForFullPicture() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(4);
        mockGroupLightsForId(1, 5, 6, 7, 8); // uses 5 from g4, 6 and 7 from g2
        mockGroupLightsForId(2, 5, 6, 7);
        mockGroupLightsForId(3, 5, 6); // uses 5 from g4 and 6 from g2 (!)
        mockGroupLightsForId(4, 5);
        mockAssignedGroups(5, 1, 2, 3, 4);
        mockAssignedGroups(6, 1, 2, 3);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addState("g2", now, "bri:120");
        addState("g4", now, "bri:140");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState("g4", now.plusMinutes(10), "bri:240");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(10), now.plusDays(1))
        );

        // g2: update for g1 uses both g2 and g4, while defaulting to off

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120));
        assertSceneUpdate("/groups/4", expectedPutCall(5).bri(140));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g4: update for g3 (no explicit schedule) also uses bigger parent g2 (with schedule)

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(4).bri(140)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120));
        assertSceneUpdate("/groups/4", expectedPutCall(5).bri(140));

        ensureRunnable(initialNow.plusSeconds(1).plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_doesNotUseParentIfExplicitScheduleDefined() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7, 8);
        mockGroupLightsForId(2, 5, 6, 7);
        mockGroupLightsForId(3, 5, 6);
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2, 3);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addState("g1", now, "bri:110");
        addState("g2", now, "bri:120");
        addState("g3", now, "bri:130");
        addState("g1", now.plusMinutes(10), "bri:210");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(2).plusMinutes(10), now.plusDays(1))
        );

        // g1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(110)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g3

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(3).bri(130)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(2), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_singleSchedule_childSceneActivated_usesParentState_doesNotResetOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6);
        mockGroupLightsForId(2, 5);
        mockGroupLightsForId(3, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 3);
        addState("g1", now, "bri:120");
        addState("g1", now.plusMinutes(10), "bri:220");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // g1.1

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(120)
        );

        ScheduledRunnable nextDay1 = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1.2 -> detects override

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1)); // override detected

        ScheduledRunnable nextDay2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // Activate synced scene for g2 -> only updates light 5

        simulateSyncedSceneActivated("/groups/2", "/lights/5");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable2);

        // next day, still detected as overridden

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(220),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertPutCalls(nextDay1); // still overridden

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // activate synced scene for group 1 -> resets override

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable3 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(10))
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable3);

        // Applied normally again:

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120)
        );
        advanceTimeAndRunAndAssertPutCalls(nextDay2,
                expectedGroupPutCall(1).bri(220)
        );

        ensureRunnable(initialNow.plusDays(2).plusMinutes(10), initialNow.plusDays(3)); // next day
    }

    @Test
    void sceneSync_singleSchedule_withInterpolation_childSceneActivated_usesParentState_resetsOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6);
        mockGroupLightsForId(2, 5);
        mockGroupLightsForId(3, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 3);
        addState("g1", now.plusMinutes(5), "bri:120", "tr-before:5min");
        addState("g1", now.plusMinutes(10), "bri:220", "tr-before:5min");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        // g1.1

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(220), // interpolated
                expectedGroupPutCall(1).bri(120).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay1 = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(5)); // next day

        // g1.2 -> detects override

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1)); // override detected

        ScheduledRunnable nextDay2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(5), initialNow.plusDays(2)); // next day

        // Activate synced scene for g2 -> re-triggers the parent state, rests override

        simulateSyncedSceneActivated("/groups/2", "/lights/5");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable2,
                expectedGroupPutCall(1).bri(220).transitionTime(tr("5min"))
        );

        // next day, still detected as overridden

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(220),
                expectedState().id("/lights/6").brightness(220)
        );
        advanceTimeAndRunAndAssertPutCalls(nextDay1,
                expectedGroupPutCall(1).bri(120).transitionTime(tr("5min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // activate synced scene for group 1

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable3 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(5))
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable3,
                expectedGroupPutCall(1).bri(120).transitionTime(tr("5min"))
        );

        // Next day 2

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120)
        );
        advanceTimeAndRunAndAssertPutCalls(nextDay2,
                expectedGroupPutCall(1).bri(220).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(2).plusMinutes(5), initialNow.plusDays(3)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_childSceneActivated_usesParentState_doesNotResetOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6);
        mockGroupLightsForId(2, 5);
        mockGroupLightsForId(3, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 3);
        addState("g1", now, "bri:120");
        addState("g3", now, "bri:130");
        addState("g1", now.plusMinutes(10), "bri:220");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1))
        );

        // g1.1

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(120)
        );

        ScheduledRunnable nextDayG1_1 = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g3.1

        manualOverrideTracker.onManuallyOverridden("/groups/3"); // simulate override

        advanceTimeAndRunAndAssertPutCalls(runnables.get(1));

        ScheduledRunnable nextDayG3_1 = ensureRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1.2 -> also detects override

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertPutCalls(runnables.get(2)); // override detected

        ScheduledRunnable nextDayG1_2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // Activate synced scene for g2 -> only updates light 5

        simulateSyncedSceneActivated("/groups/2", "/lights/5");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable2);

        // g3.2 -> still overridden

        setGroupStateResponses(3,
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // still overridden
        );
        advanceTimeAndRunAndAssertPutCalls(runnables.get(3));

        ScheduledRunnable nextDayG3_2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(10).plusSeconds(1), initialNow.plusDays(2));

        // next day G1_1, still detected as overridden

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(230),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertPutCalls(nextDayG1_1); // still overridden

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // activate synced scene for group 1 -> resets override

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable3 = ensureScheduledStates(
                // for g1
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(10)),
                // for g3
                expectedRunnable(now.plusSeconds(1), initialNow.plusMinutes(10)), // already ended
                expectedRunnable(now.plusSeconds(1), initialNow.plusDays(1)) // already ended
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable3);

        // Applied normally again:

        // G3_1

        setGroupStateResponses(3,
                expectedState().id("/lights/6").brightness(130)
        );
        advanceTimeAndRunAndAssertPutCalls(nextDayG3_1,
                expectedGroupPutCall(3).bri(130)
        );

        ensureRunnable(initialNow.plusDays(2).plusSeconds(1), initialNow.plusDays(2).plusMinutes(10)); // next day

        // G1_2

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(130)
        );
        advanceTimeAndRunAndAssertPutCalls(nextDayG1_2,
                expectedGroupPutCall(1).bri(220)
        );

        ensureRunnable(initialNow.plusDays(2).plusMinutes(10), initialNow.plusDays(3)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_offStateIsCorrectlyReCheckedAfterSyncedSceneTurnedOnContainingOffStates_bugCase() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7, 8);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 7, 8);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1, 3);
        mockAssignedGroups(8, 1, 3);
        addState("g2", now, "bri:120");
        addState("g3", now, "on:false");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // g2.1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).on(false), expectedPutCall(8).on(false));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(120), expectedPutCall(6).bri(120));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g3.1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(3).on(false)
        );
        simulateLightOffEvent("/groups/3");

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).on(false), expectedPutCall(8).on(false));
        assertSceneUpdate("/groups/3",
                expectedPutCall(7).on(false), expectedPutCall(8).on(false));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // Activate synced scene for g1 -> only applies g2 state again

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6", "/lights/7", "/lights/8");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusMinutes(10))).getFirst();
        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable); // no further update needed, no interpolations

        // g2.2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(2).bri(220)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(220), expectedPutCall(6).bri(220), expectedPutCall(7).bri(230), expectedPutCall(8).bri(230));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(220), expectedPutCall(6).bri(220));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // g3.2: bug case -> synced scene activation triggers turnOn events internally; we need to still recheck off state to not apply states where the lights are off

        mockIsGroupOff(3, true);
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3)); // no update, re-checks off state by api

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(220), expectedPutCall(6).bri(220), expectedPutCall(7).bri(230), expectedPutCall(8).bri(230));
        assertSceneUpdate("/groups/3",
                expectedPutCall(7).bri(230), expectedPutCall(8).bri(230));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_interpolate_withDayCrossover_correctlyFindsActivePutCall() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        setCurrentAndInitialTimeTo(now.withHour(13)); // 13:00
        addState(1, "12:00", "bri:100");
        addState(1, "01:00", "bri:200", "interpolate:true"); // 01:00 -> 12:00

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)),
                expectedRunnable(now.plusDays(1).minusHours(1), now.plusDays(1).minusHours(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(108),
                expectedPutCall(1).bri(113).transitionTime(tr("40min"))
        );

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(108));

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(40), initialNow.plusDays(1).minusHours(1)), // next split call
                expectedRunnable(now.plusMinutes(61), now.plusDays(1).minusHours(1)), // next scene sync
                expectedRunnable(initialNow.plusDays(1).minusHours(1), initialNow.plusDays(2).minusHours(1)) // next day
        );
    }

    @Test
    void sceneSync_withDayCrossover_startedAfterMidnight_correctSync() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        addState(1, "12:00", "bri:100");
        addState(1, "22:00", "bri:200");

        setCurrentAndInitialTimeTo(now.plusDays(1).withHour(3)); // 03:00

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusHours(9)),
                expectedRunnable(now.plusHours(9), now.plusHours(19))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(200)
        );

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(200));

        ensureScheduledStates(
                expectedRunnable(now.plusHours(19), now.plusDays(1).plusHours(9)) // next day
        );
    }

    @Test
    void sceneSync_withAdditionalAreas_considersThem_ignoresDuplicate() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        when(mockedHueApi.getAdditionalAreas(List.of("/lights/1")))
                .thenReturn(List.of(
                        new GroupInfo("/groups/7", List.of("/lights/1", "/lights/3")),
                        new GroupInfo("/groups/5", List.of("/lights/1")) // duplicate
                ));
        addState(1, "00:00", "bri:100");
        addState(1, "12:00", "bri:200");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(100));
        assertSceneUpdate("/groups/7", expectedPutCall(1).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusHours(12)) // next day
        );
    }

    @Test
    void scheduling_overlappingGroups_ofDifferentSize_automaticallyOffsetsStatesBasedOnTheNumberOfBiggerGroups() {
        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 5, 6); // has no states, ignored in offset calculation
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2, 3);
        mockAssignedGroups(7, 1);
        addKnownLightIdsWithDefaultCapabilities(6);
        addKnownLightIdsWithDefaultCapabilities(7);
        addState("g1", now, "bri:100");
        addState("g2", now, "bri:120");
        addState(6, now, "bri:130");
        addState(7, now, "bri:140");
        addState("g1", now.plusMinutes(10), "bri:200");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState(6, now.plusMinutes(10), "bri:230");
        addState(7, now.plusMinutes(10), "bri:240");

        startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // g2
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // 7
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)), // 6
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(2), now.plusDays(1))
        );
    }

    @Test
    void scheduling_overlappingGroups_wouldUseOffset_apiCallFails_usesFallbackValueOfZero() {
        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        addKnownLightIdsWithDefaultCapabilities(6);
        addKnownLightIdsWithDefaultCapabilities(7);
        addState("g1", now, "bri:100");
        addState("g2", now, "bri:120");
        addState("g1", now.plusMinutes(10), "bri:200");
        addState("g2", now.plusMinutes(10), "bri:220");

        when(mockedHueApi.getAssignedGroups("/lights/6")).thenThrow(new ApiFailure("Failure"));

        startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1
                expectedRunnable(now, now.plusMinutes(10)), // g2
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
    }

    // todo: test with gaps or with day scheduling, meaning we want to test cases where state definitions exists, but not for the current time

    @Test
    void sceneSync_apiThrowsError_doesNotSkipSchedule_retriesSync() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5);
        addState("g1", now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // Let scene sync fail -> schedules a retry
        mockSceneSyncFailure("/groups/1");

        // Schedule updates group normally
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(syncFailureRetryInMinutes), now.plusDays(1)), // sync retry
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
        ScheduledRunnable retrySync = followUpRunnables.getFirst();

        resetMockedApi();
        mockGroupLightsForId(1, 5);

        setCurrentTimeTo(retrySync);
        retrySync.run();

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(100));
    }

    @Test
    void sceneSync_apiThrowsError_interpolate_retries() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 5, 6);
        addState("g2", now, "bri:100");
        addState("g2", now.plusMinutes(10), "bri:150", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        mockSceneSyncFailure("/groups/2");

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(100),
                expectedGroupPutCall(2).bri(150).transitionTime(tr("10min"))
        );
        expectedSceneUpdates++;

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(syncFailureRetryInMinutes), now.plusDays(1)), // scene sync retry
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_lightState_ignored() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_groupState_notEnabled_ignored() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6);
        addState("g1", now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void clearCachesAndReSyncScenes_syncsCurrentActiveStates_ignoresRepeatedSyncsForInterpolations() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 11);
        mockGroupLightsForId(2, 22);
        mockGroupLightsForId(3, 33);
        addState("g1", now, "bri:110");
        addState("g2", now, "bri:120");
        addState("g3", now, "on:false");
        addState("g1", now.plusMinutes(10), "bri:210");
        addState("g2", now.plusMinutes(10), "bri:220", "tr-before:5min");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        scheduler.clearCachesAndReSyncScenes();

        assertSceneUpdate("/groups/1", expectedPutCall(11).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(22).bri(120));
        assertSceneUpdate("/groups/3", expectedPutCall(33).on(false));

        advanceCurrentTime(Duration.ofMinutes(7));

        scheduler.clearCachesAndReSyncScenes();

        assertSceneUpdate("/groups/1", expectedPutCall(11).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(22).bri(160)); // interpolated value
        assertSceneUpdate("/groups/3", expectedPutCall(33).on(false));

        advanceCurrentTime(Duration.ofMinutes(3));

        scheduler.clearCachesAndReSyncScenes();

        assertSceneUpdate("/groups/1", expectedPutCall(11).bri(210));
        assertSceneUpdate("/groups/2", expectedPutCall(22).bri(220));
        assertSceneUpdate("/groups/3", expectedPutCall(33).bri(230));

        verify(mockedHueApi, times(3)).clearCaches();
    }

    @Test
    void clearCachesAndReSyncScenes_sceneSyncNotEnabled_justClearsCaches() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 11);
        addState("g1", now, "bri:110");
        addState("g1", now.plusMinutes(10), "bri:210");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        scheduler.clearCachesAndReSyncScenes();

        verify(mockedHueApi).clearCaches();
    }

    // todo: what about if the schedule contains on:true, should we then still not apply it?

    @Test
    void requireSceneActivation_ignoresLightOnEvents_exceptForSyncedSceneActivation() {
        requireSceneActivation();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.plusMinutes(5), "bri:100", "tr-before:5min");
        addState(1, now.plusMinutes(10), "bri:110", "tr-before:5min");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst());

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)) // next day
        ).getFirst();

        // case 1: turned on normally -> ignored

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/1", expectedPowerOnEnd(now.plusMinutes(5))).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable); // no update performed

        // case 2: turned on through normal scene -> ignored

        simulateSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable normalSceneRunnable = simulateLightOnEvent("/lights/1", expectedPowerOnEnd(now.plusMinutes(5))).getFirst();

        advanceTimeAndRunAndAssertPutCalls(normalSceneRunnable); // no update performed

        // case 3: turned on through synced scene -> apply

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(expectedPowerOnEnd(now.plusMinutes(5))).getFirst();

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("5min"))
        );

        // wait until after ignore window -> light turned off and on again. Is ignored again

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds + 1));

        simulateLightOffEvent("/lights/1"); // simulate light turned off

        ScheduledRunnable powerOnRunnable2 = simulateLightOnEvent("/lights/1", expectedPowerOnEnd(initialNow.plusMinutes(5))).getFirst();

        runAndAssertPutCalls(powerOnRunnable2); // ignored again

        // also ignores second state

        advanceTimeAndRunAndAssertPutCalls(runnables.get(1));

        ScheduledRunnable secondNextDay = ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(5), initialNow.plusDays(2)) // next day
        ).getFirst();

        // synced scene turned on again -> applies second state and allows first next day to follow

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable2,
                expectedPutCall(1).bri(110).transitionTime(tr("5min"))
        );

        // first next day

        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).bri(100).transitionTime(tr("5min"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)) // next day
        );
    }

    @Test
    void requireSceneActivation_withUserModificationTracking_correctlyDetectsOverrides_stopsApplying() {
        requireSceneActivation();
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst());

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();

        // synced scene -> tracks first state apply via scene, ignoring any light state

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(expectedPowerOnEnd(now.plusMinutes(10))).getFirst();

        setLightStateResponse(1, expectedState().brightness(200)); // ignored
        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable); // no further update needed, scene already set the state; tracks last seen state

        // light has been modified before second state -> triggers override based on last seen state tracked via scene

        setLightStateResponse(1, expectedState().brightness(100 + BRIGHTNESS_OVERRIDE_THRESHOLD));
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1));

        ScheduledRunnable secondNextDay = ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        ).getFirst();

        // first state next day still skipped because of override

        advanceTimeAndRunAndAssertPutCalls(firstNextDay); // still overridden

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // activate synced scene again -> applies firstNextDay state, resetting override

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(10))
        ).get(2);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable2); // no additional update needed, no interpolation

        // secondNextDay applied normally

        setLightStateResponse(1, expectedState().brightness(100)); // no manual override this time
        advanceTimeAndRunAndAssertPutCalls(secondNextDay,
                expectedPutCall(1).bri(110)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2).plusMinutes(10), initialNow.plusDays(3)) // next day
        );
    }

    @Test
    void requireSceneActivation_withForceProperty_stillApplied() {
        requireSceneActivation();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "force:true");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(), expectedPutCall(1).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.get(1)); // no update, since no force

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void requireSceneActivation_onPhysicalOn_afterSyncedSceneTurnOn_doesNotPreventFurtherStatesToBeScheduled() {
        requireSceneActivation();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6);
        when(mockedHueApi.getAffectedIdsByDevice("/device/789")).thenReturn(List.of("/groups/1", "/lights/6"));
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst());

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // synced scene -> applies first state, ignoring any light state

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(
                expectedPowerOnEnd(now.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable); // no additional update needed, no interpolation

        // simulate group light to be turned on physically -> keeps synced scene turn on

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds)); // after scene activation window

        scheduler.getHueEventListener().onPhysicalOn("/device/789");

        ScheduledRunnable physicalPowerOnRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(physicalPowerOnRunnable,
                expectedGroupPutCall(1).bri(100)
        );

        // still allows second state to be scheduled

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).bri(110)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void requireSceneActivation_onPhysicalOn_noSyncedSceneTurnedOnBefore_notApplied() {
        requireSceneActivation();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6);
        when(mockedHueApi.getAffectedIdsByDevice("/device/789")).thenReturn(List.of("/groups/1", "/lights/6"));
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst());

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate group light to be turned on physically -> not applied

        scheduler.getHueEventListener().onPhysicalOn("/device/789");

        ScheduledRunnable physicalPowerOnRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(physicalPowerOnRunnable); // ignored

        // second state also ignored

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // ignored

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    // todo: Fall berücksichtigen, falls es mehr als eine Szene gibt mit dem gleichen Namen innerhalb der Gruppe. Weil das durchaus der Fall sein kann.

    @Test
    void sceneControl_init_loadsLightPropertiesForGroupByName() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        addState("g1", now, "scene:TestScene");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_init_withOnProperty_addsOnToIndividualLights_unlessTheyAreOff() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .on(false));
        addState("g1", now, "on:true", "scene:TestScene");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).on(true).bri(100).ct(20),
                expectedPutCall(5).on(false)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_init_withOffProperty_exception() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20));

        assertThrows(InvalidConfigurationLine.class, () -> addState("g1", now, "on:false", "scene:TestScene"));
    }

    @Test
    void sceneControl_init_withCt_exception() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
        mockSceneLightStates(1, "TestScene", ScheduledLightState.builder().id("/lights/4").bri(100));


        assertThrows(InvalidConfigurationLine.class, () -> addState("g1", now, "ct:250", "scene:TestScene"));
    }

    @Test
    void sceneControl_init_withX_exception() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
        mockSceneLightStates(1, "TestScene", ScheduledLightState.builder().id("/lights/4").bri(100));

        assertThrows(InvalidConfigurationLine.class, () -> addState("g1", now, "x:0.5", "scene:TestScene"));
    }

    @Test
    void sceneControl_init_withY_exception() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
        mockSceneLightStates(1, "TestScene", ScheduledLightState.builder().id("/lights/4").bri(100));

        assertThrows(InvalidConfigurationLine.class, () -> addState("g1", now, "y:0.5", "scene:TestScene"));
    }

    @Test
    void sceneControl_init_withEffect_exception() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
        mockSceneLightStates(1, "TestScene", ScheduledLightState.builder().id("/lights/4").bri(100));

        assertThrows(InvalidConfigurationLine.class, () -> addState("g1", now, "effect:effect", "scene:TestScene"));
    }

    @Test
    void sceneControl_init_withBrightnessOverride_appliesProportionalScaling() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)  // bright light
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)  // mid brightness
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:127");  // ~50%

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // With scaleFactor = 127/254 = 0.5
        // Light 4: 200 * 0.5 = 100
        // Light 5: 100 * 0.5 = 50
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withBrightnessOverride_preservesRelativeProportions() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(254)  // max brightness
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(64)   // 1/4 of max
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // With scaleFactor = 100/254 ≈ 0.394
        // Light 4: 254 * 0.394 = 100
        // Light 5: 64 * 0.394 = 25 (maintains 1:4 ratio)
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(25).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withMaxBrightnessOverride_leavesSceneUnchanged() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:254");  // 100%

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // With scaleFactor = 254/254 = 1.0 (no change)
        // Light 4: 200 * 1.0 = 200
        // Light 5: 100 * 1.0 = 100
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(200).ct(20),
                expectedPutCall(5).bri(100).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withLowBrightnessOverride_dimsProportionally() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5, 6);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(40),
                ScheduledLightState.builder()
                                   .id("/lights/6")
                                   .bri(50)
                                   .ct(60));
        addState("g1", now, "scene:TestScene", "bri:25");  // ~10%

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // With scaleFactor = 25/254 ≈ 0.098
        // Light 4: 200 * 0.098 = 20
        // Light 5: 100 * 0.098 = 10
        // Light 6: 50 * 0.098 = 5
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(20).ct(20),
                expectedPutCall(5).bri(10).ct(40),
                expectedPutCall(6).bri(5).ct(60)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withBrightnessOverride_enforcesMinimumBrightness() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(10)   // very dim
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(5)    // extremely dim
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:25");  // ~10%

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // With scaleFactor = 25/254 ≈ 0.098
        // Light 4: 10 * 0.098 = 1 (rounded, minimum enforced)
        // Light 5: 5 * 0.098 = 0.49 → 1 (minimum enforced)
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(1).ct(20),
                expectedPutCall(5).bri(1).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withBrightnessOverride_canExceedOriginalBrightness() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:300");  // >100%

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // With scaleFactor = 300/254 ≈ 1.18
        // Light 4: 100 * 1.18 = 118
        // Light 5: 50 * 1.18 = 59
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(118).ct(20),
                expectedPutCall(5).bri(59).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withBrightnessOverride_capsAtMaximum() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(230)
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:300");  // >100%

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // With scaleFactor = 300/254 ≈ 1.18
        // Light 4: 200 * 1.18 = 236
        // Light 5: 230 * 1.18 = 271 → 254 (capped at maximum)
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(236).ct(20),
                expectedPutCall(5).bri(254).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withBrightnessOverride_ignoresMissingBrightnessInScene() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   // no bri property
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:150");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // Light 4 gets scaled, Light 5 gets the override brightness directly
        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(59).ct(20),  // 100 * (150/254) ≈ 59
                expectedPutCall(5).ct(40) // ignored
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_withBrightnessOverride_over100Percent_cappedAtMax() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(40));
        addState("g1", now, "scene:TestScene", "bri:200%");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(254).ct(20),
                expectedPutCall(5).bri(201).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );
    }

    @Test
    void sceneControl_init_sceneWithSingleLight_treatedAsNormalGroup() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(500));
        addState("g1", now, "scene:TestScene");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100).ct(500)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    // todo: add test for manual override detection an scenes -> this might not yet work as expected

    @Test
    void sceneControl_sceneThenGroup_sceneWithGradient_calculatesFullPicture_correctPutCalls() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.123, 0.456),
                                                             Pair.of(0.234, 0.567)
                                                     ))
                                                     .mode("GRADIENT_MODE_1")
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(250));
        addState("g1", now, "scene:TestScene");
        addState("g1", now.plusMinutes(10), "bri:50", "ct:200");


        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(50).gradient(Gradient.builder()
                                                            .points(List.of(
                                                                    Pair.of(0.123, 0.456),
                                                                    Pair.of(0.234, 0.567)
                                                            ))
                                                            .mode("GRADIENT_MODE_1")
                                                            .build()),
                expectedPutCall(5).bri(100).ct(250)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_sceneThenScene_oneWithGradient_calculatesFullPicture_correctPutCalls() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(200));
        mockSceneLightStates(1, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.123, 0.456),
                                                             Pair.of(0.234, 0.567)
                                                     ))
                                                     .mode("GRADIENT_MODE_1")
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(250));
        addState("g1", now, "scene:TestScene1");
        addState("g1", now.plusMinutes(10), "scene:TestScene2");


        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(100).gradient(Gradient.builder()
                                                             .points(List.of(
                                                                     Pair.of(0.123, 0.456),
                                                                     Pair.of(0.234, 0.567)
                                                             ))
                                                             .mode("GRADIENT_MODE_1")
                                                             .build()),
                expectedPutCall(5).bri(200).ct(250)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_sceneThenGroup_calculatesFullPicture_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .ct(400));
        addState("g2", now, "scene:TestScene");
        addState("g2", now.plusMinutes(10), "bri:50", "ct:200");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 2,
                expectedPutCall(4).bri(50).ct(300), // bri taken from group
                expectedPutCall(5).bri(50).ct(400)  // bri taken from group
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_groupThenScene_noFullPicturePossible_keepsOwnProperties_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150)
                                   .ct(400));
        addState("g2", now, "ct:200");
        addState("g2", now.plusMinutes(10), "scene:TestScene");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).ct(200)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_sceneThenScene_calculatesFullPicture_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .ct(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .ct(200));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150)
                                   .ct(400));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 2,
                expectedPutCall(4).bri(100).ct(100),
                expectedPutCall(5).bri(150).ct(200)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_groupToScene_sameProperties_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "AnotherTestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150)
                                   .ct(400));
        addState("g2", now, "bri:50", "ct:200");
        addState("g2", now.plusMinutes(10), "scene:AnotherTestScene", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        ScheduledRunnable runnable = runnables.getFirst();
        setCurrentTimeTo(runnable);
        runnable.run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(50).ct(200),
                expectedPutCall(5).bri(50).ct(200)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).bri(100).ct(300).transitionTime(tr("10min")),
                expectedPutCall(5).bri(150).ct(400).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // after 5 minutes power on -> interpolates from simple group state to multiple put calls

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/2",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        powerOnRunnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(75).ct(250),  // interpolated
                expectedPutCall(5).bri(100).ct(300)  // interpolated
        );
        assertScenePutCalls(2,
                expectedPutCall(4).bri(100).ct(300).transitionTime(tr("5min")),
                expectedPutCall(5).bri(150).ct(400).transitionTime(tr("5min"))
        );

        assertAllScenePutCallsAsserted();
    }

    @Test
    void sceneControl_interpolate_groupToScene_differentGamut_performsCorrection() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5, 6);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .x(0.3)
                                   .y(0.18)
                                   .gamut(GAMUT_A),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.3, 0.18),
                                                             Pair.of(0.3, 0.18)
                                                     ))
                                                     .build())
                                   .gamut(GAMUT_A),
                ScheduledLightState.builder()
                                   .id("/lights/6")
                                   .x(0.3)
                                   .y(0.18)
                                   .gamut(GAMUT_C)
        );
        addState("g2", now, "x:0.18", "y:0.6");
        addState("g2", now.plusMinutes(10), "scene:TestScene", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        ScheduledRunnable runnable = runnables.getFirst();
        setCurrentTimeTo(runnable);
        runnable.run();

        assertScenePutCalls(2,
                expectedPutCall(4).x(0.2013).y(0.5974), // gamut corrected for gamut A
                expectedPutCall(5).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2013, 0.5974),
                                                            Pair.of(0.2013, 0.5974)
                                                    )).build()), // gamut corrected for gamut A
                expectedPutCall(6).x(0.18).y(0.6)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).x(0.3).y(0.18).transitionTime(tr("10min")),
                expectedPutCall(5).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.3, 0.18),
                                                            Pair.of(0.3, 0.18)
                                                    )).build()).transitionTime(tr("10min")),
                expectedPutCall(6).x(0.3).y(0.18).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_sceneToGroup_differentGamutForGroup_performsCorrection() {
        mockGroupCapabilities(2, LightCapabilities.builder()
                                                  .colorGamut(GAMUT_A)
                                                  .capabilities(EnumSet.of(Capability.COLOR))
                                                  .build());
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .x(0.18)
                                   .y(0.62)
                                   .gamut(GAMUT_C),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .x(0.18)
                                   .y(0.62)
                                   .gamut(GAMUT_C)
        );
        addState("g2", now, "scene:TestScene");
        addState("g2", now.plusMinutes(10), "x:0.3", "y:0.2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        ScheduledRunnable runnable = runnables.getFirst();
        setCurrentTimeTo(runnable);
        runnable.run();

        assertScenePutCalls(2,
                expectedPutCall(4).x(0.2037).y(0.6171), // gamut corrected for gamut A
                expectedPutCall(5).x(0.2037).y(0.6171) // gamut corrected for gamut A
        );
        assertAllScenePutCallsAsserted();

        assertGroupPutCalls(
                expectedGroupPutCall(2).x(0.3).y(0.2).transitionTime(tr("10min"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_groupToScene_sceneHasOffLights_interpolatesBrightness_alsoForOffState_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .on(false), // treated as bri:0
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .x(0.6024)
                                   .y(0.3433));
        addState("g2", now, "bri:40", "x:0.6024", "y:0.3433", "tr:2s");
        addState("g2", now.plusMinutes(10), "scene:TestScene", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        ScheduledRunnable runnable = runnables.getFirst();
        setCurrentTimeTo(runnable);
        runnable.run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(40).x(0.6024).y(0.3433).transitionTime(tr("2s")),
                expectedPutCall(5).bri(40).x(0.6024).y(0.3433).transitionTime(tr("2s"))
        );

        assertScenePutCalls(2,
                expectedPutCall(4).on(false).transitionTime(tr("10min")),
                expectedPutCall(5).bri(100).x(0.6024).y(0.3433).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // after 5 minutes power on -> interpolates from scene group state

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/2",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        powerOnRunnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(20).x(0.6024).y(0.3433).transitionTime(tr("2s")),  // interpolated brightness (implicit target = 0)
                expectedPutCall(5).bri(70).x(0.6024).y(0.3433).transitionTime(tr("2s"))  // interpolated
        );
        assertScenePutCalls(2,
                expectedPutCall(4).on(false).transitionTime(tr("5min")),
                expectedPutCall(5).bri(100).x(0.6024).y(0.3433).transitionTime(tr("5min"))
        );

        assertAllScenePutCallsAsserted();
    }

    @Test
    void sceneControl_interpolate_sceneToGroup_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150)
                                   .ct(400));
        addState("g2", now, "scene:TestScene");
        addState("g2", now.plusMinutes(10), "bri:50", "ct:200", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        ScheduledRunnable runnable = runnables.getFirst();
        setCurrentTimeTo(runnable);
        runnable.run();

        // previous state
        assertScenePutCalls(2,
                expectedPutCall(4).bri(100).ct(300),
                expectedPutCall(5).bri(150).ct(400)
        );
        assertAllScenePutCallsAsserted();

        assertGroupPutCalls(
                expectedGroupPutCall(2).bri(50).ct(200).transitionTime(tr("10min"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // after 5 minutes power on -> interpolates from scene group state

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/2",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        powerOnRunnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(75).ct(250),  // interpolated
                expectedPutCall(5).bri(100).ct(300)  // interpolated
        );
        assertGroupPutCalls(
                expectedGroupPutCall(2).bri(50).ct(200).transitionTime(tr("5min"))
        );

        assertAllScenePutCallsAsserted();
    }

    @Test
    void sceneControl_interpolate_sceneToScene_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150)
                                   .ct(400));
        mockSceneLightStates(2, "AnotherTestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(400),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(250)
                                   .ct(500));
        addState("g2", now, "scene:TestScene");
        addState("g2", now.plusMinutes(10), "scene:AnotherTestScene", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        ScheduledRunnable runnable = runnables.getFirst();
        setCurrentTimeTo(runnable);
        runnable.run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(100).ct(300),
                expectedPutCall(5).bri(150).ct(400)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).bri(200).ct(400).transitionTime(tr("10min")),
                expectedPutCall(5).bri(250).ct(500).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // after 5 minutes power on -> interpolates from scene group state

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/2",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        powerOnRunnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(150).ct(350),  // interpolated
                expectedPutCall(5).bri(200).ct(450)  // interpolated
        );
        assertScenePutCalls(2,
                expectedPutCall(4).bri(200).ct(400).transitionTime(tr("5min")),
                expectedPutCall(5).bri(250).ct(500).transitionTime(tr("5min"))
        );

        assertAllScenePutCallsAsserted();
    }

    @Test
    void sceneControl_interpolate_sceneToScene_withGradients_sameNumberOfPoints_correctPutCalls() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.123, 0.456),
                                                             Pair.of(0.234, 0.567)
                                                     ))
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.345, 0.678),
                                                             Pair.of(0.456, 0.789)
                                                     ))
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(250));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        ScheduledRunnable runnable = runnables.getFirst();
        setCurrentTimeTo(runnable);
        runnable.run();

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.1637, 0.455),
                                                            Pair.of(0.234, 0.567)
                                                    ))
                                                    .build()),
                expectedPutCall(5).bri(150)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.345, 0.678),
                                                            Pair.of(0.456, 0.789)
                                                    ))
                                                    .build())
                                  .transitionTime(tr("10min")),
                expectedPutCall(5).bri(250).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // after 5 minutes power on -> interpolates from scene group state

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/2",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        powerOnRunnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2159, 0.5488), // interpolated
                                                            Pair.of(0.2716, 0.5865)  // interpolated
                                                    ))
                                                    .build()),
                expectedPutCall(5).bri(200) // interpolated
        );
        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.345, 0.678),
                                                            Pair.of(0.456, 0.789)
                                                    ))
                                                    .build())
                                  .transitionTime(tr("5min")),
                expectedPutCall(5).bri(250).transitionTime(tr("5min"))
        );

        assertAllScenePutCallsAsserted();
    }
    // todo: add test that interpolating between modes is not possible

    // todo: test that the interpolated call is not repeated on normal state progression -> put calls equals check needs to consider the gradient

    @Test
    void sceneControl_interpolate_sceneToScene_noOverlappingProperties_noInterpolation() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .ct(200),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .ct(300));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(200));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "interpolate:true"); // interpolate ignored

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 2,
                expectedPutCall(4).bri(100).ct(200),
                expectedPutCall(5).bri(200).ct(300)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // second state

        advanceTimeAndRunAndAssertScenePutCalls(runnables.get(1), 2,
                expectedPutCall(4).bri(100),
                expectedPutCall(5).bri(200)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_sceneToScene_longDuration_someOverlappingProperties_interpolatesTheOnesOverlapping() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .on(true),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .on(true),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(200));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusHours(10), "scene:TestScene2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        runnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).on(true), // a bit of a constructed case, but if we don't provide any state, the scene would set the light to off
                expectedPutCall(5).bri(100)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).on(true).transitionTime(MAX_TRANSITION_TIME),
                expectedPutCall(5).bri(117).transitionTime(MAX_TRANSITION_TIME)
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_sceneToScene_longDuration_keepsEqualProperties() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .ct(100));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100), // unchanged
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .ct(200));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusHours(2), "scene:TestScene2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        runnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(100),
                expectedPutCall(5).ct(100)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).bri(100).transitionTime(MAX_TRANSITION_TIME), // unchanged, but still kept
                expectedPutCall(5).ct(183).transitionTime(MAX_TRANSITION_TIME)
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_sceneToScene_justEffect_noInterpolationPossible() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .effect(Effect.builder().effect("effect").build()), // only effect
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .ct(300));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(200));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "interpolate:true"); // interpolate ignored

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 2,
                expectedPutCall(4).bri(100).effect(Effect.builder().effect("effect").build()),
                expectedPutCall(5).bri(200).ct(300)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_sceneToScene_targetMissesOneLight_raceCondition_lightIgnored() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(200));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(300),
                ScheduledLightState.builder()
                                   .id("/lights/8") // different light
                                   .bri(400));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        runnables.getFirst().run();

        assertGroupPutCalls(
                expectedGroupPutCall(2).bri(100) // ignores light 5, since not in target state
        );

        assertScenePutCalls(2,
                expectedPutCall(4).bri(300).transitionTime(tr("10min")),
                expectedPutCall(8).bri(400).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    private void mockSceneLightStates(int groupId, String sceneName, ScheduledLightState.ScheduledLightStateBuilder... builder) {
        List<ScheduledLightState> states = Arrays.stream(builder)
                                                 .map(ScheduledLightState.ScheduledLightStateBuilder::build)
                                                 .toList();
        when(mockedHueApi.getSceneLightState("/groups/" + groupId, sceneName)).thenReturn(states);
    }

    private void advanceTimeAndRunAndAssertScenePutCalls(ScheduledRunnable runnable, int groupId,
                                                         PutCall.PutCallBuilder... putCallBuilders) {
        setCurrentTimeTo(runnable);

        runnable.run();

        assertScenePutCalls(groupId, putCallBuilders);

        assertAllScenePutCallsAsserted();
    }

    private void assertScenePutCalls(int groupId, PutCall.PutCallBuilder... putCallBuilders) {
        List<PutCall> putCalls = Arrays.stream(putCallBuilders)
                                       .map(PutCall.PutCallBuilder::build)
                                       .toList();
        String groupIdString = "/groups/" + groupId;
        expectedScenePutCalls++;
        orderVerifier.verify(mockedHueApi, calls(1)).putSceneState(groupIdString, putCalls);
    }

    private void advanceTimeAndRunAndAssertGroupPutCalls(ScheduledRunnable runnable, PutCall.PutCallBuilder... putCallBuilders) {
        setCurrentTimeTo(runnable);

        runAndAssertGroupPutCalls(runnable, putCallBuilders);
    }

    private void runAndAssertGroupPutCalls(ScheduledRunnable runnable, PutCall.PutCallBuilder... putCallBuilders) {
        runnable.run();

        assertGroupPutCalls(putCallBuilders);
    }

    private void assertGroupPutCalls(PutCall.PutCallBuilder... putCallBuilders) {
        for (PutCall.PutCallBuilder putCallBuilder : putCallBuilders) {
            expectedGroupPutCalls++;
            orderVerifier.verify(mockedHueApi, calls(1)).putGroupState(putCallBuilder.build());
        }

        assertAllGroupPutCallsAsserted();
    }

    private void simulateSceneActivated(String groupId, String... containedLights) {
        simulateSceneWithNameActivated(groupId, unsyncedSceneName, containedLights);
    }

    private void simulateSyncedSceneActivated(String groupId, String... containedLights) {
        simulateSceneWithNameActivated(groupId, sceneSyncName, containedLights);
    }

    private void simulateSceneWithNameActivated(String groupId, String sceneName, String... containedLights) {
        String sceneId = "/scene/mocked_scene_" + sceneName;
        List<String> affectedIds = new ArrayList<>(Arrays.asList(containedLights));
        affectedIds.add(groupId);
        when(mockedHueApi.getAffectedIdsByScene(sceneId)).thenReturn(affectedIds);
        when(mockedHueApi.getSceneName(sceneId)).thenReturn(sceneName);

        scheduler.getSceneEventListener().onSceneActivated(sceneId);
    }

    private void mockIsLightOff(int id, boolean value) {
        mockIsLightOff("/lights/" + id, value);
    }

    private void mockIsLightOff(String id, boolean value) {
        when(mockedHueApi.isLightOff(id)).thenReturn(value);
    }

    private void mockIsGroupOff(int id, boolean value) {
        when(mockedHueApi.isGroupOff("/groups/" + id)).thenReturn(value);
    }

    private static PutCall.PutCallBuilder expectedGroupPutCall(int id) {
        return expectedPutCall("/groups/" + id);
    }

    private static PutCall.PutCallBuilder expectedPutCall(int id) {
        return expectedPutCall("/lights/" + id);
    }

    private static PutCall.PutCallBuilder expectedPutCall(Object id) {
        return PutCall.builder().id(id.toString());
    }

    private static PutCall.PutCallBuilder defaultPutCall() {
        return expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT);
    }

    private static PutCall.PutCallBuilder defaultGroupPutCall() {
        return expectedGroupPutCall(ID).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT);
    }

    private LightState.LightStateBuilder expectedState() {
        return LightState.builder()
                         .on(true)
                         .lightCapabilities(defaultCapabilities);
    }

    private ExpectedRunnable expectedRunnable(ZonedDateTime start, ZonedDateTime endExclusive) {
        return new ExpectedRunnable(start, endExclusive);
    }

    private ExpectedRunnable expectedPowerOnEnd(ZonedDateTime endExclusive) {
        return expectedRunnable(now, endExclusive);
    }

    private void assertSceneUpdate(String groupId, PutCall.PutCallBuilder... expectedPutCalls) {
        expectedSceneUpdates++;
        List<PutCall> putCalls = Arrays.stream(expectedPutCalls).map(PutCall.PutCallBuilder::build).toList();
        sceneSyncOrderVerifier.verify(mockedHueApi, calls(1)).createOrUpdateScene(groupId, sceneSyncName, putCalls);
    }

    private void mockSceneSyncFailure(String groupId) {
        doThrow(ApiFailure.class).when(mockedHueApi).createOrUpdateScene(eq(groupId), eq(sceneSyncName), any());
    }

    @RequiredArgsConstructor
    @Getter
    private static class ExpectedRunnable {
        private final ZonedDateTime start;
        private final ZonedDateTime end;
    }

}
