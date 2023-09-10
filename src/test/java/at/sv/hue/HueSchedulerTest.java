package at.sv.hue;

import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.HueApiFailure;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.ManualOverrideTracker;
import at.sv.hue.api.PutCall;
import at.sv.hue.time.InvalidStartTimeExpression;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@Slf4j
class HueSchedulerTest {

    private static final int ID = 1;
    private static final int DEFAULT_BRIGHTNESS = 50;
    private static final int DEFAULT_CT = 400; // very warm. [153-500]
    private static final double DEFAULT_X = 0.2319;
    private static final double DEFAULT_Y = 0.4675;
    private static final PutCall DEFAULT_PUT_CALL = expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT).build();

    private TestStateScheduler stateScheduler;
    private ManualOverrideTracker manualOverrideTracker;
    private HueScheduler scheduler;
    private ZonedDateTime now;
    private ZonedDateTime initialNow;
    private LightCapabilities defaultCapabilities;
    private String nowTimeString;
    private int connectionFailureRetryDelay;
    private int multiColorAdjustmentDelay;
    private StartTimeProviderImpl startTimeProvider;
    private boolean controlGroupLightsIndividually;
    private boolean disableUserModificationTracking;
    private HueApi mockedHueApi;
    private InOrder orderVerifier;
    private int expectedPutCalls;
    private String defaultInterpolationTransitionTimeInMs;
    private int minTrGap = 2; // in minutes
    private final int MAX_TRANSITION_TIME_WITH_BUFFER = ScheduledState.MAX_TRANSITION_TIME - minTrGap * 600;

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
            log.info("New time: {} [+{}] ({})", newTime, Duration.between(now, newTime), DayOfWeek.from(newTime));
        }
        now = newTime;
    }

    private void setCurrentAndInitialTimeTo(ZonedDateTime dateTime) {
        initialNow = now = dateTime;
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
                defaultInterpolationTransitionTimeInMs, 0, connectionFailureRetryDelay,
                multiColorAdjustmentDelay, minTrGap);
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

    private List<ScheduledRunnable> ensureScheduledStates(int expectedSize) {
        List<ScheduledRunnable> scheduledRunnables = stateScheduler.getScheduledStates();
        assertThat("ScheduledStates size differs", scheduledRunnables.size(), is(expectedSize));
        stateScheduler.clear();
        return scheduledRunnables;
    }

    private void mockLightIdForName(String name, int id) {
        when(mockedHueApi.getLightId(name)).thenReturn(id);
    }

    private void mockGroupLightsForId(int groupId, Integer... lights) {
        when(mockedHueApi.getGroupLights(groupId)).thenReturn(Arrays.asList(lights));
    }

    private void mockGroupIdForName(String name, int id) {
        when(mockedHueApi.getGroupId(name)).thenReturn(id);
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
        advanceTimeAndRunAndAssertPutCall(state, DEFAULT_PUT_CALL);

        ensureNextDayRunnable();
    }

    private void advanceTimeAndRunAndAssertPutCall(ScheduledRunnable scheduledRunnable, PutCall putCall) {
        setCurrentTimeTo(scheduledRunnable);

        runAndAssertPutCall(scheduledRunnable, putCall);
        assertAllPutCallsAsserted();
    }

    private void runAndAssertPutCall(ScheduledRunnable state, PutCall expectedPutCall) {
        state.run();

        assertPutCall(expectedPutCall);
    }

    private void assertPutCall(PutCall putCall) {
        expectedPutCalls++;
        orderVerifier.verify(mockedHueApi, calls(1)).putState(putCall);
    }

    private void setLightStateResponse(int id, LightState.LightStateBuilder lightStateBuilder) {
        setLightStateResponse(id, lightStateBuilder.build());
    }

    private void setLightStateResponse(int id, LightState lightState) {
        when(mockedHueApi.getLightState(id)).thenReturn(lightState);
    }

    private void setLightStateResponse(int id, boolean reachable, boolean on, String effect) {
        LightState lightState = LightState.builder()
                                          .brightness(DEFAULT_BRIGHTNESS)
                                          .colorTemperature(DEFAULT_CT)
                                          .effect(effect)
                                          .reachable(reachable)
                                          .on(on)
                                          .lightCapabilities(defaultCapabilities)
                                          .build();
        setLightStateResponse(id, lightState);
    }

    private void addGroupStateResponses(int id, LightState... lightStates) {
        when(mockedHueApi.getGroupStates(id)).thenReturn(Arrays.asList(lightStates));
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
        ScheduledRunnable state = ensureScheduledStates(1).get(0);
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
        assertThat("Schedule end differs. Difference: " + between + ". Start: " + state.getStart(), state.getEnd(), is(endExclusive.minusSeconds(1)));
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start) {
        Duration between = Duration.between(start, state.getStart());
        assertThat("Schedule start differs. Difference: " + between + ". End: " + state.getEnd(), state.getStart(), is(start));
    }

    private void addKnownLightIdsWithDefaultCapabilities(Integer... ids) {
        Arrays.stream(ids).forEach(this::mockDefaultLightCapabilities);
    }

    private void mockLightCapabilities(int id, LightCapabilities capabilities) {
        when(mockedHueApi.getLightCapabilities(id)).thenReturn(capabilities);
    }

    private void mockDefaultLightCapabilities(int id) {
        mockLightCapabilities(id, defaultCapabilities);
    }

    private void mockDefaultGroupCapabilities(int id) {
        mockGroupCapabilities(id, defaultCapabilities);
    }

    private void mockGroupCapabilities(int id, LightCapabilities capabilities) {
        when(mockedHueApi.getGroupCapabilities(id)).thenReturn(capabilities);
    }

    private void mockAssignedGroups(int lightId, List<Integer> groups) {
        lenient().when(mockedHueApi.getAssignedGroups(lightId)).thenReturn(groups);
    }

    private ScheduledRunnable startWithDefaultState() {
        addDefaultState();
        startScheduler();

        return ensureScheduledStates(1).get(0);
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

        return ensureScheduledStates(1).get(0);
    }

    private List<ScheduledRunnable> simulateLightOnEvent(int expectedScheduledStates) {
        simulateLightOnEvent("/lights/" + ID);
        return ensureScheduledStates(expectedScheduledStates);
    }

    private void simulateLightOnEvent(String idv1) {
        scheduler.getHueEventListener().onLightOn(idv1, null, false);
    }

    private ScheduledRunnable simulateLightOnEventExpectingSingleScheduledState() {
        return simulateLightOnEvent(1).get(0);
    }

    private ScheduledRunnable simulateLightOnEventExpectingSingleScheduledState(ZonedDateTime scheduleStart, ZonedDateTime endExclusive) {
        ScheduledRunnable scheduledRunnable = simulateLightOnEventExpectingSingleScheduledState();
        assertScheduleStart(scheduledRunnable, scheduleStart, endExclusive);
        return scheduledRunnable;
    }

    private void mockPutReturnValue(boolean value) {
        getPutStateMock().thenReturn(value);
    }

    private OngoingStubbing<Boolean> getPutStateMock() {
        return when(mockedHueApi.putState(any()));
    }

    private void enableUserModificationTracking() {
        disableUserModificationTracking = false;
        create();
    }

    private void resetMockedApi() {
        Mockito.reset(mockedHueApi);
        expectedPutCalls = 0;
    }

    private void assertAllPutCallsAsserted() {
        verify(mockedHueApi, times(expectedPutCalls)).putState(any());
    }

    private int tr(String tr) {
        return InputConfigurationParser.parseTransitionTime("tr", tr);
    }

    @BeforeEach
    void setUp() {
        mockedHueApi = mock(HueApi.class);
        orderVerifier = inOrder(mockedHueApi);
        expectedPutCalls = 0;
        mockPutReturnValue(true); // defaults to true, to signal success
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
                                               .capabilities(EnumSet.allOf(Capability.class)).build();
        multiColorAdjustmentDelay = 4;
        controlGroupLightsIndividually = false;
        disableUserModificationTracking = true;
        defaultInterpolationTransitionTimeInMs = null;
        when(mockedHueApi.getLightName(ID)).thenReturn("Test");
        create();
    }

    @AfterEach
    void tearDown() {
        ensureScheduledStates(0);
        assertAllPutCallsAsserted();
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now, now.plusDays(1));

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), DEFAULT_PUT_CALL.toBuilder().groupState(true).build());

        ensureNextDayRunnable();
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_becauseOfDayWrapAround() {
        addDefaultState(1, now.plusHours(1));

        ScheduledRunnable runnable = startAndGetSingleRunnable(now, now.plusHours(1));

        advanceTimeAndRunAndAssertPutCall(runnable, DEFAULT_PUT_CALL);

        ensureRunnable(now.plusHours(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_theNextWithCorrectDelay() {
        addDefaultState(1, now.plusHours(1));
        addDefaultState(1, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusHours(2));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), DEFAULT_PUT_CALL);

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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusHours(2));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2), now.plusDays(1));
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        setCurrentTimeTo(now.plusHours(3)); // 03:00
        addDefaultState(13, initialNow.plusHours(4));  // 04:00 -> scheduled in one hour
        addDefaultState(13, initialNow.plusHours(2)); // 02:00 -> scheduled immediately
        addDefaultState(13, initialNow.plusHours(1)); // 01:00 -> scheduled next day

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), initialNow.plusDays(1).plusHours(1));
        assertScheduleStart(scheduledRunnables.get(2), initialNow.plusDays(1).plusHours(1));
    }

    @Test
    void parse_unknownLightId_exception() {
        when(mockedHueApi.getLightName(1)).thenThrow(new LightNotFoundException("Light not foune"));

        assertThrows(LightNotFoundException.class, () -> addStateNow("1"));
    }

    @Test
    void parse_unknownGroupId_exception() {
        when(mockedHueApi.getGroupName(1)).thenThrow(new GroupNotFoundException("Group not found"));

        assertThrows(GroupNotFoundException.class, () -> addStateNow("g1"));
    }

    @Test
    void parse_group_brightness_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, LightCapabilities.NO_CAPABILITIES);

        assertThrows(BrightnessNotSupported.class, () -> addStateNow("g7", "bri:254"));
    }

    @Test
    void parse_group_colorTemperature_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, LightCapabilities.NO_CAPABILITIES);

        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("g7", "ct:500"));
    }

    @Test
    void parse_group_color_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, LightCapabilities.NO_CAPABILITIES);

        assertThrows(ColorNotSupported.class, () -> addStateNow("g7", "x:1", "y:1"));
    }

    @Test
    void parse_parsesInputLine_createsMultipleStates_canHandleGroups() {
        int groupId = 9;
        mockGroupLightsForId(groupId, 77);
        addKnownLightIdsWithDefaultCapabilities(1, 2);
        mockDefaultGroupCapabilities(groupId);
        addStateNow("1, 2,g" + groupId, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(2), now, now.plusDays(1));

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
    void parse_trimsProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1\t" + nowTimeString + "\t bri:" + DEFAULT_BRIGHTNESS + "  \tct:" + DEFAULT_CT);

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

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).ct(null).transitionTime(5).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:5s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(50).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_minutes() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:100min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(60000).build()
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_hours() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:1h");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(36000).build()
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_hoursAndMinutesAndSeconds() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:1H20min5s10");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(48060).build()
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTimeBefore_withSunTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "civil_dusk", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:sunset+10"); // tr-before:16:24:29
        ZonedDateTime sunset = startTimeProvider.getStart("sunset", now); // 16:14:29
        ZonedDateTime civilDusk = startTimeProvider.getStart("civil_dusk", now); // 16:47:46
        ZonedDateTime nextDaySunset = startTimeProvider.getStart("sunset", now.plusDays(1));
        ZonedDateTime nextNextDaySunset = startTimeProvider.getStart("sunset", now.plusDays(2));

        ScheduledRunnable crossOverState = startAndGetSingleRunnable(now, sunset.plusMinutes(10));

        // no interpolation or transition, as state was already reached
        advanceTimeAndRunAndAssertPutCall(crossOverState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());

        ScheduledRunnable firstState = ensureRunnable(sunset.plusMinutes(10), nextDaySunset.plusMinutes(10));

        int expectedTransitionTime = (int) (Duration.between(sunset.plusMinutes(10), civilDusk).toMillis() / 100L);
        advanceTimeAndRunAndAssertPutCall(firstState, expectedPutCall(1)
                .bri(DEFAULT_BRIGHTNESS)
                .transitionTime(expectedTransitionTime).build());

        ensureRunnable(nextDaySunset.plusMinutes(10), nextNextDaySunset.plusMinutes(10));
    }

    @Test
    void parse_canParseTransitionTimeBefore_withAbsoluteTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "01:00", "bri:1");
        addState("1", "12:00", "bri:254", "tr-before:01:00");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1)); // from previous day
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusHours(1)); // zero length state
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(5);

        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(12)); // second state
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(12), now.plusHours(14)); // third state
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(14), now.plusHours(15)); // fourth state
        assertScheduleStart(scheduledRunnables.get(3), now.plusHours(15), now.plusHours(20)); // fifth state
        assertScheduleStart(scheduledRunnables.get(4), now.plusHours(20), now.plusDays(1)); // first state

        setCurrentTimeTo(scheduledRunnables.get(2));
        scheduledRunnables.get(2).run();

        assertPutCall(expectedPutCall(1).bri(100).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(50).transitionTime(tr("58min")).build()); // 2 min gap added

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void parse_interpolateProperty_andTrBefore_prefersTrBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1");
        addState("1", "12:00", "bri:254", "interpolate:true", "tr-before:1h"); // ignores interpolate:true

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(11));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(11), now.plusDays(1));

        setCurrentTimeTo(scheduledRunnables.get(1));
        scheduledRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).bri(1).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(254).transitionTime(tr("1h")).build());

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);

        ScheduledRunnable previousDaySunriseState = scheduledRunnables.get(0);
        assertScheduleStart(previousDaySunriseState, now, sunrise); // from previous day
        assertScheduleStart(scheduledRunnables.get(1), sunrise, noon);
        assertScheduleStart(scheduledRunnables.get(2), noon, sunset);

        previousDaySunriseState.run();

        assertPutCall(expectedPutCall(1).bri(50).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(47).transitionTime(18910).build());
        assertAllPutCallsAsserted();

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(2);

        assertScheduleStart(followUpStates.get(0), now.plus(1891000 + 120000, ChronoUnit.MILLIS), sunrise); // next split start
        assertScheduleStart(followUpStates.get(1), sunset, nextDaySunrise);
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
        advanceTimeAndRunAndAssertPutCall(crossOverState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());

        ScheduledRunnable trBeforeRunnable = ensureRunnable(goldenHour, nextDayGoldenHour);

        // ignored transition time, as negative
        advanceTimeAndRunAndAssertPutCall(trBeforeRunnable, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());

        ensureRunnable(nextDayGoldenHour, nextNextDayGoldenHour);
    }

    @Test
    void parse_canParseTransitionTimeBefore_maxDuration_doesNotPerformAdditionalInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now); // null state
        addState(1, now.plusHours(1).plusMinutes(50), "bri:200", "tr-before:" + ScheduledState.MAX_TRANSITION_TIME);

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start

        ScheduledRunnable trRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(trRunnable, expectedPutCall(1).bri(200).transitionTime(ScheduledState.MAX_TRANSITION_TIME).build());

        ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(10)); // next day
    }

    @Test
    void parse_canParseTransitionTimeBefore_tooLongDuration_noPreviousState_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now); // null state
        addState(1, now.plusHours(3).plusMinutes(40), "bri:200", "tr-before:210min");

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start

        ScheduledRunnable trRunnable = startAndGetSingleRunnable();

        trRunnable.run(); // only logs warning, but causes no interactions as the previous state is missing for the required interpolation

        ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(10)); // next day
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trRunnable = scheduledRunnables.get(0);

        assertScheduleStart(trRunnable, now, initialNow.plusDays(1));

        // first split

        setCurrentTimeTo(trRunnable);
        trRunnable.run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness).build()); // previous state call as interpolation start
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 100 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split of transition

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        ScheduledRunnable followUpRunnable = followUpRunnables.get(0);
        ScheduledRunnable nextDayRunnable = followUpRunnables.get(1);

        assertScheduleStart(followUpRunnable, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled second split of transition
        assertScheduleStart(nextDayRunnable, initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // run follow-up call for the second split

        setCurrentTimeTo(followUpRunnable);
        followUpRunnable.run();

        // no interpolation as "initialBrightness + 100" already set at end of first part
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // second split of transition
        assertAllPutCallsAsserted();

        ScheduledRunnable finalSplit = ensureScheduledStates(1).get(0);

        assertScheduleStart(finalSplit, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled third split of transition

        // simulate power-on, ten minutes later: adjusts calls from above

        advanceCurrentTime(Duration.ofMinutes(5));
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables.get(0), now, now.minusMinutes(5));
        assertScheduleStart(powerOnRunnables.get(1), now, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS).minusMinutes(5));

        powerOnRunnables.get(0).run(); // already ended -> no put calls
        powerOnRunnables.get(1).run();
        // creates another power-on runnable but with adjusted end -> see "already ended" runnable for second power-on

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 105).build()); // adjusted to five minutes after
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER - 3000).build());

        ensureScheduledStates(0);

        // run final split call

        setCurrentTimeTo(finalSplit);
        finalSplit.run();

        // no interpolation as "initialBrightness + 200" already set at end of second part
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000).build()); // remaining 10 minutes
        assertAllPutCallsAsserted();

        // simulate second power on, five minutes later: adjusts calls from above

        advanceCurrentTime(Duration.ofMinutes(5));
        List<ScheduledRunnable> finalPowerOns = simulateLightOnEvent(2);
        assertScheduleStart(finalPowerOns.get(0), now, now.minusMinutes(5));
        assertScheduleStart(finalPowerOns.get(1), now, initialNow.plusDays(1));

        finalPowerOns.get(0).run(); // already ended -> no put calls
        finalPowerOns.get(1).run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 205).build());
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).transitionTime(3000).build()); // remaining five minutes

        // move to defined start -> no tr-before transition time left

        advanceCurrentTime(Duration.ofMinutes(5)); // at defined start

        ScheduledRunnable normalPowerOn = simulateLightOnEventExpectingSingleScheduledState(now, initialNow.plusDays(1));

        normalPowerOn.run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).build()); // no transition anymore
    }

    @Test
    void parse_transitionTimeBefore_usingInterpolate_longTransition_correctSplitCalls() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(30), "bri:" + (initialBrightness + 210), "interpolate:true");
        // 3h30min -> split to: 100min + 100min + 10min

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trRunnable = scheduledRunnables.get(0);

        assertScheduleStart(trRunnable, now, initialNow.plusDays(1));

        // first split

        setCurrentTimeTo(trRunnable);
        trRunnable.run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness).build()); // previous state call as interpolation start
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 100 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split of transition

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        ScheduledRunnable followUpRunnable = followUpRunnables.get(0);
        ScheduledRunnable nextDayRunnable = followUpRunnables.get(1);

        assertScheduleStart(followUpRunnable, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled second split of transition
        assertScheduleStart(nextDayRunnable, initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // run follow-up call for the second split

        setCurrentTimeTo(followUpRunnable);
        followUpRunnable.run();

        // no interpolation as "initialBrightness + 100" already set at end of first part
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // second split of transition
        assertAllPutCallsAsserted();

        ScheduledRunnable finalSplit = ensureScheduledStates(1).get(0);

        assertScheduleStart(finalSplit, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled third split of transition

        setCurrentTimeTo(finalSplit);
        finalSplit.run();

        // no interpolation as "initialBrightness + 200" already set at end of second part
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000).build()); // remaining 10 minutes
        assertAllPutCallsAsserted();
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trRunnable = scheduledRunnables.get(0);
        assertScheduleStart(trRunnable, now, initialNow.plusDays(1));

        trRunnable.run(); // no interaction, as directly skipped

        // power-on event

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now, initialNow.plusDays(1)); // the same as initial trRunnable

        powerOnRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build()); // previous state call as interpolation start
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 100 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split of transition

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        ScheduledRunnable followUpRunnable = followUpRunnables.get(0);
        ScheduledRunnable nextDayRunnable = followUpRunnables.get(1);

        assertScheduleStart(followUpRunnable, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled second split of transition
        assertScheduleStart(nextDayRunnable, initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // no user modification since split call -> proceeds normally

        setCurrentTimeTo(followUpRunnable);
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 100 - 2)); // same as split call

        followUpRunnable.run();

        // no interpolation, as "DEFAULT_BRIGHTNESS + 100" already set before
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 110).transitionTime(6000).build()); // remaining 10 minutes
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsFirstSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min"); // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        assertScheduleStart(initialRunnable, now, now.plusMinutes(10));
        assertScheduleStart(trRunnable, now.plusMinutes(10), now.plusDays(1));

        // first state runs normally
        advanceTimeAndRunAndAssertPutCall(initialRunnable, expectedPutCall(1).bri(initialBrightness).build());

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        setCurrentTimeTo(trRunnable);
        setLightStateResponse(1, expectedState().brightness(initialBrightness + 10)); // user modification
        trRunnable.run(); // detects manual override

        // advance time to second split -> will skip first split, as not relevant anymore
        advanceCurrentTime(Duration.ofMillis(ScheduledState.MAX_TRANSITION_TIME_MS));

        // power-on event

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables.get(0), now, now.minus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusDays(1)); // initial trRunnable again

        powerOnRunnables.get(0).run(); // already ended
        powerOnRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 100).build()); // end of first split, which was skipped
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // second split of transition

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        ScheduledRunnable finalSplit = followUpRunnables.get(0);
        ScheduledRunnable nextDayRunnable = followUpRunnables.get(1);

        assertScheduleStart(finalSplit, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled final split of transition
        assertScheduleStart(nextDayRunnable, initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // second power on, right at the start of the gap

        advanceCurrentTime(Duration.ofMillis(ScheduledState.MAX_TRANSITION_TIME_MS).minusMinutes(minTrGap));
        ScheduledRunnable powerOn = simulateLightOnEventExpectingSingleScheduledState(now, now.plusMinutes(minTrGap));

        powerOn.run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 198).build()); // interpolation only
        assertAllPutCallsAsserted();

        // final split

        setCurrentTimeTo(finalSplit);
        setLightStateResponse(1, expectedState().brightness(initialBrightness + 200 - 2)); // same as second split

        finalSplit.run();

        // no interpolation, as "initialBrightness + 200" already set at end of second split
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000).build()); // remaining 10 minutes
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsSecondSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        assertScheduleStart(initialRunnable, now, now.plusMinutes(10));
        assertScheduleStart(trRunnable, now.plusMinutes(10), now.plusDays(1));

        // first state runs normally
        advanceTimeAndRunAndAssertPutCall(initialRunnable, expectedPutCall(1).bri(initialBrightness).build());

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // run tr-runnable -> detects manual override

        setCurrentTimeTo(trRunnable);
        setLightStateResponse(1, expectedState().brightness(initialBrightness + 10)); // user modification
        trRunnable.run(); // detects manual override

        // power-on event -> resets tr runnable again

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables.get(0), now, now); // first state, already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusDays(1)); // trRunnable again

        powerOnRunnables.get(0).run(); // already ended
        powerOnRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness).build()); // previous state call as interpolation start
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 100 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split of transition

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        ScheduledRunnable secondSplit = followUpRunnables.get(0);
        ScheduledRunnable nextDayRunnable = followUpRunnables.get(1);

        assertScheduleStart(secondSplit, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled second split of transition
        assertScheduleStart(nextDayRunnable, initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // second split -> detect override

        setCurrentTimeTo(secondSplit);
        setLightStateResponse(1, expectedState().brightness(initialBrightness + 101)); // second modification
        secondSplit.run(); // detects manual override

        // advance time to final split -> skips second split, as not relevant anymore
        advanceCurrentTime(Duration.of(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS));

        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(secondPowerOnRunnables.get(0), now, now.minus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS));
        assertScheduleStart(secondPowerOnRunnables.get(1), now, initialNow.plusDays(1));

        secondPowerOnRunnables.get(0).run(); // already ended
        secondPowerOnRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200).build()); // end of the second part, which was skipped
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000).build()); // remaining 10 minutes
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsFirstAndSecondSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        assertScheduleStart(initialRunnable, now, now.plusMinutes(10));
        assertScheduleStart(trRunnable, now.plusMinutes(10), now.plusDays(1));

        // first state runs normally
        advanceTimeAndRunAndAssertPutCall(initialRunnable, expectedPutCall(1).bri(initialBrightness).build());

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        setCurrentTimeTo(trRunnable);
        setLightStateResponse(1, expectedState().brightness(initialBrightness + 10)); // user modification
        trRunnable.run(); // detects manual override

        // advance time to second split
        advanceCurrentTime(Duration.of(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS));
        // advance time to final split
        advanceCurrentTime(Duration.of(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables.get(0), now, initialNow.plusMinutes(10)); // initial state, already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusDays(1));

        powerOnRunnables.get(0).run(); // already ended
        powerOnRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200).build()); // end of the second part, which was skipped
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000).build()); // remaining 10 minutes

        ScheduledRunnable nextDayRunnable = ensureScheduledStates(1).get(0);

        assertScheduleStart(nextDayRunnable, initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_powerOnInGaps_correctlyHandled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        assertScheduleStart(initialRunnable, now, now.plusMinutes(10));
        assertScheduleStart(trRunnable, now.plusMinutes(10), now.plusDays(1));

        // first state runs normally
        advanceTimeAndRunAndAssertPutCall(initialRunnable, expectedPutCall(1).bri(initialBrightness).build());

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // Run tr-runnable

        setCurrentTimeTo(trRunnable);
        trRunnable.run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 100 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split of transition
        assertAllPutCallsAsserted();

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        ScheduledRunnable secondSplit = followUpRunnables.get(0);
        ScheduledRunnable nextDayRunnable = followUpRunnables.get(1);

        assertScheduleStart(secondSplit, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // scheduled second split of transition
        assertScheduleStart(nextDayRunnable, initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // Power on, right at the start of the gap

        advanceCurrentTime(Duration.ofMillis(ScheduledState.MAX_TRANSITION_TIME_MS).minusMinutes(minTrGap));
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables.get(0), now, initialNow.plusMinutes(10)); // first state, already ended
        assertScheduleStart(powerOnRunnables.get(1), now, now.plusMinutes(minTrGap)); // first split again

        powerOnRunnables.get(0).run(); // already ended
        powerOnRunnables.get(1).run();

        // performs just the interpolation call
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 100 - 2).build());
        assertAllPutCallsAsserted();

        // Power on, one minute in the gap

        advanceCurrentTime(Duration.ofMinutes(1));
        ScheduledRunnable inGapPowerRunnable = simulateLightOnEventExpectingSingleScheduledState(now, now.plusMinutes(minTrGap - 1));

        inGapPowerRunnable.run();

        // performs just the interpolation call
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 100 - 1).build());
        assertAllPutCallsAsserted();

        setCurrentTimeTo(secondSplit);
        secondSplit.run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split of transition
        assertAllPutCallsAsserted();

        ScheduledRunnable thirdSplit = ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1));

        // Advance to middle of second split, and cause power on -> does not over extend the transition, even though its less than the max transition length

        advanceCurrentTime(Duration.ofMillis(ScheduledState.MAX_TRANSITION_TIME_MS / 2));

        List<ScheduledRunnable> powerOnRunnables2 = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables2.get(0), now, initialNow.plusMinutes(10).plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)); // already ended power on for first split
        assertScheduleStart(powerOnRunnables2.get(1), now, initialNow.plusMinutes(10).plus(ScheduledState.MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS));

        powerOnRunnables2.get(1).run();

        Duration untilThirdSplit = Duration.between(now, thirdSplit.getStart()).minusMinutes(minTrGap);
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 150).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime((int) (untilThirdSplit.toMillis() / 100)).build()); // transition till start of third and final split (minus buffer)
        assertAllPutCallsAsserted();

        // Third and final split

        setCurrentTimeTo(thirdSplit);

        thirdSplit.run();

        assertPutCall(expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000).build()); // remaining 10 minutes
    }

    @Test
    void parse_transitionTimeBeforeBefore_longDuration_24Hours_zeroLengthStateBefore_performsInterpolationOverWholeDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:254", "tr-before:24h");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1), now.plusDays(1)); // zero length state

        scheduledRunnables.get(0).run();

        assertPutCall(expectedPutCall(1).bri(1).build());
        assertPutCall(expectedPutCall(1).bri(19 - 1).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(2);

        assertScheduleStart(followUpStates.get(0), now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)); // next split
        assertScheduleStart(followUpStates.get(1), now.plusDays(1), now.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_moreThan24Hours_25hours_overAdjusts_treatedAs23Hours_correctlyScheduled() { // todo: this is not officially supported, but there is no validation right now
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr-before:25h");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1).minusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1), now.plusDays(1).minusHours(1)); // strange artifact
    }

    @Test
    void parse_transitionTImeBefore_longDuration_putCallReturnsFalseForSplitCall_skipsFurtherSplitCallsUntilPowerOn() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:254", "tr-before:24h");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstSplit = scheduledRunnables.get(0);
        assertScheduleStart(firstSplit, now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1), now.plusDays(1)); // zero length state

        firstSplit.run();

        assertPutCall(expectedPutCall(1).bri(1).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(18).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(2);
        ScheduledRunnable secondSplit = followUpStates.get(0);

        assertScheduleStart(secondSplit, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)); // next split
        assertScheduleStart(followUpStates.get(1), now.plusDays(1), now.plusDays(2)); // next day

        setCurrentTimeTo(secondSplit);
        mockPutReturnValue(false);
        secondSplit.run(); // put returns false, indicating light off
        mockPutReturnValue(true);

        assertPutCall(expectedPutCall(1).bri(36).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        ScheduledRunnable thirdSplit = ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // third split -> no put calls, skipped

        setCurrentTimeTo(thirdSplit);
        thirdSplit.run();

        assertAllPutCallsAsserted(); // no put calls
        ensureScheduledStates(0); // no further split calls scheduled

        // power on event -> re tries third split

        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(3);

        assertScheduleStart(powerOnEvents.get(0), now, initialNow.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS));
        assertScheduleStart(powerOnEvents.get(1), now, initialNow.plus(ScheduledState.MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS));
        assertScheduleStart(powerOnEvents.get(2), now, initialNow.plusDays(1)); // third split again

        powerOnEvents.get(0).run(); // already ended
        powerOnEvents.get(1).run(); // already ended
        powerOnEvents.get(2).run();

        assertPutCall(expectedPutCall(1).bri(36).build()); // end of second split
        assertPutCall(expectedPutCall(1).bri(53).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split
    }

    @Test
    void parse_transitionTimeBefore_shiftsGivenStartByThatTime_afterPowerCycle_sameStateAgainWithTransitionTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.plusMinutes(10), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, now, now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(6000).build());

        ensureNextDayRunnable(initialNow);

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCall(powerOnRunnable, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(6000).build());
    }

    @Test
    void parse_transitionTimeBefore_allowsBackToBack_zeroLengthState_usedOnlyForInterpolation() { // todo: write a similar test with interpolate:true
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS); // 00:00, zero length
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min"); // 00:00, same start as initial
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 20)); // 10:00, second zero length
        addState(1, now.plusMinutes(20), "bri:" + (DEFAULT_BRIGHTNESS + 30), "tr-before:10min"); // 10:00 same start as second zero length

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(4);
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondZeroLengthRunnable = scheduledRunnables.get(1);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(2);
        ScheduledRunnable firstZeroLengthRunnable = scheduledRunnables.get(3);

        assertScheduleStart(firstTrBeforeRunnable, now, now.plusMinutes(10));
        assertScheduleStart(secondZeroLengthRunnable, now.plusMinutes(10), now.plusMinutes(10)); // zero length state, scheduled next day
        assertScheduleStart(secondTrBeforeRunnable, now.plusMinutes(10), now.plusDays(1));
        assertScheduleStart(firstZeroLengthRunnable, now.plusDays(1), now.plusDays(1)); // zero length state, scheduled next day

        // first tr-before runnable
        setCurrentTimeTo(firstTrBeforeRunnable);
        firstTrBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min")).build()); // gap added

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // power on after 5 minutes -> correct interpolation

        advanceCurrentTime(Duration.ofMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now, initialNow.plusMinutes(10));

        powerOnRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 5).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("3min")).build()); // gap added

        // second tr-before

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        setCurrentTimeTo(secondTrBeforeRunnable);
        secondTrBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // run zero length runnable -> no interaction

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 30));
        setCurrentTimeTo(firstZeroLengthRunnable);
        firstZeroLengthRunnable.run();

        ensureRunnable(now.plusDays(1), now.plusDays(1)); // next day, again zero length
    }

    @Test
    void parse_transitionTimeBefore_performsInterpolationsAfterPowerCycles_usesTransitionFromPreviousState() {
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr:1"); // this transition is used in interpolated call
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now, initialNow.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1));

        scheduledRunnables.get(0).run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(4800).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day

        ScheduledRunnable powerOnRunnable1 = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable1.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(4800).build());

        ScheduledRunnable powerOnRunnable2 = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable2.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(4800).build());
    }

    @Test
    void parse_transitionTimeBefore_ifPreviousHasNotTransitionTime_usesDefault() {
        defaultInterpolationTransitionTimeInMs = "2"; // default value
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS); // no explicit transition time set
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now, initialNow.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1));

        scheduledRunnables.get(0).run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(2).build()); // uses default
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(4800).build());

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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now, initialNow.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1));

        scheduledRunnables.get(0).run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(0).build()); // reused previous value
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(4800).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_multipleTrBeforeAfterEachOther_repeatedNextDay_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.plusMinutes(5), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min"); // 00:00
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min"); // 00:05

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(1);

        assertScheduleStart(firstTrBeforeRunnable, now, now.plusMinutes(5));
        assertScheduleStart(secondTrBeforeRunnable, now.plusMinutes(5), now.plusDays(1));

        // first tr-before

        setCurrentTimeTo(firstTrBeforeRunnable); // 00:00
        firstTrBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build()); // interpolated call from "previous" state from day before
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // added gap

        ScheduledRunnable nextDay1 = ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // second tr-before

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(secondTrBeforeRunnable);
        secondTrBeforeRunnable.run();

        // no interpolation as previous state = last seen state
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min")).build());

        ScheduledRunnable nextDay2 = ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(5)); // next day

        // repeat next day, same calls expected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        setCurrentTimeTo(nextDay1);
        nextDay1.run();

        // no interpolation as previous state = last seen state
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // gap added

        ScheduledRunnable nextDay3 = ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(nextDay2);
        nextDay2.run();

        // no interpolation as previous state = last seen state
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min")).build());

        ScheduledRunnable nextDay4 = ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(5)); // next day

        // repeat next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        setCurrentTimeTo(nextDay3);
        nextDay3.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // gap added

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // turn on again, 2 minutes after start

        advanceCurrentTime(Duration.ofMinutes(2));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(5);
        assertScheduleStart(powerOnRunnables.get(0), now, initialNow.plusMinutes(5)); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusDays(1)); // already ended
        assertScheduleStart(powerOnRunnables.get(2), now, initialNow.plusDays(1).plusMinutes(5)); // already ended
        assertScheduleStart(powerOnRunnables.get(3), now, initialNow.plusDays(2)); // already ended
        assertScheduleStart(powerOnRunnables.get(4), now, initialNow.plusDays(2).plusMinutes(5));

        powerOnRunnables.get(4).run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 6).build()); // adjusted interpolated call
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("1min")).build()); // gap added

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(nextDay4);
        advanceCurrentTime(Duration.ofMinutes(5)); // "turned on" at defined start, i.e., no interpolation and transition time expected
        nextDay4.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build());

        ensureRunnable(now.plusDays(1).minusMinutes(5), now.plusDays(2).minusMinutes(10)); // next day
    }

    @Test
    void parse_transitionTimeBefore_withDayOfWeek_backToBack_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, now.plusMinutes(5), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min");
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min", "days:MO,TU"); // 00:05

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        assertScheduleStart(firstState, now, now.plusMinutes(5));
        assertScheduleStart(secondState, now.plusMinutes(5), now.plusDays(1));

        setCurrentTimeTo(firstState);
        firstState.run();

        // no interpolated call, as second state is not scheduled on Sunday (the day before)
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // gap added
        assertAllPutCallsAsserted();

        ScheduledRunnable nextFirstState1 = ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day (Tuesday)

        // simulate light on event, which schedules the same state again -> still no previous state found, no interpolation

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now, now.plusMinutes(5));

        powerOnRunnable.run(); // same state as firstState

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // gap added
        assertAllPutCallsAsserted();

        // run second state

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(secondState);
        secondState.run();

        // no interpolation, as "DEFAULT_BRIGHTNESS" was already set before
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min")).build());
        assertAllPutCallsAsserted();

        ScheduledRunnable nextSecondState1 = ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day (Tuesday)

        // [Tuesday] run next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        setCurrentTimeTo(nextFirstState1);
        nextFirstState1.run();

        // no interpolation, as "DEFAULT_BRIGHTNESS + 10" was already set before
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // gap added
        assertAllPutCallsAsserted();

        ScheduledRunnable nextFirstState2 = ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day (Wednesday), now full day, as there is no additional state

        // simulate light on event, which schedules the same state again -> but now with interpolations

        List<ScheduledRunnable> powerOnRunnables2 = simulateLightOnEvent(3);
        assertScheduleStart(powerOnRunnables2.get(0), now, initialNow.plusMinutes(5)); // already ended
        assertScheduleStart(powerOnRunnables2.get(1), now, initialNow.plusDays(1)); // already ended
        assertScheduleStart(powerOnRunnables2.get(2), now, now.plusMinutes(5));

        powerOnRunnables2.get(2).run(); // same state as nextFirstState1

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // gap added
        assertAllPutCallsAsserted();

        // second state

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(nextSecondState1);
        nextSecondState1.run();

        // no interpolation, as "DEFAULT_BRIGHTNESS" was already set
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min")).build());
        assertAllPutCallsAsserted();

        ensureRunnable(initialNow.plusDays(7).plusMinutes(5), initialNow.plusDays(8)); // next week (Monday)

        // [Wednesday] run next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        setCurrentTimeTo(nextFirstState2);
        nextFirstState2.run();

        // no interpolation as "DEFAULT_BRIGHTNESS + 10" was already set
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min")).build());
        assertAllPutCallsAsserted();

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day (Thursday)
    }

    @Test
    void parse_transitionTimeBefore_withDayOfWeek_backToBack_onlyOnOneDayTwoDaysInTheFuture_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min", "days:WE"); // 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min", "days:MO,WE"); // 00:05

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(1);

        assertScheduleStart(secondTrBeforeRunnable, now.plusMinutes(5), now.plusDays(1)); // only second is scheduled on Monday (=today)
        assertScheduleStart(firstTrBeforeRunnable, now.plusDays(2), now.plusDays(2).plusMinutes(5)); // on Wednesday

        // second tr-before

        setCurrentTimeTo(secondTrBeforeRunnable);
        secondTrBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(3000).build()); // full 5 min transition

        ScheduledRunnable secondTrBeforeOnWednesday = ensureRunnable(initialNow.plusDays(2).plusMinutes(5), initialNow.plusDays(3)); // on Wednesday

        // move to Wednesday: first tr-before

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        setCurrentTimeTo(firstTrBeforeRunnable);
        firstTrBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")).build()); // 2 min gap added

        ensureRunnable(initialNow.plusDays(9), initialNow.plusDays(9).plusMinutes(5)); // next Wednesday

        // second tr-before again -> adjusted transition time

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(secondTrBeforeOnWednesday);
        secondTrBeforeOnWednesday.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min")).build());

        ensureRunnable(initialNow.plusDays(7).plusMinutes(5), initialNow.plusDays(8)); // next monday
    }

    // todo: what if the state crosses over to the previous day. Does it then still work?

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnMonday_todayIsMonday_crossesOverToPreviousDay_stillScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, now, "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:MO");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnTuesday_todayIsMonday_crossesOverToPreviousDay_scheduledAtEndOfDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, now, "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:TU");

        startAndGetSingleRunnable(now.plusDays(1).minusMinutes(10), now.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnSunday_todayIsMonday_crossesOverToPreviousDay_scheduledNextWeek() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, now, "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:SU");

        startAndGetSingleRunnable(now.plusDays(6).minusMinutes(10), now.plusDays(7));
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_backToBack_onOneDayOfWeek_correctGapAdded() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min", "days:SA"); // 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min"); // 00:05 -> adjusted to 00:07 ONLY on SA

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable dayCrossOverState = scheduledRunnables.get(0);
        ScheduledRunnable nextSaturdayState = scheduledRunnables.get(1);

        assertScheduleStart(dayCrossOverState, now, now.plusMinutes(5)); // day cross over of second state
        assertScheduleStart(nextSaturdayState, now.plusDays(5), now.plusDays(5).plusMinutes(5));  // state scheduled next Saturday

        // cross over state (second tr-before runnable from Sunday)

        advanceTimeAndRunAndAssertPutCall(dayCrossOverState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build());

        ScheduledRunnable secondState = ensureRunnable(now.plusMinutes(5), now.plusDays(1).plusMinutes(5)); // rescheduled second state

        // no interpolated call, as state is not scheduled on Sunday (the day before)
        advanceTimeAndRunAndAssertPutCall(secondState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(3000).build());

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_crossesDayLine_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "ct:" + DEFAULT_CT, "tr-before:20min");

        ScheduledRunnable runnable = startAndGetSingleRunnable(now, now.plusDays(1).minusMinutes(20));

        advanceTimeAndRunAndAssertPutCall(runnable, expectedPutCall(1).ct(DEFAULT_CT).build());

        ScheduledRunnable nextDay = ensureRunnable(initialNow.plusDays(1).minusMinutes(20), initialNow.plusDays(2).minusMinutes(20));

        advanceTimeAndRunAndAssertPutCall(nextDay, expectedPutCall(1).ct(DEFAULT_CT).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(2).minusMinutes(20), initialNow.plusDays(3).minusMinutes(20));
    }

    @Test
    void parse_dayCrossOver_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(20), "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1).minusMinutes(20));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(1).ct(DEFAULT_CT).build());

        ScheduledRunnable runnable = ensureRunnable(now.plusDays(1).minusMinutes(20), now.plusDays(2).minusMinutes(20));

        setCurrentTimeTo(now.plusDays(1).plusHours(1));
        runAndAssertPutCall(runnable, expectedPutCall(1).ct(DEFAULT_CT).build());

        ensureRunnable(initialNow.plusDays(2).minusMinutes(20), initialNow.plusDays(3).minusMinutes(20));
    }

    @Test
    void parse_transitionTimeBefore_overNight_doesNotOverAdjustTransitionTime_returnsNullInstead() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(50));
        addState(1, now.plusMinutes(5), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, now, now.plusDays(1));

        advanceCurrentTime(Duration.ofMinutes(10)); // here we cross over to tomorrow

        runAndAssertPutCall(scheduledRunnable, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build()); // no transition time expected

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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable crossOverState = scheduledRunnables.get(0);
        ScheduledRunnable sunsetState = scheduledRunnables.get(1);

        assertScheduleStart(crossOverState, now, sunset); // sunrise started yesterday
        assertScheduleStart(sunsetState, sunset, nextDaySunrise.minusHours(8)); // sunset from today

        setCurrentTimeTo(crossOverState);
        crossOverState.run();

        ZonedDateTime trBeforeStart = sunrise.minusHours(8);
        Duration initialStartOffset = Duration.between(trBeforeStart, now);
        long adjustedSplitDuration = MAX_TRANSITION_TIME_WITH_BUFFER * 100 - initialStartOffset.toMillis();
        long adjustedNextStart = ScheduledState.MAX_TRANSITION_TIME_MS - initialStartOffset.toMillis();

        assertPutCall(expectedPutCall(1).bri(19).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(61 - 1).transitionTime((int) (adjustedSplitDuration / 100)).build()); // first split call

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstSplitCall = followUpRunnables.get(0);
        ScheduledRunnable nextDaySunriseState = followUpRunnables.get(1);

        assertScheduleStart(firstSplitCall, now.plus(adjustedNextStart, ChronoUnit.MILLIS), sunset); // next split call
        assertScheduleStart(nextDaySunriseState, nextDaySunrise.minusHours(8), nextDaySunset); // sunrise rescheduled for the next day

        advanceTimeAndRunAndAssertPutCall(firstSplitCall,
                expectedPutCall(1).bri(112 - 1).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        ScheduledRunnable secondSplitCall = ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        advanceTimeAndRunAndAssertPutCall(secondSplitCall,
                expectedPutCall(1).bri(163 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        ScheduledRunnable thirdSplitCall = ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        advanceTimeAndRunAndAssertPutCall(thirdSplitCall,
                expectedPutCall(1).bri(213 - 1).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        ScheduledRunnable fourthSplitCall = ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        setCurrentTimeTo(fourthSplitCall);
        Duration finalSplitDuration = Duration.between(now, sunrise);
        runAndAssertPutCall(fourthSplitCall, // last split call
                expectedPutCall(1).bri(254).transitionTime((int) (finalSplitDuration.toMillis() / 100L)).build());

        // power on event

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(5);
        assertScheduleStart(powerOnRunnables.get(0), now, firstSplitCall.getStart()); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, secondSplitCall.getStart()); // already ended
        assertScheduleStart(powerOnRunnables.get(2), now, thirdSplitCall.getStart()); // already ended
        assertScheduleStart(powerOnRunnables.get(3), now, fourthSplitCall.getStart()); // already ended
        assertScheduleStart(powerOnRunnables.get(4), now, sunset);

        thirdSplitCall.run();

        assertPutCall(expectedPutCall(1).bri(213).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(254).transitionTime((int) (finalSplitDuration.toMillis() / 100L)).build());

        // sunset state

        advanceTimeAndRunAndAssertPutCall(sunsetState, expectedPutCall(1).bri(10).build());

        ensureRunnable(nextDaySunset, nextNextDaySunrise.minusHours(8));
    }

    @Test
    void parse_transitionTimeBefore_backToBack_alreadyEnoughGapInBetween_noAdjustmentsMade() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10"); // zero length
        addState(1, "01:00", "bri:100", "tr-before:1h"); // gap not relevant
        addState(1, "01:30", "bri:254", "tr-before:28min"); // gap of 2 minutes added

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(1);

        assertScheduleStart(firstTrRunnable, now, now.plusHours(1).plusMinutes(minTrGap));
        assertScheduleStart(secondTrRunnable, now.plusHours(1).plusMinutes(minTrGap), now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(2), now.plusDays(1), now.plusDays(1)); // zero length

        // first tr-before runnable

        setCurrentTimeTo(firstTrRunnable);
        firstTrRunnable.run();

        assertPutCall(expectedPutCall(1).bri(10).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(100).transitionTime(36000).build()); // no additional buffer used

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1).plusMinutes(minTrGap)); // next day

        // second tr-before runnable

        setCurrentTimeTo(secondTrRunnable);
        secondTrRunnable.run();

        assertPutCall(expectedPutCall(1).bri(254).transitionTime(16800).build()); // no additional buffer used

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_withModificationTracking_backToBack_smallTrBefore_lessThanTwoMinutes_adjustedTrToEnsureGap() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10"); // zero length
        addState(1, "00:02", "bri:100", "tr-before:2min"); // 00:00-00:02
        addState(1, "00:04", "bri:254", "tr-before:2min"); // 00:02-00:04
        addState(1, "00:05", "bri:100", "tr-before:1min"); // 00:04-00:05

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(4);
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(1);
        ScheduledRunnable thirdTrRunnable = scheduledRunnables.get(2);

        assertScheduleStart(firstTrRunnable, now, now.plusMinutes(2));
        assertScheduleStart(secondTrRunnable, now.plusMinutes(2), now.plusMinutes(4));
        assertScheduleStart(thirdTrRunnable, now.plusMinutes(4), now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(3), now.plusDays(1), now.plusDays(1)); // zero length

        // first

        setCurrentTimeTo(firstTrRunnable);
        firstTrRunnable.run();

        assertPutCall(expectedPutCall(1).bri(10).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(100).build()); // removed tr to ensure gap, does not use zero

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(2));

        // second

        setLightStateResponse(1, expectedState().brightness(100));
        setCurrentTimeTo(secondTrRunnable);
        secondTrRunnable.run();

        assertPutCall(expectedPutCall(1).bri(254).build()); // removed tr to ensure gap, does not use zero

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(2));

        // third

        setLightStateResponse(1, expectedState().brightness(254));
        setCurrentTimeTo(thirdTrRunnable);
        thirdTrRunnable.run();

        assertPutCall(expectedPutCall(1).bri(100).transitionTime(tr("1min")).build());

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTime_withModificationTracking_backToBack_ensuresGap() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "tr:2min"); // to small gap
        addState(1, "00:05", "bri:150", "tr:6min"); // too long tr, which would cause overlap
        addState(1, "00:10", "bri:200");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(4);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusMinutes(2));
        assertScheduleStart(scheduledRunnables.get(1), now.plusMinutes(2), now.plusMinutes(5));
        assertScheduleStart(scheduledRunnables.get(2), now.plusMinutes(5), now.plusMinutes(10));
        assertScheduleStart(scheduledRunnables.get(3), now.plusMinutes(10), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).build()); // removes tr

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        setLightStateResponse(1, expectedState().brightness(10));
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("1min")).build()); // ensures min gap

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(5));

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(2),
                expectedPutCall(1).bri(150).transitionTime(tr("3min")).build()); // shortened tr and ensures gap

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(10));
    }

    @Test
    void parse_transitionTime_withModificationTracking_backToBack_customGap_usesGap() {
        minTrGap = 5; // 5 minutes
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:7min");
        addState(1, "00:07", "bri:100");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusMinutes(7));
        assertScheduleStart(scheduledRunnables.get(1), now.plusMinutes(7), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")).build()); // adjusts to custom gap

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(7));
    }

    @Test
    void parse_transitionTime_withUserModificationTracking_backToBack_stateHasForceProperty_doesNotAddGap() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "force:true", "tr:3min"); // would overlap
        addState(1, "00:04", "bri:100", "force:true");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusMinutes(2));
        assertScheduleStart(scheduledRunnables.get(1), now.plusMinutes(2), now.plusMinutes(4));
        assertScheduleStart(scheduledRunnables.get(2), now.plusMinutes(4), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")).build()); // does not remove tr

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("2min")).build()); // still adjusts tr to avoid overlap

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(4));
    }

    @Test
    void parse_transitionTime_noUserModificationTracking_backToBack_doesNotAddGap_stillShortensIfOverlap() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "tr:4min"); // would overlap
        addState(1, "00:05", "bri:200");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusMinutes(2));
        assertScheduleStart(scheduledRunnables.get(1), now.plusMinutes(2), now.plusMinutes(5));
        assertScheduleStart(scheduledRunnables.get(2), now.plusMinutes(5), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")).build()); // does not remove tr

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("3min")).build()); // shortened tr to avoid overlap

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(5));
    }

    @Test
    void parse_transitionTimeBefore_backToBack_noGapsInBetween_ignoresPreviousDay_adjustsTr() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100", "tr-before:1h"); // 23:00-00:00
        addState(1, "00:30", "bri:254", "tr-before:30min"); // 00:00-00:30

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(1);

        assertScheduleStart(secondTrRunnable, now, now.plusDays(1).minusHours(1)); // second state scheduled first as other already ended at 00:00
        assertScheduleStart(firstTrRunnable, now.plusDays(1).minusHours(1), now.plusDays(1));

        // second tr-before runnable

        secondTrRunnable.run();

        assertPutCall(expectedPutCall(1).bri(100).build());
        assertPutCall(expectedPutCall(1).bri(254).transitionTime(tr("30min")).build());
        assertAllPutCallsAsserted();

        ensureRunnable(now.plusDays(1), now.plusDays(2).minusHours(1)); // next day

        // first tr-before runnable

        setLightStateResponse(1, expectedState().brightness(254));
        setCurrentTimeTo(firstTrRunnable); // 23:00
        firstTrRunnable.run();

        assertPutCall(expectedPutCall(1).bri(100).transitionTime(tr("58min")).build()); // gap added
        assertAllPutCallsAsserted();

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_longDuration_backToBack_noGapsInBetween_considersPreviousDay_adjustsTrBefore() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100", "tr-before:23h"); // 01:00, starts yesterday
        addState(1, "01:00", "bri:254", "tr-before:30min"); // 01:00 -> 00:30

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(1);

        assertScheduleStart(firstTrBeforeRunnable, now, now.plusMinutes(30));
        assertScheduleStart(secondTrBeforeRunnable, now.plusMinutes(30), now.plusHours(1));

        // first tr-before runnable

        setCurrentTimeTo(firstTrBeforeRunnable);
        firstTrBeforeRunnable.run();

        // no interpolation, as state already started
        assertPutCall(expectedPutCall(1).bri(100).build());
        assertAllPutCallsAsserted();

        ScheduledRunnable nextDayFirst = ensureRunnable(now.plusHours(1), initialNow.plusDays(1).plusMinutes(30));

        // second tr-before runnable

        setLightStateResponse(1, expectedState().brightness(100));
        setCurrentTimeTo(secondTrBeforeRunnable);
        secondTrBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(254).transitionTime(tr("28min")).build()); // gap added

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusHours(1));

        // next day first

        setLightStateResponse(1, expectedState().brightness(254));
        setCurrentTimeTo(nextDayFirst);
        nextDayFirst.run();

        assertPutCall(expectedPutCall(1).bri(243).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split call
        assertAllPutCallsAsserted();

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(2);
        assertScheduleStart(followUpRunnables.get(0), now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1).plusMinutes(30));
        assertScheduleStart(followUpRunnables.get(1), initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2).plusMinutes(30)); // next day
    }

    // todo: add test for temporary copy and gap (but probably not needed right now, as we only have the split calls as temporary now)

    @Test
    void parse_transitionTimeBefore_group_lightTurnedOnLater_stillBeforeStart_transitionTimeIsShortenedToRemainingTimeBefore() {
        mockGroupLightsForId(1, 5);
        addKnownLightIdsWithDefaultCapabilities(1);
        mockDefaultGroupCapabilities(1);
        addState("g1", now.plusMinutes(10), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(5));

        runAndAssertPutCall(scheduledRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(3000).groupState(true).build());

        ensureNextDayRunnable(initialNow);
    }

    @Test
    void parse_transitionTimeBefore_lightTurnedAfterStart_beforeTransitionTimeIgnored_normalTransitionTimeUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.plusMinutes(10), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10min", "tr:3s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(10));

        runAndAssertPutCall(scheduledRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(30).build());

        ensureNextDayRunnable(initialNow);
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_nullState_performsNoInterpolations() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState(1, now.plusMinutes(2)); // null state, detected as previous state -> treated as no interpolation possible
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(20));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_previousIsOnState_withBrightness_removedForInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "on:true", "bri:10");
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(20));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(10).build()); // no "on" property
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_previousIsOnStateOnly_notInterpolationAtAll() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "on:true"); // just a single "on"
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(20));

        trBeforeRunnable.run();

        // no additional interpolation call
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOnState_alsoAddsOnPropertyToInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:10"); // no explicit "on"
        addState(1, now.plusMinutes(40), "on:true", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(20));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(10).on(true).build()); // added "on" property
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).on(true).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOffState_doesNotAddOffPropertyToInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:10"); // no explicit "off"
        addState(1, now.plusMinutes(40), "on:false", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(20));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(10).build()); // no "off" added
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).on(false).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnExactlyAtTrBeforeStart_performsNoInterpolation_setsExactPreviousStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(20));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_noInterpolationWhenPreviousStatesSetCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");
        addState(1, now.plusMinutes(60), "bri:" + (DEFAULT_BRIGHTNESS + 30), "ct:" + (DEFAULT_CT + 30), "tr-before:10min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(1);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(2);

        setCurrentTimeTo(firstTrBeforeRunnable);

        firstTrBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT).build()); // sets previous state again
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(12000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(1).plusMinutes(50)); // next day

        setCurrentTimeTo(secondTrBeforeRunnable);

        secondTrBeforeRunnable.run();

        // does not set previous state again
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT + 30).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(50), initialNow.plusDays(2)); // next day

        // after power on -> perform interpolation again

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables.get(0), now, initialNow.plusMinutes(50)); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusDays(1));

        powerOnRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT + 30).transitionTime(6000).build());
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfter_performsCorrectInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:400");
        addState(1, now.plusHours(3), "ct:200", "tr-before:30min");

        setCurrentTimeTo(now.plusHours(2).plusMinutes(45)); // 15 minutes after start

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(0);

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).ct(300).build()); // correct interpolated ct value
        assertPutCall(expectedPutCall(1).ct(200).transitionTime(9000).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(3).minusMinutes(30), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnExactlyAtDefinedStart_noAdditionalPutCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(40)); // at defined start

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).build());
        assertAllPutCallsAsserted();

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfterStart_performsInterpolation_andMakesAdditionalPut() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 10).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_groupStates_lightTurnedOnAfterStart_performsInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 7, 8, 9);
        mockGroupCapabilities(5,
                LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.COLOR_TEMPERATURE)).build());
        addState("g5", now, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState("g5", now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30)); // 10 minutes after start

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(5).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 10).groupState(true).build());
        assertPutCall(expectedPutCall(5).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).groupState(true).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfterStart_correctRounding() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(32).plusSeconds(18));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 12).ct(DEFAULT_CT + 12).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(4620).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_noCtAtTarget_onlyInterpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_longDuration_multipleStates_multipleProperty_previousStateHasNoBrightness_noLongTransition() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:166", "x:0.4", "y:0.5", "hue:2000", "tr:1"); // 00:00 zero length
        addState(1, "12:00", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:00:00"); // 12:00 -> 00:00, back-to-back

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(0);
        assertScheduleStart(trBeforeRunnable, now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1), now.plusDays(1)); // zero length

        advanceCurrentTime(Duration.ofMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).ct(166).x(0.4).y(0.5).hue(2000).transitionTime(1).build());
        // no long transition expected, just warning

        ScheduledRunnable nextDay = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));

        setCurrentTimeTo(nextDay);

        nextDay.run(); // skips all put calls next day

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(3));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_noCtAtPrevious_onlyInterpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build());
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_hueSat_hueAtStart_detectedAsSameValue_noHueInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "hue:0", "sat:0");
        addState(1, now.plusMinutes(40), "hue:65535", "sat:254", "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).hue(0).sat(127).build());
        assertPutCall(expectedPutCall(1).hue(65535).sat(254).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_hueSat_hueAtMiddle_increasesValue() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "hue:32768", "sat:0");
        addState(1, now.plusMinutes(40), "hue:65535", "sat:254", "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).hue(49152).sat(127).build());
        assertPutCall(expectedPutCall(1).hue(65535).sat(254).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_hueSat_hueBeforeMiddle_decreasesValue() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "hue:16384", "sat:0");
        addState(1, now.plusMinutes(40), "hue:65535", "sat:254", "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).hue(8192).sat(127).build());
        assertPutCall(expectedPutCall(1).hue(65535).sat(254).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_xAndY_performsInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "x:0", "y:0");
        addState(1, now.plusMinutes(40), "x:1", "y:1", "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).x(0.5).y(0.5).build());
        assertPutCall(expectedPutCall(1).x(1.0).y(1.0).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_previousCT_targetHS_convertsXYToHS_removesCT_performsInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "hue:32768", "sat:254", "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);
        assertScheduleStart(trBeforeRunnable, now.plusMinutes(20), now.plusDays(1));

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).hue(19011).sat(219).build());
        assertPutCall(expectedPutCall(1).hue(32768).sat(254).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_fromTheSameDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(35), "ct:" + (DEFAULT_CT - 10));
        addState(1, now, "ct:" + DEFAULT_CT); // should be picked as previous state
        addState(1, now.plusMinutes(40), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);
        assertScheduleStart(trBeforeRunnable, now.plusMinutes(20), now.plusDays(1).minusMinutes(35));

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).ct(DEFAULT_CT + 10).build());
        assertPutCall(expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2).minusMinutes(35));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_fromTheDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(35), "ct:" + (DEFAULT_CT - 15));
        addState(1, now.minusMinutes(10), "ct:" + (DEFAULT_CT - 10), "days:DO");
        addState(1, now.minusMinutes(5), "ct:" + DEFAULT_CT); // this should be picked as previous state
        addState(1, now.plusMinutes(40), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(4);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);
        assertScheduleStart(trBeforeRunnable, now.plusMinutes(20), now.plusDays(1).minusMinutes(35));

        setCurrentTimeTo(now.plusMinutes(30));

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).ct(DEFAULT_CT + 10).build());
        assertPutCall(expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2).minusMinutes(35));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_currentStateStartedTheDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(50), "ct:" + (DEFAULT_CT - 15)); // 23:10
        addState(1, now.minusMinutes(40), "ct:" + DEFAULT_CT); // 23:20, should be picked as previous state
        addState(1, now, "ct:" + (DEFAULT_CT + 20), "tr-before:20min"); // 23:40
        setCurrentAndInitialTimeTo(now.minusMinutes(10)); // 23:30

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(0);
        assertScheduleStart(trBeforeRunnable, now, now.plusDays(1).minusMinutes(40));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1).minusMinutes(40), now.plusDays(1).minusMinutes(30));
        assertScheduleStart(scheduledRunnables.get(2), now.plusDays(1).minusMinutes(30), now.plusDays(1).minusMinutes(10));

        setCurrentTimeTo(trBeforeRunnable);
        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).ct(DEFAULT_CT + 10).build());
        assertPutCall(expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(6000).build());

        ensureRunnable(initialNow.plusDays(1).minusMinutes(10), initialNow.plusDays(2).minusMinutes(40));
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

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(1).ct(DEFAULT_CT).build());

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
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(4);
        ScheduledRunnable crossOverState = scheduledRunnables.get(0);
        assertScheduleStart(crossOverState, now, now.plusHours(7));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(7), now.plusHours(9));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(9), now.plusHours(10));
        assertScheduleStart(scheduledRunnables.get(3), now.plusHours(14), now.plusDays(1)); // ends at end of day

        advanceTimeAndRunAndAssertPutCall(crossOverState, expectedPutCall(1).ct(200).build());

        ensureRunnable(now.plusHours(10), now.plusHours(14)); // rescheduled cross over state
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnMondayAndTuesday_anotherStateBeforeOnTuesday_mondayStateEndsAtStartOfTuesdayOnlyState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + 153, "days:Mo, Tu");
        addState(1, "11:00", "ct:" + 200, "days:Tu");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now.plusHours(12), now.plusDays(1).plusHours(11));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1).plusHours(11), now.plusDays(1).plusHours(12));
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

        advanceTimeAndRunAndAssertPutCall(crossOverState, expectedPutCall(1).ct(DEFAULT_CT).build());

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

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(1).ct(DEFAULT_CT).build());

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndTuesday_scheduledNormallyOnNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(1).ct(DEFAULT_CT).build());

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
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(2).plusHours(12), now.plusDays(3));

        setCurrentTimeTo(initialNow.plusDays(1)); // Tuesday

        scheduledRunnables.get(0).run(); // already ended, state is not scheduled on Tuesday

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
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1).plusHours(12), now.plusDays(2).plusHours(12));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(11)); // Tuesday, AM

        scheduledRunnables.get(0).run(); // already ended, state is not scheduled on Tuesday; schedule next week

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
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mon,Tue,Wen,Thu,Fri,Sat,Sun");
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_twoLetterGerman() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Di,Mi,Do,Fr,Sa,So");
    }

    @Test
    void parse_canHandleSaturationInPercent_minvalue() {
        assertAppliedSaturation("0%", 0);
    }

    @Test
    void parse_canHandleSaturationInPercent_halfPercent() {
        assertAppliedSaturation("0.5%", 1);
    }

    @Test
    void parse_canHandleSaturationInPercent_tooLow_stillUsesMinvalue() {
        assertAppliedSaturation("-0.5%", 0);
    }

    @Test
    void parse_canHandleSaturationInPercent_maxValue() {
        assertAppliedSaturation("100%", 254);
    }

    @Test
    void parse_canHandleSaturationInPercent_tooHigh_stillUsesMaxValue() {
        assertAppliedSaturation("100.5%", 254);
    }

    private void assertAppliedSaturation(String input, int expected) {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "sat:" + input);

        ScheduledRunnable runnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(runnable, expectedPutCall(1).sat(expected).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleBrightnessInPercent_minValue() {
        assertAppliedBrightness("1%", 1);
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
    void parse_canHandleBrightnessInPercent_maxValue() {
        assertAppliedBrightness("100%", 254);
    }

    private void assertAppliedBrightness(String input, int expected) {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + input);

        ScheduledRunnable runnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(runnable, expectedPutCall(1).bri(expected).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY() {
        addKnownLightIdsWithDefaultCapabilities(1);
        double x = 0.6075;
        double y = 0.3525;
        addStateNow("1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(ID).x(x).y(y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_forGroups() {
        mockGroupLightsForId(1, ID);
        double x = 0.5043;
        double y = 0.6079;
        mockDefaultGroupCapabilities(1);
        addStateNow("g1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).x(x).y(y).groupState(true).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHueAndSaturation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int hue = 65535;
        int saturation = 254;
        addStateNow("1", "hue:" + hue, "sat:" + saturation);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).hue(hue).sat(saturation).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 94;
        double x = 0.2319;
        double y = 0.4675;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(bri).x(x).y(y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 100;
        addStateNow("1", "bri:" + customBrightness, "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color: 94, 186, 125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 94;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(bri).x(DEFAULT_X).y(DEFAULT_Y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 200;
        addStateNow("1", "bri:" + customBrightness, "color:94,186,125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_colorLoop() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(ID).effect("colorloop").build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_colorLoop_group() {
        mockGroupLightsForId(1, 1);
        mockDefaultGroupCapabilities(1);
        addStateNow("g1", "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).effect("colorloop").groupState(true).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_multiColorLoopEffect_group_withMultipleLights() {
        mockGroupLightsForId(1, 1, 2, 3, 4, 5, 6);
        mockDefaultGroupCapabilities(1);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();


        setLightStateResponse(1, true, true, null);
        setLightStateResponse(2, true, true, "colorloop");
        setLightStateResponse(3, true, false, "colorloop"); // ignored, because off
        setLightStateResponse(4, true, true, null); // ignored because no support for colorloop
        setLightStateResponse(5, true, true, "colorloop");
        setLightStateResponse(6, false, false, "colorloop"); // ignored, because unreachable and off
        setCurrentTimeTo(scheduledRunnable);
        runAndAssertPutCall(scheduledRunnable, expectedPutCall(1).effect("colorloop").groupState(true).build());

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2); // adjustment, and next day
        assertScheduleStart(scheduledRunnables.get(0), now.plusSeconds(multiColorAdjustmentDelay)); // first adjustment

        setCurrentTimeToAndRun(scheduledRunnables.get(0)); // turns off light 2

        assertPutCall(expectedPutCall(2).on(false).build());
        List<ScheduledRunnable> round2 = ensureScheduledStates(2);
        assertScheduleStart(round2.get(0), now.plus(300, ChronoUnit.MILLIS)); // turn on again
        assertScheduleStart(round2.get(1), now.plusSeconds(multiColorAdjustmentDelay)); // next adjustment

        setCurrentTimeToAndRun(round2.get(0)); // turns on light 2

        assertPutCall(expectedPutCall(2).effect("colorloop").on(true).build());

        setCurrentTimeToAndRun(round2.get(1)); // turns off light 5

        assertPutCall(expectedPutCall(5).on(false).build());
        List<ScheduledRunnable> round3 = ensureScheduledStates(2);
        assertScheduleStart(round3.get(0), now.plus(300, ChronoUnit.MILLIS)); // turn on again
        assertScheduleStart(round3.get(1), now.plusSeconds(multiColorAdjustmentDelay)); // next adjustment

        setCurrentTimeToAndRun(round3.get(0)); // turns on light 5

        assertPutCall(expectedPutCall(5).effect("colorloop").on(true).build());

        setCurrentTimeToAndRun(round3.get(1)); // next adjustment, no action needed
    }

    @Test
    void parse_multiColorLoopEffect_group_withMultipleLights_secondExample() {
        mockGroupLightsForId(1, 1, 2);
        mockDefaultGroupCapabilities(1);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, true, true, null);
        setLightStateResponse(2, true, true, "colorloop");
        setCurrentTimeTo(scheduledRunnable);
        runAndAssertPutCall(scheduledRunnable, expectedPutCall(1).effect("colorloop").groupState(true).build());

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now.plusSeconds(multiColorAdjustmentDelay)); // first adjustment
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1)); // next day

        setCurrentTimeToAndRun(scheduledRunnables.get(0)); // turns off light 2

        assertPutCall(expectedPutCall(2).on(false).build());
        List<ScheduledRunnable> round2 = ensureScheduledStates(1);
        assertScheduleStart(round2.get(0), now.plus(300, ChronoUnit.MILLIS)); // turn on again

        setCurrentTimeToAndRun(round2.get(0)); // turns on light 2

        assertPutCall(expectedPutCall(2).effect("colorloop").on(true).build());
    }

    @Test
    void parse_multiColorLoopEffect_justOneLightInGroup_skipsAdjustment() {
        mockGroupLightsForId(1, 1);
        mockDefaultGroupCapabilities(1);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).effect("colorloop").groupState(true).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_multiColorLoopEffect_noGroup_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow(1, "effect:multi_colorloop"));
    }

    @Test
    void parse_canHandleEffect_none() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "effect:none");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).effect("none").build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_colorInput_x_y_butLightDoesNotSupportColor_exception() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS)).build());
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "color:#ffbaff"));
    }

    @Test
    void parse_colorInput_hue_butLightDoesNotSupportColor_exception() {
        mockLightCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "hue:200"));
    }

    @Test
    void parse_colorInput_sat_butLightDoesNotSupportColor_exception() {
        mockLightCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "sat:200"));
    }

    @Test
    void parse_colorInput_effect_butLightDoesNotSupportColor_exception() {
        mockLightCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "effect:colorloop"));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_maxValue_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 6500;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).ct(153).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_minValue_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 2000;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable,
                expectedPutCall(ID).ct(500).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_ct_butLightDoesNotSupportCt_exception() {
        mockLightCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("1", "ct:200"));
    }

    @Test
    void parse_detectsOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "on:" + true);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(1).on(true).build());

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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(1), DEFAULT_PUT_CALL);

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

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

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

        runAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ScheduledRunnable nextDayState = ensureRunnable(nextDaySunset, nextNextDaySunset);

        setCurrentTimeTo(nextNextDaySunset.minusMinutes(5));

        runAndAssertPutCall(nextDayState, DEFAULT_PUT_CALL);

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

        runAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

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
    void parse_useLampNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "gKitchen Lamp";
        mockLightIdForName(name, 2);
        mockDefaultLightCapabilities(2);
        when(mockedHueApi.getGroupId(name)).thenThrow(new GroupNotFoundException("Group not found"));
        addStateNow(name, "ct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_unknownLampName_exception() {
        String unknownLightName = "Unknown Light";
        when(mockedHueApi.getGroupId(unknownLightName)).thenThrow(new GroupNotFoundException("Group not found"));
        when(mockedHueApi.getLightId(unknownLightName)).thenThrow(new LightNotFoundException("Light not found"));

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
    void parse_invalidBrightnessValue_tooLow_exception() {
        assertThrows(InvalidBrightnessValue.class, () -> addState(1, now, 0, null));
    }

    @Test
    void parse_invalidBrightnessValue_tooHigh_exception() {
        assertThrows(InvalidBrightnessValue.class, () -> addState(1, now, 255, null));
    }

    @Test
    void parse_light_invalidCtValue_tooLow_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 152));
    }

    @Test
    void parse_group_invalidCtValue_tooLow_exception() {
        mockGroupCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)).build());
        mockGroupLightsForId(1, 2, 3);

        assertThrows(InvalidColorTemperatureValue.class, () -> addState("g1", now, "ct:50"));
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_lowerThanDefault_noException() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)).build());

        addStateNow("1", "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_alsoForGroups_lowerThanDefault_noException() {
        mockGroupCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)).build());
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ct_group_noMinAndMax_noValidationPerformed() {
        mockGroupCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)).build());
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:10");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_higherThanDefault_noException() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(1000).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)).build());

        addStateNow("1", "ct:1000");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_brightness_forOnOffLight_exception() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)).build());

        assertThrows(BrightnessNotSupported.class, () -> addStateNow(1, "bri:250"));
    }

    @Test
    void parse_on_forOnOffLight() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)).build());

        addStateNow(1, "on:true");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_invalidCtValue_tooHigh_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 501));
    }

    @Test
    void parse_invalidHueValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidHueValue.class, () -> addStateNow("1", "hue:" + 65536));
    }

    @Test
    void parse_invalidHueValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidHueValue.class, () -> addStateNow("1", "hue:" + -1));
    }

    @Test
    void parse_invalidSaturationValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidSaturationValue.class, () -> addStateNow("1", "sat:" + 255));
    }

    @Test
    void parse_invalidSaturationValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidSaturationValue.class, () -> addStateNow("1", "sat:" + -1));
    }

    @Test
    void parse_invalidXAndYValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "x:" + 1.1));
    }

    @Test
    void parse_invalidXAndYValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "y:" + -0.1));
    }

    @Test
    void parse_invalidTransitionTime_tooLow_invalidProperty_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "tr:" + -1));
    }

    @Test
    void parse_invalidTransitionTime_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addStateNow("1", "tr:" + ScheduledState.MAX_TRANSITION_TIME + 1));
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
    void parse_invalidPropertyValue_invalidValue_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "effect:INVALID"));
    }

    @Test
    void run_execution_reachable_startsAgainNextDay_repeats() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(nextDayState, DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addDefaultGroupState(1, now, 1, 2, 3);
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL.toBuilder().groupState(true).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_groupState_controlIndividuallyFlagSet_multipleSinglePutCalls() {
        controlGroupLightsIndividually = true;
        create();
        addDefaultGroupState(10, now, 1, 2, 3);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, true, true, null);
        scheduledRunnable.run();
        assertPutCall(DEFAULT_PUT_CALL.toBuilder().id(1).build());
        assertPutCall(DEFAULT_PUT_CALL.toBuilder().id(2).build());
        assertPutCall(DEFAULT_PUT_CALL.toBuilder().id(3).build());

        ScheduledRunnable nextDay = ensureRunnable(now.plusDays(1));

        setCurrentTimeTo(nextDay);
        nextDay.run();
        assertPutCall(DEFAULT_PUT_CALL.toBuilder().id(1).build());
        assertPutCall(DEFAULT_PUT_CALL.toBuilder().id(2).build());
        assertPutCall(DEFAULT_PUT_CALL.toBuilder().id(3).build());

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(0));
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, nextMorning, DEFAULT_BRIGHTNESS + 100, DEFAULT_CT);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        assertScheduleStart(initialStates.get(0), now, now.plusHours(8));
        assertScheduleStart(initialStates.get(1), now.plusHours(8), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(initialStates.get(0), DEFAULT_PUT_CALL);

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
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        ScheduledRunnable nextDayState = initialStates.get(1);
        assertScheduleStart(initialStates.get(0), now, now.plusDays(1).minusHours(1));
        assertScheduleStart(nextDayState, now.plusDays(1).minusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(nextDayState, DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(2).minusHours(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addDefaultState(1, now);
        addDefaultState(1, now.plusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        assertScheduleStart(initialStates.get(0), now, now.plusHours(1));
        assertScheduleStart(initialStates.get(1), now.plusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertPutCall(initialStates.get(0), DEFAULT_PUT_CALL);

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));

        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(1));
    }

    @Test
    void run_execution_setsStateAgainAfterPowerOnEvent() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        setCurrentTimeTo(scheduledRunnable);
        runAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCall(powerOnRunnable, DEFAULT_PUT_CALL);
    }

    @Test
    void run_execution_multipleStates_stopsPowerOnEventIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(ID, secondStateStart, brightness2, ct2);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        setCurrentTimeTo(initialStates.get(0));
        runAndAssertPutCall(initialStates.get(0), DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusSeconds(10)); // next day runnable

        setCurrentTimeTo(secondStateStart);

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable.run();  /* this aborts without any api calls, as the current state already ended */

        /* run and assert second state: */

        advanceTimeAndRunAndAssertPutCall(initialStates.get(1), expectedPutCall(ID).bri(brightness2).ct(ct2).build());

        ensureRunnable(secondStateStart.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_allStatesInTheFuture_schedulesLastStateImmediately_setsEndToFirstStateOfDay() {
        addDefaultState(1, now.plusMinutes(5));
        addDefaultState(1, now.plusMinutes(10));
        startScheduler();

        List<ScheduledRunnable> states = ensureScheduledStates(2);
        assertScheduleStart(states.get(0), now, now.plusMinutes(5)); // cross over
        assertScheduleStart(states.get(1), now.plusMinutes(5), now.plusMinutes(10)); // first state

        // run cross over state
        advanceTimeAndRunAndAssertPutCall(states.get(0), DEFAULT_PUT_CALL);

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
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertPutCall(initialStates.get(0), DEFAULT_PUT_CALL);
        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10));

        setCurrentTimeTo(secondStateStart);
        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable.run(); // aborts and does not call any api calls

        advanceTimeAndRunAndAssertPutCall(nextDayRunnable, DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10));
    }

    @Test
    void run_execution_powerOnRunnableScheduledAfterStateIsSet() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);
        ensureNextDayRunnable(); // next day

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void run_execution_triesAgainAfterPowerOn() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        runAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCall(powerOnRunnable, DEFAULT_PUT_CALL);
    }

    @Test
    void run_execution_putApiConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        getPutStateMock().thenThrow(new BridgeConnectionFailure("Failed test connection"));
        setCurrentTimeToAndRun(scheduledRunnable); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        mockPutReturnValue(true);
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putInvalidApiResponse_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        getPutStateMock().thenThrow(new HueApiFailure("Invalid response"));
        setCurrentTimeToAndRun(scheduledRunnable); // failes but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        mockPutReturnValue(true);
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_getConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        getPutStateMock().thenThrow(new BridgeConnectionFailure("Failed test connection"));
        setCurrentTimeTo(scheduledRunnable);
        runAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);  // fails on GET, retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        mockPutReturnValue(true);
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putSuccessful_triesAgainAfterPowerOn() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        runAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();
        advanceTimeAndRunAndAssertPutCall(powerOnRunnable, DEFAULT_PUT_CALL);
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

        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(1).on(false).build());

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_off() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCall(scheduledRunnable,
                expectedPutCall(1).on(false).build());

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_offState_doesNotRetryAfterPowerOn() {
        addOffState();

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCall(scheduledRunnable, expectedPutCall(1).on(false).build());

        ensureNextDayRunnable();

        simulateLightOnEvent(0);
    }

    @Test
    void run_execution_multipleStates_userChangedStateManuallyBetweenStates_secondStateIsNotApplied_untilPowerCycle_detectsManualChangesAgainAfterwards() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);
        addState(1, now.plusHours(3), DEFAULT_BRIGHTNESS + 30, DEFAULT_CT);
        addState(1, now.plusHours(4), DEFAULT_BRIGHTNESS + 40, DEFAULT_CT);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(5);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);
        ScheduledRunnable fifthState = scheduledRunnables.get(4);

        // first state is set normally
        advanceTimeAndRunAndAssertPutCall(firstState, DEFAULT_PUT_CALL);
        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> update skipped and retry scheduled
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5) // modified
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode("CT"));
        setCurrentTimeTo(secondState);

        secondState.run(); // detects change, sets manually changed flag

        ensureScheduledStates(0);

        setCurrentTimeTo(thirdState);

        thirdState.run(); // directly skipped

        ensureScheduledStates(0);

        // simulate power on -> sets enforce flag, rerun third state
        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(3);

        powerOnEvents.get(0).run(); // temporary, already ended
        powerOnEvents.get(1).run(); // already ended
        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // second state, for next day

        // re-run third state after power on -> applies state as state is enforced
        advanceTimeAndRunAndAssertPutCall(powerOnEvents.get(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT).build());
        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(3)); // third state, for next day

        // no modification detected, fourth state set normally
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 20)
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode("CT"));
        setCurrentTimeTo(fourthState);

        runAndAssertPutCall(fourthState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(3), initialNow.plusDays(1).plusHours(4)); // fourth state, for next day

        // second modification detected, fifth state skipped again
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode("CT"));
        setCurrentTimeTo(fifthState);

        fifthState.run(); // detects manual modification again

        ensureScheduledStates(0);

        verify(mockedHueApi, times(3)).getLightState(1);
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(5);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);
        ScheduledRunnable fifthState = scheduledRunnables.get(4);

        // first state is set normally
        advanceTimeAndRunAndAssertPutCall(firstState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).groupState(true).build());
        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified group state between first and second state -> update skipped and retry scheduled
        LightState userModifiedLightState = expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                           .colormode("CT")
                                                           .build();
        LightState sameAsFirst = expectedState().brightness(DEFAULT_BRIGHTNESS)
                                                .colormode("CT")
                                                .build();
        addGroupStateResponses(1, sameAsFirst, userModifiedLightState);
        setCurrentTimeTo(secondState);

        secondState.run(); // detects change, sets manually changed flag

        ensureScheduledStates(0);

        setCurrentTimeTo(thirdState);

        thirdState.run(); // directly skipped

        ensureScheduledStates(0);

        // simulate power on -> sets enforce flag, rerun third state
        simulateLightOnEvent("/groups/1");

        List<ScheduledRunnable> powerOnEvents = ensureScheduledStates(3);

        powerOnEvents.get(0).run(); // temporary, already ended
        powerOnEvents.get(1).run(); // already ended
        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // second state, for next day

        // re-run third state after power on -> applies state as state is enforced
        advanceTimeAndRunAndAssertPutCall(powerOnEvents.get(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).groupState(true).build());
        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(3)); // third state, for next day

        // no modification detected, fourth state set normally
        LightState sameStateAsThird = expectedState().brightness(DEFAULT_BRIGHTNESS + 20)
                                                     .colormode("CT")
                                                     .build();
        addGroupStateResponses(1, sameStateAsThird, sameStateAsThird);
        setCurrentTimeTo(fourthState);

        runAndAssertPutCall(fourthState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).groupState(true).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(3), initialNow.plusDays(1).plusHours(4)); // fourth state, for next day

        // second modification detected, fifth state skipped again
        LightState secondUserModification = expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                           .colormode("CT")
                                                           .build();
        addGroupStateResponses(1, secondUserModification, secondUserModification);
        setCurrentTimeTo(fifthState);

        fifthState.run(); // detects manual modification again

        ensureScheduledStates(0);

        verify(mockedHueApi, times(3)).getGroupStates(1);
    }

    @Test
    void run_execution_manualOverride_stateIsDirectlyScheduledWhenOn() {
        enableUserModificationTracking();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden("/lights/" + ID); // start with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureScheduledStates(0);

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
        mockAssignedGroups(lightId, Collections.singletonList(groupId));

        addState("g" + groupId, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(lightId, now, "ct:" + DEFAULT_CT);
        manualOverrideTracker.onManuallyOverridden("/groups/" + groupId);
        manualOverrideTracker.onManuallyOverridden("/lights/" + lightId);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        scheduledRunnables.get(0).run();
        scheduledRunnables.get(1).run();

        ensureScheduledStates(0);

        simulateLightOnEvent("/lights/" + lightId); // non-physical light on event

        ensureScheduledStates(1); // only light is rescheduled, assigned group is ignored
    }

    @Test
    void run_execution_twoGroups_manualOverride_bothRescheduledWhenContainedLightIsTurnedOnPhysically() {
        enableUserModificationTracking();
        int groupId1 = 5;
        int groupId2 = 6;
        int lightId = 77;
        mockDefaultGroupCapabilities(groupId1);
        mockDefaultGroupCapabilities(groupId2);
        mockDefaultLightCapabilities(lightId);
        mockGroupLightsForId(5, lightId);
        mockGroupLightsForId(6, lightId);
        mockAssignedGroups(lightId, Arrays.asList(groupId1, groupId2)); // light is part of two groups

        addState("g" + groupId1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g" + groupId2, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(lightId, now, "ct:" + DEFAULT_CT);
        manualOverrideTracker.onManuallyOverridden("/groups/" + groupId1);
        manualOverrideTracker.onManuallyOverridden("/groups/" + groupId2);
        manualOverrideTracker.onManuallyOverridden("/lights/" + lightId);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);

        scheduledRunnables.get(0).run();
        scheduledRunnables.get(1).run();
        scheduledRunnables.get(2).run();

        ensureScheduledStates(0);

        scheduler.getHueEventListener().onLightOn("/lights/" + lightId, null, true);

        ensureScheduledStates(3); // individual light, as well as contained groups are rescheduled
    }

    @Test
    void run_execution_manualOverride_stateIsDirectlyScheduledWhenOn_calculatesCorrectNextStart() {
        enableUserModificationTracking();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden("/lights/" + ID); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureScheduledStates(0);

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void run_execution_manualOverride_forGroup_stateIsDirectlyScheduledWhenOn_calculatesCorrectNextStart() {
        enableUserModificationTracking();

        addDefaultGroupState(2, now, 1);
        manualOverrideTracker.onManuallyOverridden("/groups/2"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureScheduledStates(0);

        simulateLightOnEvent("/groups/2");
        ensureScheduledStates(1);
    }

    @Test
    void run_execution_manualOverride_multipleStates_powerOnAfterNextDayStart_beforeNextState_reschedulesImmediately() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);

        firstState.run(); // rescheduled when power on

        ensureScheduledStates(0);

        setCurrentTimeTo(initialNow.plusDays(1).plusMinutes(30)); // after next day start of state, but before next day start of second state

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run();

        ensureRunnable(now, now.plusMinutes(30)); // state should be scheduled immediately to fill in the remaining gap until the second state
    }

    @Test
    void run_execution_manualOverride_multipleStates_powerOnAfterNextDayStart_afterNextState_rescheduledNextDay() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);

        firstState.run(); // rescheduled when power on

        ensureScheduledStates(0);

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1).plusMinutes(30)); // after start of next day for first state, but after start of next state

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run();

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(1));
    }

    @Test
    void run_execution_manualOverride_forDynamicSunTimes_turnedOnEventOnlyNextDay_correctlyReschedulesStateOnSameDay() {
        enableUserModificationTracking();
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        setCurrentAndInitialTimeTo(sunrise);

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "sunrise+60", "bri:" + (DEFAULT_BRIGHTNESS + 10));
        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        setCurrentTimeTo(firstState);
        firstState.run();
        setCurrentTimeTo(secondState);
        secondState.run();

        ensureScheduledStates(0); // nothing scheduled, as both wait for power on

        setCurrentTimeTo(initialNow.plusDays(1).minusHours(1)); // next day, one hour before next schedule
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);

        powerOnRunnables.get(0).run(); // already ended, but schedules the same state again for the same day sunrise

        ensureRunnable(nextDaySunrise, nextDaySunrise.plusHours(1));
    }

    @Test
    void run_execution_manualOverride_multipleStates_detectsChangesIfMadeDuringCrossOverState() {
        enableUserModificationTracking();

        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + DEFAULT_CT, "force:false"); // force:false = default

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable crossOverState = scheduledRunnables.get(0);
        ScheduledRunnable firstState = scheduledRunnables.get(1);

        assertScheduleStart(crossOverState, now, now.plusHours(1));
        assertScheduleStart(firstState, now.plusHours(1), now.plusHours(2));

        advanceTimeAndRunAndAssertPutCall(crossOverState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT).build()); // runs through normally

        ScheduledRunnable secondState = ensureRunnable(now.plusHours(2), now.plusDays(1).plusHours(1));

        // user modified light state before first state -> update skipped and retry scheduled
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                .colorTemperature(DEFAULT_CT));

        setCurrentTimeToAndRun(firstState); // no update, as modification was detected

        ensureScheduledStates(0);

        setCurrentTimeToAndRun(secondState);  // no update, as modification was detected

        ensureScheduledStates(0); // still overridden
    }

    @Test
    void run_execution_manualOverride_forceProperty_true_ignored() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10), "force:true");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCall(firstState, DEFAULT_PUT_CALL);
        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> ignored since force:true is set
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                .colorTemperature(DEFAULT_CT));

        advanceTimeAndRunAndAssertPutCall(secondState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build()); // enforced despite user changes

        ensureRunnable(initialNow.plusHours(1).plusDays(1), initialNow.plusDays(2)); // for next day
    }

    @Test
    void run_execution_manualOverride_secondPutCallNotSuccessful_doesNotDetectChangesForThirdState() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(1, now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20));
        addState(1, now.plusHours(3), "bri:" + (DEFAULT_BRIGHTNESS + 30));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(4);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);

        assertScheduleStart(firstState, now);
        assertScheduleStart(secondState, now.plusHours(1));
        assertScheduleStart(thirdState, now.plusHours(2));
        assertScheduleStart(fourthState, now.plusHours(3));

        advanceTimeAndRunAndAssertPutCall(firstState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());
        ensureRunnable(initialNow.plusDays(1)); // for next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS)); // same as first

        mockPutReturnValue(false); // put call fails for second state
        advanceTimeAndRunAndAssertPutCall(secondState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build());
        ensureRunnable(initialNow.plusDays(1).plusHours(1)); // for next day
        mockPutReturnValue(true); // api call works again

        setCurrentTimeTo(thirdState);
        thirdState.run(); // no put call expected, as light has been set to off
        assertAllPutCallsAsserted();

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(3);
        assertScheduleStart(powerOnRunnables.get(0), now, initialNow.plusHours(1)); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusHours(2)); // already ended
        assertScheduleStart(powerOnRunnables.get(2), now, initialNow.plusHours(3));
        // light state is still like first state -> not recognized as override as last seen state has not been updated through second state

        runAndAssertPutCall(powerOnRunnables.get(2), expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 20)); // same as third

        advanceTimeAndRunAndAssertPutCall(fourthState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(3)); // for next day
    }

    @Test
    void run_execution_manualOverride_firstCallFailed_powerOn_lastSeenCorrectlySet_usedForModificationTracking_detectedCorrectly() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        mockPutReturnValue(false);
        firstState.run(); // not marked as seen
        mockPutReturnValue(true);

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1)); // next day

        // Power on -> reruns first state

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now, now.plusHours(1));

        powerOnRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());

        // Second state -> detects manual override

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - 10)); // overriden

        setCurrentTimeTo(secondState);
        secondState.run(); // detected as overridden -> no put calls or next day runnable
        assertAllPutCallsAsserted();
        ensureScheduledStates(0);
    }

    @Test
    void run_execution_manualOverride_transitionTimeBefore_longDuration_detectsChangesCorrectly() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(6), "bri:" + (DEFAULT_BRIGHTNESS + 50), "tr-before:6h");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(0);
        assertScheduleStart(trBeforeRunnable, now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1), now.plusDays(1)); // zero length

        trBeforeRunnable.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 14).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build()); // first split call

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(2);
        ScheduledRunnable secondSplit = followUpStates.get(0);
        assertScheduleStart(secondSplit, now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)); // next split
        assertScheduleStart(followUpStates.get(1), now.plusDays(1), now.plusDays(2)); // next day

        // second split -> no override detected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 14)); // same as first

        setCurrentTimeTo(secondSplit);
        secondSplit.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 28).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        ScheduledRunnable thirdSplit = ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // third split -> still no override detected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 28)); // same as second

        setCurrentTimeTo(thirdSplit);
        thirdSplit.run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 41).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER).build());

        ScheduledRunnable fourthSplit = ensureRunnable(now.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // fourth split -> detects override

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 42)); // overridden

        setCurrentTimeTo(fourthSplit);
        fourthSplit.run(); // detects override

        // simulate light on -> re run fourth (and last) split

        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(4);
        assertScheduleStart(powerOnEvents.get(0), now, initialNow.plus(ScheduledState.MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)); // already ended
        assertScheduleStart(powerOnEvents.get(1), now, initialNow.plus(ScheduledState.MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS)); // already ended
        assertScheduleStart(powerOnEvents.get(2), now, initialNow.plus(ScheduledState.MAX_TRANSITION_TIME_MS * 3, ChronoUnit.MILLIS)); // already ended
        assertScheduleStart(powerOnEvents.get(3), now, initialNow.plusDays(1)); // fourth split again

        powerOnEvents.get(3).run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 42).build()); // interpolated call
        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 50).transitionTime(36000).build()); // last split part
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

        simulateLightOnEvent(0);
    }

    @Test
    void run_execution_offState_forceProperty_reschedulesOffState_alsoIfAlreadyOverridden() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "force:true");
        manualOverrideTracker.onManuallyOverridden("/lights/1"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        scheduledRunnable.run(); // overridden -> reschedules on power on
        assertAllPutCallsAsserted();

        // first power on after overridden -> turned off again

        ScheduledRunnable powerOnRunnable1 = simulateLightOnEventExpectingSingleScheduledState(now, now.plusDays(1));

        powerOnRunnable1.run();

        assertPutCall(expectedPutCall(1).on(false).build());

        ScheduledRunnable nextDay = ensureRunnable(now.plusDays(1)); // next day

        // next day -> runs through normally

        setCurrentTimeTo(nextDay);

        nextDay.run();

        assertPutCall(expectedPutCall(1).on(false).build());

        ensureRunnable(now.plusDays(1)); // next day

        // second power on after normal run through -> also rescheduled for power on

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);
        assertScheduleStart(powerOnRunnables.get(0), now, now); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, now.plusDays(1));

        powerOnRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).on(false).build());
    }

    @Test
    void run_execution_offState_putReturnedFalse_signaledOff_offStateIsNotRescheduledWhenOn_skippedAllTogether() {
        addOffState();

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        mockPutReturnValue(false);
        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, expectedPutCall(ID).on(false).build());
        mockPutReturnValue(true);

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1)); // next day

        setCurrentTimeTo(nextDayRunnable);
        nextDayRunnable.run();
        assertAllPutCallsAsserted();

        ensureRunnable(initialNow.plusDays(2)); // next day

        // no power-on events have been scheduled

        simulateLightOnEvent(0);
    }

    @Test
    void run_execution_putReturnsFalse_signalsLightIsOff_skipsNextState_untilPowerOnEvent() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(1, now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);

        mockPutReturnValue(false);
        advanceTimeAndRunAndAssertPutCall(firstState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());
        ensureRunnable(initialNow.plusDays(1)); // for next day
        mockPutReturnValue(true);

        // second state is skipped

        setCurrentTimeTo(secondState);
        secondState.run(); // no put calls expected
        assertAllPutCallsAsserted();

        // simulate light turning on -> re runs second state

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(2);

        assertScheduleStart(powerOnRunnables.get(0), now, initialNow.plusHours(1)); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusHours(2));

        powerOnRunnables.get(0).run(); // already ended
        powerOnRunnables.get(1).run();

        assertPutCall(expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(1)); // for next day

        // third state is run normally again

        advanceTimeAndRunAndAssertPutCall(thirdState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day
    }

    @Test
    void run_execution_putReturnsFalse_lightConsideredOff_updatesSkipped_untilStateHasOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(1, now.plusHours(2), "on:true", "bri:" + (DEFAULT_BRIGHTNESS + 20));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);

        mockPutReturnValue(false);
        advanceTimeAndRunAndAssertPutCall(firstState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());
        ensureRunnable(initialNow.plusDays(1)); // for next day
        mockPutReturnValue(true);

        // second state is skipped

        setCurrentTimeTo(secondState);
        secondState.run(); // no put calls expected
        assertAllPutCallsAsserted();

        // third state has "on:true" property -> is run normally again

        advanceTimeAndRunAndAssertPutCall(thirdState, expectedPutCall(1).on(true).bri(DEFAULT_BRIGHTNESS + 20).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(3);
        assertScheduleStart(powerOnRunnables.get(0), now, initialNow.plusHours(1)); // already ended
        assertScheduleStart(powerOnRunnables.get(1), now, initialNow.plusHours(2)); // already ended
        assertScheduleStart(powerOnRunnables.get(2), now, initialNow.plusDays(1)); // third state again

        powerOnRunnables.get(0).run();
        powerOnRunnables.get(1).run();
        ensureRunnable(initialNow.plusDays(1).plusHours(1)); // next day for skipped second state

        advanceTimeAndRunAndAssertPutCall(powerOnRunnables.get(2), expectedPutCall(1).on(true).bri(DEFAULT_BRIGHTNESS + 20).build());
    }

    private static PutCall.PutCallBuilder expectedPutCall(int id) {
        return PutCall.builder().id(id);
    }

    private LightState.LightStateBuilder expectedState() {
        return LightState.builder()
                         .on(true)
                         .reachable(true)
                         .lightCapabilities(defaultCapabilities);
    }
}
