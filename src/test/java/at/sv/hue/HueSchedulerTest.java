package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.time.InvalidStartTimeExpression;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
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
    private static final int DEFAULT_CT = 500;
    private static final double DEFAULT_X = 0.2318731647393379;
    private static final double DEFAULT_Y = 0.4675382426015799;
    private static final LightState REACHABLE = LightState.builder().on(true).reachable(true).build();
    private static final LightState UNREACHABLE = LightState.builder().reachable(false).on(true).build();
    private static final LightState REACHABLE_BUT_OFF = LightState.builder().reachable(true).on(false).build();
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
            log.info("New time: {} ({})", newTime, DayOfWeek.from(newTime));
        }
        now = newTime;
    }

    private void setCurrentAndInitialTimeTo(ZonedDateTime dateTime) {
        initialNow = now = dateTime;
        log.info("Initial time: {}", now);
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
                0, connectionFailureRetryDelay, multiColorAdjustmentDelay);
        manualOverrideTracker = scheduler.getManualOverrideTracker();
    }

    private void addState(int id, ZonedDateTime startTime) {
        addState(id, startTime, DEFAULT_BRIGHTNESS, DEFAULT_CT);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct) {
        addState(id, startTime, brightness, ct, null);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct, Boolean on) {
        addKnownLightIdsWithDefaultCapabilities(id);
        addState(id, startTime, "bri:" + brightness, "ct:" + ct, "on:" + on);
    }

    private void addGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        addGroupLightsForId(groupId, lights);
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

    private void addLightIdForName(String name, int id) {
        when(mockedHueApi.getLightName(id)).thenReturn(name);
    }

    private void addGroupLightsForId(int groupId, Integer... lights) {
        when(mockedHueApi.getGroupLights(groupId)).thenReturn(Arrays.asList(lights));
    }

    private void addGroupIdForName(String name, int id) {
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
        advanceTimeAndRunAndAssertReachablePutCall(state, DEFAULT_PUT_CALL);

        ensureNextDayRunnable();
    }

    private void advanceTimeAndRunAndAssertReachablePutCall(ScheduledRunnable scheduledRunnable, PutCall putCall) {
        setCurrentTimeTo(scheduledRunnable);

        runAndAssertReachablePutCall(scheduledRunnable, putCall);
    }

    private void runAndAssertReachablePutCall(ScheduledRunnable state, PutCall expectedPutCall) {
        runAndAssertPutCall(state, REACHABLE, expectedPutCall);
    }

    private void runAndAssertPutCall(ScheduledRunnable state, LightState lightState, PutCall putCall) {
        addLightStateResponse(ID, lightState);

        state.run();

        assertPutCall(putCall);
    }

    private void runAndAssertPutCall(ScheduledRunnable state, PutCall putCall) {
        state.run();

        assertPutCall(putCall);
    }

    private void assertPutCall(PutCall putCall) {
        orderVerifier.verify(mockedHueApi, calls(1)).putState(putCall.id, putCall.bri,
                putCall.ct, putCall.x, putCall.y, putCall.hue, putCall.sat, putCall.effect, putCall.on,
                putCall.transitionTime, putCall.groupState);
    }

    private void addLightStateResponse(int id, LightState lightState) {
        when(mockedHueApi.getLightState(id)).thenReturn(lightState);
    }

    private void addLightStateResponse(int id, boolean reachable, boolean on, String effect) {
        LightState lightState = LightState.builder()
                                          .brightness(DEFAULT_BRIGHTNESS)
                                          .colorTemperature(DEFAULT_CT)
                                          .effect(effect)
                                          .reachable(reachable)
                                          .on(on)
                                          .build();
        addLightStateResponse(id, lightState);
    }

    private void ensureNextDayRunnable() {
        ensureNextDayRunnable(initialNow);
    }

    private void ensureNextDayRunnable(ZonedDateTime now) {
        ensureRunnable(now.plusDays(1), now.plusDays(2));
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
        Duration between = Duration.between(endExclusive, state.getEnd());
        assertThat("Schedule end differs. Difference: " + between + ". Start: " + state.getStart(), state.getEnd(), is(endExclusive.minusSeconds(1)));
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start) {
        Duration between = Duration.between(start, state.getStart());
        assertThat("Schedule start differs. Difference: " + between + ". End: " + state.getEnd(), state.getStart(), is(start));
    }

    private void addKnownLightIdsWithDefaultCapabilities(Integer... ids) {
        Arrays.stream(ids).forEach(this::mockDefaultCapabilities);
    }

    private void mockCapabilities(int id, LightCapabilities capabilities) {
        when(mockedHueApi.getLightCapabilities(id)).thenReturn(capabilities);
    }

    private void mockDefaultCapabilities(int id) {
        mockCapabilities(id, defaultCapabilities);
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

    private void simulateLightOnEvent() {
        scheduler.getHueEventListener().onLightOn(ID, null);
    }

    private ScheduledRunnable simulateLightOnEventAndEnsureSingleScheduledState() {
        simulateLightOnEvent();
        return ensureScheduledStates(1).get(0);
    }

    private void mockPutReturnValue(boolean value) {
        getPutStateMock().thenReturn(value);
    }

    private OngoingStubbing<Boolean> getPutStateMock() {
        return when(mockedHueApi.putState(anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()));
    }

    private void enableUserModificationTracking() {
        disableUserModificationTracking = false;
    }

    @BeforeEach
    void setUp() {
        mockedHueApi = mock(HueApi.class);
        orderVerifier = inOrder(mockedHueApi);
        mockPutReturnValue(true); // defaults to true, to signal success
        setCurrentAndInitialTimeTo(ZonedDateTime.of(2021, 1, 1, 0, 0, 0,
                0, ZoneId.systemDefault()));
        startTimeProvider = new StartTimeProviderImpl(new SunTimesProviderImpl(48.20, 16.39, 165));
        stateScheduler = new TestStateScheduler();
        nowTimeString = now.toLocalTime().toString();
        connectionFailureRetryDelay = 5;
        Double[][] gamut = {{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
        defaultCapabilities = new LightCapabilities(gamut, 153, 500);
        multiColorAdjustmentDelay = 4;
        controlGroupLightsIndividually = false;
        disableUserModificationTracking = true;
        create();
    }

    @AfterEach
    void tearDown() {
        ensureScheduledStates(0);
    }

    @Test
    void run_groupState_looksUpContainingLights_addsState() {
        addGroupState(9, now, 1, 2, 3);

        startAndGetSingleRunnable(now);
    }

    @Test
    void run_groupState_andLightState_sameId_treatedDifferently_endIsCalculatedIndependently() {
        addGroupState(1, now, 1);
        addState(1, now);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now, now.plusDays(1));

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnables.get(1), DEFAULT_PUT_CALL.toBuilder().groupState(true).build());

        ensureNextDayRunnable();
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_asAdditionalCopy_becauseOfDayWrapAround() {
        addState(22, now.plusHours(1));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_asAdditionalCopy_theNextWithCorrectDelay() {
        addState(1, now.plusHours(1));
        addState(1, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusHours(2));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        setCurrentTimeTo(now.plusHours(2));
        addState(11, now.minusHours(1));

        startAndGetSingleRunnable(now, initialNow.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_sameId_differentTimes_correctlyScheduled() {
        addState(22, now);
        addState(22, now.plusHours(1));
        addState(22, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusHours(2));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2), now.plusDays(1));
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        setCurrentTimeTo(now.plusHours(3)); // 03:00
        addState(13, initialNow.plusHours(4));  // 04:00 -> scheduled in one hour
        addState(13, initialNow.plusHours(2)); // 02:00 -> scheduled immediately
        addState(13, initialNow.plusHours(1)); // 01:00 -> scheduled next day

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
    void parse_emptyGroup_exception() {
        when(mockedHueApi.getGroupLights(1)).thenThrow(new EmptyGroupException("No lights found"));

        assertThrows(EmptyGroupException.class, () -> addStateNow("g1"));
    }

    @Test
    void parse_parsesInputLine_createsMultipleStates_canHandleGroups() {
        int groupId = 9;
        addGroupLightsForId(groupId, 77);
        addKnownLightIdsWithDefaultCapabilities(1, 2);
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

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).ct(null).transitionTime(5).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:5s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(50).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits_minutes() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:109min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(65400).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_transitionTimeBefore_shiftsGivenStartByThatTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(10);
        addState(1, definedStart, "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, actualStart, actualStart.plusDays(1));

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(6000).build());

        ensureNextDayRunnable(actualStart);
    }

    @Test
    void parse_transitionTimeBefore_overNight_doesNotOverAdjustTransitionTime_returnsNullInstead() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentTimeTo(now.withHour(23).withMinute(50));
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(5);
        addState(1, definedStart, "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, actualStart, actualStart.plusDays(1));

        advanceCurrentTime(Duration.ofMinutes(10)); // here we cross over to tomorrow

        runAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).build());

        ensureNextDayRunnable(actualStart);
    }

    @Test
    void parse_transitionTimeBefore_group_lightTurnedOnLater_stillBeforeStart_transitionTimeIsShortenedToRemainingTimeBefore() {
        addGroupLightsForId(1, 1);
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(10);
        addState("g1", definedStart, "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(5));

        runAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(3000).groupState(true).build());

        ensureNextDayRunnable(actualStart);
    }

    @Test
    void parse_transitionTimeBefore_lightTurnedAfterStart_beforeTransitionTimeIgnored_normalTransitionTimeUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(10);
        addState(1, definedStart, "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10min", "tr:3s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(10));

        runAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(30).build());

        ensureNextDayRunnable(actualStart);
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

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(1).ct(DEFAULT_CT).build());

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_multipleStates_twoOnMonday_oneOnSundayAndMonday_usesOneFromSundayAsWraparound_correctEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "07:00", "ct:" + DEFAULT_CT, "days:Mo");
        addState(1, "09:00", "ct:" + DEFAULT_CT, "days:So,Mo");
        addState(1, "10:00", "ct:" + 200, "days:So,Mo");
        addState(1, "14:00", "ct:" + DEFAULT_CT, "days:Mo");
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(5);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(7));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(7), now.plusHours(9));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(9), now.plusHours(10));
        assertScheduleStart(scheduledRunnables.get(3), now.plusHours(10), now.plusHours(14));
        assertScheduleStart(scheduledRunnables.get(4), now.plusHours(14), now.plusDays(1));
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(12));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(12), now.plusDays(1));
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

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(1).ct(DEFAULT_CT).build());

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndTuesday_scheduledNormallyOnNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(1).ct(DEFAULT_CT).build());

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
    void parse_canHandleColorInput_viaXAndY() {
        addKnownLightIdsWithDefaultCapabilities(1);
        double x = 0.6075;
        double y = 0.3525;
        addStateNow("1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(ID).x(x).y(y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_forGroups() {
        addGroupLightsForId(1, ID);
        double x = 0.5043;
        double y = 0.6079;
        addStateNow("g1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
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

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).hue(hue).sat(saturation).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 94;
        double x = 0.2318731647393379;
        double y = 0.4675382426015799;
        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).bri(bri).x(x).y(y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 100;
        addStateNow("1", "bri:" + customBrightness, "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color: 94, 186, 125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 94;
        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).bri(bri).x(DEFAULT_X).y(DEFAULT_Y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 200;
        addStateNow("1", "bri:" + customBrightness, "color:94,186,125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_colorLoop() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(ID).effect("colorloop").build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_colorLoop_group() {
        addGroupLightsForId(1, 1);
        addStateNow("g1", "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).effect("colorloop").groupState(true).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_multiColorLoopEffect_group_withMultipleLights() {
        addGroupLightsForId(1, 1, 2, 3, 4, 5, 6);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();


        addLightStateResponse(1, true, true, null);
        addLightStateResponse(2, true, true, "colorloop");
        addLightStateResponse(3, true, false, "colorloop"); // ignored, because off
        addLightStateResponse(4, true, true, null); // ignored because no support for colorloop
        addLightStateResponse(5, true, true, "colorloop");
        addLightStateResponse(6, false, false, "colorloop"); // ignored, because unreachable and off
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
        addGroupLightsForId(1, 1, 2);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        addLightStateResponse(1, true, true, null);
        addLightStateResponse(2, true, true, "colorloop");
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
        addGroupLightsForId(1, 1);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
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

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).effect("none").build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_colorInput_x_y_butLightDoesNotSupportColor_exception() {
        mockCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "color:#ffbaff"));
    }

    @Test
    void parse_colorInput_hue_butLightDoesNotSupportColor_exception() {
        mockCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "hue:200"));
    }

    @Test
    void parse_colorInput_sat_butLightDoesNotSupportColor_exception() {
        mockCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "sat:200"));
    }

    @Test
    void parse_colorInput_effect_butLightDoesNotSupportColor_exception() {
        mockCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "effect:colorloop"));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 6500;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable,
                expectedPutCall(ID).ct(153).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_ct_butLightDoesNotSupportCt_exception() {
        mockCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("1", "ct:200"));
    }

    @Test
    void parse_detectsOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "on:" + true);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(1).on(true).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_sunset_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        ZonedDateTime sunset = getSunset(now);
        ZonedDateTime nextDaySunset = getSunset(now.plusDays(1));
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(sunset.minusHours(1));
        addState(1, now); // one hour before sunset
        addState(1, "sunset", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnables.get(1), DEFAULT_PUT_CALL);

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

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, DEFAULT_PUT_CALL);

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

        runAndAssertReachablePutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ScheduledRunnable nextDayState = ensureRunnable(nextDaySunset, nextNextDaySunset);

        setCurrentTimeTo(nextNextDaySunset.minusMinutes(5));

        runAndAssertReachablePutCall(nextDayState, DEFAULT_PUT_CALL);

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

        runAndAssertReachablePutCall(scheduledRunnable, DEFAULT_PUT_CALL);

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
        addLightIdForName(name, 2);
        mockDefaultCapabilities(2);
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
        addGroupIdForName(name, id);
        addGroupLightsForId(id, 1, 2);
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
    void parse_invalidCtValue_tooLow_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 152));
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_lowerThanDefault_noException() {
        mockCapabilities(1, new LightCapabilities(null, 100, 200));

        addStateNow("1", "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_higherThanDefault_noException() {
        mockCapabilities(1, new LightCapabilities(null, 100, 1000));

        addStateNow("1", "ct:1000");

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
    void parse_invalidTransitionTime_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addStateNow("1", "tr:" + -1));
    }

    @Test
    void parse_invalidTransitionTime_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addStateNow("1", "tr:" + 65536));
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

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertReachablePutCall(nextDayState, DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addGroupState(1, now, 1, 2, 3);
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, DEFAULT_PUT_CALL.toBuilder().groupState(true).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_groupState_controlIndividuallyFlagSet_multipleSinglePutCalls() {
        controlGroupLightsIndividually = true;
        create();
        addGroupState(10, now, 1, 2, 3);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        addLightStateResponse(1, true, true, null);
        scheduledRunnable.run();
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

        advanceTimeAndRunAndAssertReachablePutCall(initialStates.get(0), DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(8));

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable powerOnEvent = simulateLightOnEventAndEnsureSingleScheduledState();

        powerOnEvent.run(); // does not call any API, as its past its end

        ensureScheduledStates(0);
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addState(1, now);
        addState(1, now.minusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        ScheduledRunnable nextDayState = initialStates.get(1);
        assertScheduleStart(initialStates.get(0), now, now.plusDays(1).minusHours(1));
        assertScheduleStart(nextDayState, now.plusDays(1).minusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertReachablePutCall(nextDayState, DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(2).minusHours(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addState(1, now);
        addState(1, now.plusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        assertScheduleStart(initialStates.get(0), now, now.plusHours(1));
        assertScheduleStart(initialStates.get(1), now.plusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertReachablePutCall(initialStates.get(0), DEFAULT_PUT_CALL);

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));

        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(1));
    }

    @Test
    void run_execution_firstUnreachable_triesAgainAfterPowerOnEvent_secondTimeReachable_success() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        setCurrentTimeTo(scheduledRunnable);
        runAndAssertPutCall(scheduledRunnable, UNREACHABLE, DEFAULT_PUT_CALL);

        ensureScheduledStates(0); // no retry, instead waiting on power on

        ScheduledRunnable powerOnRunnable = simulateLightOnEventAndEnsureSingleScheduledState();

        runAndAssertNextDay(powerOnRunnable);
    }

    @Test
    void run_execution_multipleStates_unreachable_stopsRetryingIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(ID, secondStateStart, brightness2, ct2);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        setCurrentTimeTo(initialStates.get(0));
        runAndAssertPutCall(initialStates.get(0), UNREACHABLE, DEFAULT_PUT_CALL);

        ensureScheduledStates(0); // no retry, instead waiting on power on

        setCurrentTimeTo(secondStateStart);

        ScheduledRunnable powerOnRunnable = simulateLightOnEventAndEnsureSingleScheduledState();

        powerOnRunnable.run();  /* this aborts without any api calls, as the current state already ended */

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusSeconds(10));

        /* run and assert second state: */

        advanceTimeAndRunAndAssertReachablePutCall(initialStates.get(1), expectedPutCall(ID).bri(brightness2).ct(ct2).build());

        ensureRunnable(secondStateStart.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_allStatesInTheFuture_schedulesLastStateImmediately_asCopy_butAbortsIfFirstStateOfDayStarts() {
        ZonedDateTime firstStart = now.plusMinutes(5);
        ZonedDateTime secondStart = now.plusMinutes(10);
        addState(1, firstStart);
        addState(1, secondStart);
        startScheduler();

        List<ScheduledRunnable> states = ensureScheduledStates(3);
        assertScheduleStart(states.get(0), now, now.plusMinutes(5)); // temporary copy
        assertScheduleStart(states.get(1), firstStart, now.plusMinutes(10));
        assertScheduleStart(states.get(2), secondStart, now.plusDays(1).plusMinutes(5));

        // run temporary state
        advanceTimeAndRunAndAssertReachablePutCall(states.get(0), DEFAULT_PUT_CALL);

        setCurrentTimeTo(firstStart);

        ScheduledRunnable powerOnRunnable = simulateLightOnEventAndEnsureSingleScheduledState();

        powerOnRunnable.run();

        // no next day runnable, as it was just a temporary copy
    }

    @Test
    void run_execution_multipleStates_reachable_stopsRescheduleIfNextIntervallStarts() {
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusMinutes(10);
        addState(ID, secondStateStart);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertReachablePutCall(initialStates.get(0), DEFAULT_PUT_CALL);
        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10));

        setCurrentTimeTo(secondStateStart);
        ScheduledRunnable powerOnRunnable = simulateLightOnEventAndEnsureSingleScheduledState();

        powerOnRunnable.run(); // aborts and does not call any api calls

        advanceTimeAndRunAndAssertReachablePutCall(nextDayRunnable, DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10));
    }

    @Test
    void run_execution_powerOnRunnableScheduledAfterStateIsSet() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, DEFAULT_PUT_CALL);
        ensureNextDayRunnable(); // next day

        simulateLightOnEventAndEnsureSingleScheduledState();
    }

    @Test
    void run_execution_putReturnsFalse_toSignalLightOff_butReachable_triesAgainAfterPowerOn_withoutCallingGetStatus() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        mockPutReturnValue(false);
        runAndAssertPutCall(scheduledRunnable, DEFAULT_PUT_CALL);

        ensureScheduledStates(0); // no retry, instead waiting on power on

        ScheduledRunnable powerOnRunnable = simulateLightOnEventAndEnsureSingleScheduledState();

        mockPutReturnValue(true);
        runAndAssertNextDay(powerOnRunnable);
    }

    @Test
    void run_execution_putApiConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        getPutStateMock().thenThrow(new BridgeConnectionFailure("Failed test connection"));
        setCurrentTimeToAndRun(scheduledRunnable); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        Mockito.reset(mockedHueApi);
        mockPutReturnValue(true);
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putInvalidApiResponse_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        getPutStateMock().thenThrow(new HueApiFailure("Invalid response"));
        setCurrentTimeToAndRun(scheduledRunnable); // failes but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        Mockito.reset(mockedHueApi);
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

        Mockito.reset(mockedHueApi);
        mockPutReturnValue(true);
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putSuccessful_reachable_butOff_triesAgainAfterPowerOn() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        runAndAssertPutCall(scheduledRunnable, REACHABLE_BUT_OFF, DEFAULT_PUT_CALL);

        ensureScheduledStates(0); // no retry, waiting for light on

        ScheduledRunnable powerOnRunnable = simulateLightOnEventAndEnsureSingleScheduledState();
        advanceTimeAndRunAndAssertReachablePutCall(powerOnRunnable, DEFAULT_PUT_CALL);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_notExecuted_justUsedToProvideEndToPreviousState() {
        addDefaultState();
        addNullState(now.plusMinutes(5));
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(5));

        scheduledRunnable.run(); // no API calls, as already ended

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_allInTheFuture_stillIgnored() {
        addState(1, now.plusMinutes(5));
        addNullState(now.plusMinutes(10));
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void run_execution_off_reachable_turnedOff_noConfirm() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertReachablePutCall(scheduledRunnable, expectedPutCall(1).on(false).build());

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_unreachable_treatedAsSuccess_noConfirm() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCall(scheduledRunnable, UNREACHABLE,
                expectedPutCall(1).on(false).build());

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_putReturnsFalse_retriesAfterPowerOn_secondTimeSuccess_noConfirms() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        mockPutReturnValue(false);
        runAndAssertPutCall(scheduledRunnable, expectedPutCall(1).on(false).build());

        ensureScheduledStates(0); // no retry, waiting for light on

        ScheduledRunnable powerOnRunnable = simulateLightOnEventAndEnsureSingleScheduledState();

        mockPutReturnValue(true);
        setCurrentTimeTo(powerOnRunnable);
        runAndAssertPutCall(powerOnRunnable, UNREACHABLE, expectedPutCall(1).on(false).build());

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_multipleStates_userChangedStateManuallyBetweenStates_secondStateIsNotApplied_untilPowerCycle() {
        enableUserModificationTracking();
        create();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        // first state is set normally
        advanceTimeAndRunAndAssertReachablePutCall(firstState, DEFAULT_PUT_CALL);
        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> update skipped and retry scheduled
        LightState userModifiedLightState = LightState.builder()
                                                      .brightness(DEFAULT_BRIGHTNESS + 5)
                                                      .colorTemperature(DEFAULT_CT)
                                                      .reachable(true)
                                                      .on(true)
                                                      .build();
        addLightStateResponse(1, userModifiedLightState);
        setCurrentTimeTo(secondState);

        secondState.run();

        ensureScheduledStates(0);

        simulateLightOnEvent();

        List<ScheduledRunnable> powerOnEvents = ensureScheduledStates(2);

        powerOnEvents.get(0).run(); // already ended

        advanceTimeAndRunAndAssertReachablePutCall(powerOnEvents.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT).build());

        ensureRunnable(initialNow.plusHours(1).plusDays(1), initialNow.plusDays(2)); // for next day
    }

    @Test
    void run_execution_manualOverride_stateIsDirectlyScheduledWhenOn() {
        enableUserModificationTracking();
        create();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden(ID); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureScheduledStates(0);

        simulateLightOnEventAndEnsureSingleScheduledState();
    }

    @Test
    void run_execution_manualOverride_stateIsDirectlyScheduledWhenOn_calculatesCorrectNextStart() {
        enableUserModificationTracking();
        create();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden(ID); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureScheduledStates(0);

        simulateLightOnEventAndEnsureSingleScheduledState();
    }

    @Test
    void run_execution_manualOverride_multipleStates_powerOnAfterNextDayStart_beforeNextState_reschedulesImmediately() {
        enableUserModificationTracking();
        create();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden(1); // start with overridden state

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);

        firstState.run(); // rescheduled when power on

        ensureScheduledStates(0);

        setCurrentTimeTo(initialNow.plusDays(1).plusMinutes(30)); // after next day start of state, but before next day start of second state

        ScheduledRunnable powerOnEvent = simulateLightOnEventAndEnsureSingleScheduledState();

        powerOnEvent.run();

        ensureRunnable(now, now.plusMinutes(30)); // state should be scheduled immediately to fill in the remaining gap until the second state
    }

    @Test
    void run_execution_manualOverride_multipleStates_powerOnAfterNextDayStart_afterNextState_rescheduledNextDay() {
        enableUserModificationTracking();
        create();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden(1); // start with overridden state

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);

        firstState.run(); // rescheduled when power on

        ensureScheduledStates(0);

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1).plusMinutes(30)); // after start of next day for first state, but after start of next state

        ScheduledRunnable powerOnEvent = simulateLightOnEventAndEnsureSingleScheduledState();

        powerOnEvent.run();

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(1));
    }

    @Test
    void run_execution_manualOverride_forDynamicSunTimes_turnedOnEventOnlyNextDay_correctlyReschedulesStateOnSameDay() {
        enableUserModificationTracking();
        create();
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        setCurrentAndInitialTimeTo(sunrise);

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "sunrise+60", "bri:" + (DEFAULT_BRIGHTNESS + 10));
        manualOverrideTracker.onManuallyOverridden(1); // start directly with overridden state

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
        simulateLightOnEvent();

        List<ScheduledRunnable> powerOnRunnables = ensureScheduledStates(2);

        powerOnRunnables.get(0).run(); // already ended, but schedules the same state again for the same day sunrise

        ensureRunnable(nextDaySunrise, nextDaySunrise.plusHours(1));
    }

    @Test
    void run_execution_manualOverride_multipleStates_detectsChangesIfMadeDuringTemporaryCopy() {
        enableUserModificationTracking();
        create();

        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        ScheduledRunnable temporaryCopy = scheduledRunnables.get(0);
        ScheduledRunnable firstState = scheduledRunnables.get(1);
        ScheduledRunnable secondState = scheduledRunnables.get(2);

        advanceTimeAndRunAndAssertReachablePutCall(temporaryCopy,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT).build()); // runs through normally

        // user modified light state before first state -> update skipped and retry scheduled
        LightState userModifiedLightState = LightState.builder()
                                                      .brightness(DEFAULT_BRIGHTNESS + 5)
                                                      .colorTemperature(DEFAULT_CT)
                                                      .reachable(true)
                                                      .on(true)
                                                      .build();
        addLightStateResponse(1, userModifiedLightState);

        setCurrentTimeToAndRun(firstState); // no update, as modification was detected

        ensureScheduledStates(0);

        setCurrentTimeToAndRun(secondState);  // no update, as modification was detected

        ensureScheduledStates(0); // still overridden
    }

    @Test
    void run_execution_manualOverride_forceProperty_ignored() {
        enableUserModificationTracking();
        create();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1),"bri:" + (DEFAULT_BRIGHTNESS + 10), "force:true");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertReachablePutCall(firstState, DEFAULT_PUT_CALL);
        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> update skipped and retry scheduled
        LightState userModifiedLightState = LightState.builder()
                                                      .brightness(DEFAULT_BRIGHTNESS + 5)
                                                      .colorTemperature(DEFAULT_CT)
                                                      .reachable(true)
                                                      .on(true)
                                                      .build();
        addLightStateResponse(1, userModifiedLightState);
        
        advanceTimeAndRunAndAssertReachablePutCall(secondState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).build()); // enforced despite user changes

        ensureRunnable(initialNow.plusHours(1).plusDays(1), initialNow.plusDays(2)); // for next day
    }

    @Test
    void run_execution_offState_manualOverride_offStateIsNotRescheduledWhenOn_skippedAllTogether() {
        enableUserModificationTracking();
        create();

        addOffState();
        manualOverrideTracker.onManuallyOverridden(1); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureRunnable(initialNow.plusDays(1));
    }

    private static PutCall.PutCallBuilder expectedPutCall(int lightId) {
        return PutCall.builder().id(lightId);
    }

    @Data
    @Builder(toBuilder = true)
    private static final class PutCall {
        int id;
        Integer bri;
        Integer ct;
        Double x;
        Double y;
        Integer hue;
        Integer sat;
        Boolean on;
        String effect;
        Integer transitionTime;
        boolean groupState;
    }
}
