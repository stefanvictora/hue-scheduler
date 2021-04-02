package at.sv.hue;

import at.sv.hue.api.HueApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

class HueSchedulerTest {

    private static final Logger LOG = LoggerFactory.getLogger(HueSchedulerTest.class);

    private TestStateScheduler stateScheduler;
    private HueScheduler enforcer;
    private ZonedDateTime now;
    private ZonedDateTime initialNow;
    private List<GetState> lightGetStates;
    private List<GetState> groupGetStates;
    private List<PutState> putStates;
    private List<String> lightIdLookups;
    private List<LightState> lightStateResponses;
    private List<List<Integer>> groupLightsResponses;
    private List<Integer> lightIdResponses;
    private int defaultCt;
    private int defaultBrightness;
    private int id;
    private LocalTime sunrise;
    private LocalTime nextDaySunrise;
    private LocalTime nextNextDaysSunrises;
    private boolean apiPutSuccessful;
    private int retryDelay;
    private int confirmDelay;

    private void setCurrentTimeTo(ZonedDateTime newTime) {
        if (now != null && newTime.isBefore(now)) {
            throw new IllegalArgumentException("New time is before now: " + newTime);
        }
        now = newTime;
        LOG.info("Current time: {}", now);
    }

    private void setCurrentAndInitialTimeTo(ZonedDateTime dateTime) {
        initialNow = dateTime;
        setCurrentTimeTo(initialNow);
    }

    private void advanceCurrentTime(Duration duration) {
        setCurrentTimeTo(now.plus(duration));
    }

    private void create() {
        HueApi hueApi = new HueApi() {
            @Override
            public LightState getLightState(int id) {
                lightGetStates.add(new GetState(id));
                assertFalse(lightStateResponses.isEmpty(), "TestCase mis-configured: no lightState response available");
                return lightStateResponses.remove(0);
            }

            @Override
            public boolean putState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, boolean groupState) {
                putStates.add(new PutState(id, bri, x, y, ct, on, groupState));
                return apiPutSuccessful;
            }

            @Override
            public List<Integer> getGroupLights(int groupId) {
                groupGetStates.add(new GetState(groupId));
                assertFalse(groupLightsResponses.isEmpty(), "TestCase mis-configured: no groupLights response available");
                return groupLightsResponses.remove(0);
            }

            @Override
            public String getGroupName(int groupId) {
                return "Group";
            }

            @Override
            public int getLightId(String name) {
                lightIdLookups.add(name);
                assertFalse(lightIdResponses.isEmpty(), "TestCase mis-configured: no lightId response available");
                return lightIdResponses.remove(0);
            }

            @Override
            public String getLightName(int id) {
                return "Name";
            }
        };
        enforcer = new HueScheduler(hueApi, stateScheduler, (input, dateTime) -> {
            if (input.equals("sunrise")) {
                long daysBetween = Duration.between(initialNow, dateTime).toDays();
                if (daysBetween == 0) {
                    return sunrise;
                } else if (daysBetween == 1) {
                    return nextDaySunrise;
                } else if (daysBetween >= 2) {
                    return nextNextDaysSunrises;
                }
                throw new IllegalStateException("No sunrise time for dateTime defined: " + dateTime);
            } else if (input.equals("sunrise+10")) {
                return sunrise.plusMinutes(10);
            } else {
                return LocalTime.parse(input);
            }
        }, () -> now, () -> retryDelay, confirmDelay);
    }

    private void addState(int id, ZonedDateTime start) {
        addState(id, start, defaultBrightness, defaultCt);
    }

    private void addState(int id, ZonedDateTime start, Integer brightness, Integer ct) {
        addState(id, start, brightness, ct, null);
    }

    private void addState(int id, ZonedDateTime start, Integer brightness, Integer ct, Boolean on) {
        enforcer.addState("Name", id, start.toLocalTime().toString(), brightness, ct, on);
    }

    private void addState(String input) {
        enforcer.addState(input);
    }

    private void addGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        groupLightsResponses.add(Arrays.asList(lights));
        enforcer.addGroupState("Name", groupId, start.toLocalTime().toString(), defaultBrightness, defaultCt, null);
    }

    private void addLightStateResponse(int brightness, int ct, boolean reachable, boolean on) {
        lightStateResponses.add(new LightState(brightness, ct, null, null, reachable, on));
    }

    private void startEnforcer() {
        enforcer.start();
    }

    private List<ScheduledRunnable> ensureScheduledStates(int expectedSize) {
        List<ScheduledRunnable> scheduledRunnables = stateScheduler.getScheduledStates();
        assertThat("ScheduledStates size differs", scheduledRunnables.size(), is(expectedSize));
        stateScheduler.clear();
        return scheduledRunnables;
    }

    private void assertPutState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, boolean groupState) {
        assertTrue(putStates.size() > 0, "No PUT API calls happened");
        PutState putState = putStates.remove(0);
        assertThat("Brightness differs", putState.bri, is(bri));
        assertThat("X differs", putState.x, is(x));
        assertThat("Y differs", putState.y, is(y));
        assertThat("CT differs", putState.ct, is(ct));
        assertThat("On differs", putState.on, is(on));
        assertThat("isGroupState differs", putState.groupState, is(groupState));
    }

    private void assertLightGetState(int id) {
        assertGetState(id, lightGetStates);
    }

    private void assertGroupGetState(int id) {
        assertGetState(id, groupGetStates);
    }

    private void assertGetState(int id, List<GetState> states) {
        assertTrue(states.size() > 0, "No more get states to assert");
        GetState getState = states.remove(0);
        assertThat("ID differs", getState.id, is(id));
    }

    private void assertLightIdLookup(String name) {
        assertTrue(lightIdLookups.size() > 0, "No more lightId lookups to assert");
        String lookup = lightIdLookups.remove(0);
        assertThat("Name differs", lookup, is(name));
    }

    private void addLightIdResponse(int id) {
        lightIdResponses.add(id);
    }

    private void runAndAssertConfirmations() {
        runAndAssertConfirmations(false);
    }

    private void runAndAssertConfirmations(boolean groupState) {
        runAndAssertConfirmations(id, defaultBrightness, defaultCt, groupState);
    }

    private void runAndAssertConfirmations(int id, int brightness, int ct, boolean groupState) {
        runAndAssertConfirmations(state ->
                advanceTimeAndRunAndAssertApiCalls(true, state, brightness, ct, groupState, true));
    }

    private void runAndAssertConfirmations(Consumer<ScheduledRunnable> consumer) {
        for (int i = 0; i < ScheduledState.CONFIRM_AMOUNT; i++) {
            List<ScheduledRunnable> confirmRunnable = ensureScheduledStates(1);
            ScheduledRunnable state = confirmRunnable.get(0);
            assertScheduleStart(state, now.plusSeconds(confirmDelay));

            LOG.info("Confirm state {} [{}/{}]", id, i, ScheduledState.CONFIRM_AMOUNT);
            consumer.accept(state);
        }
    }

    private void addDefaultState() {
        addState(1, now, defaultBrightness, defaultCt);
    }

    private void addNullState(ZonedDateTime start) {
        addState(1, start, null, null);
    }

    private void advanceTimeAndRunAndAssertApiCalls(boolean reachable, ScheduledRunnable state) {
        advanceTimeAndRunAndAssertApiCalls(reachable, state, false);
    }

    private void advanceTimeAndRunAndAssertApiCalls(boolean reachable, ScheduledRunnable state, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(reachable, state, defaultBrightness, defaultCt, groupState, true);
    }

    private void advanceTimeAndRunAndAssertApiCalls(boolean reachable, ScheduledRunnable state, int brightness, int ct,
                                                    boolean groupState, boolean onLightStateResponse) {
        setCurrentTimeTo(state.getStart());
        runAndAssertApiCalls(reachable, state, brightness, ct, groupState, onLightStateResponse);
    }

    private void runAndAssertApiCalls(boolean reachable, ScheduledRunnable state, int brightness, int ct, boolean groupState,
                                      boolean onLightStateResponse) {
        addLightStateResponse(brightness, ct, reachable, onLightStateResponse);
        runAndAssertPutCall(state, brightness, ct, groupState, null);
        assertLightGetState(id);
    }

    private void advanceTimeAndRunAndAssertTurnOffApiCall(boolean reachable, ScheduledRunnable state, boolean onLightStateResponse) {
        addLightStateResponse(defaultBrightness, defaultCt, reachable, onLightStateResponse);
        advanceTimeAndRunAndAssertPutCall(state, null, null, false, false);
        assertLightGetState(id);
    }

    private void advanceTimeAndRunAndAssertTurnOnApiCall(ScheduledRunnable state) {
        addLightStateResponse(defaultBrightness, defaultCt, true, true);
        advanceTimeAndRunAndAssertPutCall(state, null, null, false, true);
        assertLightGetState(id);
    }

    private void runAndAssertPutCall(ScheduledRunnable state, Integer brightness, Integer ct, boolean groupState, Boolean on) {
        state.run();

        assertPutState(id, brightness, null, null, ct, on, groupState);
    }

    private void advanceTimeAndRunAndAssertPutCall(ScheduledRunnable state, Integer brightness, Integer ct, boolean groupState, Boolean on) {
        setCurrentTimeTo(state.getStart());
        runAndAssertPutCall(state, brightness, ct, groupState, on);
    }

    private ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart) {
        ScheduledRunnable state = ensureScheduledStates(1).get(0);
        assertScheduleStart(state, scheduleStart);
        return state;
    }

    private ScheduledRunnable ensureRetryState() {
        return ensureRunnable(now.plusSeconds(retryDelay));
    }

    private void ensureAndRunSingleConfirmation(boolean reachable) {
        ScheduledRunnable runnable = ensureRunnable(now.plusSeconds(confirmDelay));

        advanceTimeAndRunAndAssertApiCalls(reachable, runnable);
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start) {
        assertThat("Schedule start differs", state.getStart(), is(start));
    }

    private void addOffState() {
        addState(1, now, null, null, false);
    }

    @BeforeEach
    void setUp() {
        apiPutSuccessful = true;
        sunrise = LocalTime.of(6, 0);
        nextDaySunrise = LocalTime.of(7, 0);
        nextNextDaysSunrises = LocalTime.of(7, 15);
        stateScheduler = new TestStateScheduler();
        setCurrentAndInitialTimeTo(ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()));
        lightGetStates = new ArrayList<>();
        putStates = new ArrayList<>();
        lightStateResponses = new ArrayList<>();
        lightIdLookups = new ArrayList<>();
        lightIdResponses = new ArrayList<>();
        groupGetStates = new ArrayList<>();
        groupLightsResponses = new ArrayList<>();
        retryDelay = 1;
        confirmDelay = 2;
        defaultCt = 500;
        defaultBrightness = 50;
        id = 1;
        create();
    }

    @AfterEach
    void tearDown() {
        assertEquals(0, lightGetStates.size(), "Not all lightGetState calls asserted");
        assertEquals(0, putStates.size(), "Not all putState calls asserted");
        assertEquals(0, lightIdLookups.size(), "Not all lightIdLookups asserted");
        assertEquals(0, lightIdResponses.size(), "Not all lightIdResponses returned");
        assertEquals(0, lightStateResponses.size(), "Not all lightStateResponses returned");
        assertEquals(0, groupGetStates.size(), "Not all groupGetState calls asserted");
        assertEquals(0, groupLightsResponses.size(), "Not all groupLightResponses returned");
        ensureScheduledStates(0);
    }

    @Test
    void run_groupState_looksUpContainingLights_addsState() {
        addGroupState(9, now, 1, 2, 3);

        assertGroupGetState(9);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
    }

    @Test
    void run_groupState_andLightState_sameId_treatedDifferently_endIsCalculatedIndependently() {
        addGroupState(1, now, 1);
        addState(1, now.plusHours(1));

        assertGroupGetState(1);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now);

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertApiCalls(true, scheduledRunnables.get(1), true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_becauseOfDayWrapAround() {
        addState(22, now.plus(1, ChronoUnit.HOURS));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_theNextWithCorrectDelay() {
        addState(1, now.plusHours(1));
        addState(1, now.plusHours(2));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        addState(11, now.minus(1, ChronoUnit.HOURS));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
    }

    @Test
    void run_multipleStates_sameId_differentTimes_correctlyScheduled() {
        addState(22, now);
        addState(22, now.plusHours(1));
        addState(22, now.plusHours(2));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2));
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        addState(13, now.plusHours(1));
        addState(13, now.minusHours(1));
        addState(13, now.minusHours(2));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(22));
    }

    @Test
    void parse_parsesInputLine_createsMultipleStates_canHandleGroups() {
        String time = now.toLocalTime().toString();
        groupLightsResponses.add(Collections.singletonList(77));
        addState("1,2,g9\t" + time + "\tbri:" + defaultBrightness + "\tct:" + defaultCt);
        assertGroupGetState(9);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now);
        assertScheduleStart(scheduledRunnables.get(2), now);

        advanceTimeAndRunAndAssertApiCalls(true, scheduledRunnables.get(1));
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_detectsOnProperty() {
        String time = now.toLocalTime().toString();
        addState("1\t" + time + "\ton:" + true);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOnApiCall(scheduledRunnables.get(0));
        runAndAssertConfirmations(this::advanceTimeAndRunAndAssertTurnOnApiCall);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_sunrise_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        setCurrentAndInitialTimeTo(now.with(sunrise).minusHours(1));
        addState(1, now); // one hour before sunrise
        addState("1\tsunrise\tbri:" + defaultBrightness + "\tct:" + defaultCt);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));

        advanceTimeAndRunAndAssertApiCalls(true, scheduledRunnables.get(1)); // sunrise state
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1).with(nextDaySunrise));
    }

    @Test
    void parse_sunrise_singleState_callsStartTimeProvider_calculatesInitialAndNextEndCorrectly() {
        setCurrentAndInitialTimeTo(now.with(sunrise));
        addState("1\tsunrise\tbri:" + defaultBrightness + "\tct:" + defaultCt);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);

        setCurrentTimeTo(initialNow.plusDays(1).with(nextDaySunrise).minusMinutes(5));

        runAndAssertApiCalls(true, scheduledRunnables.get(0), defaultBrightness, defaultCt, false, true);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(2).with(nextNextDaysSunrises));

        setCurrentTimeTo(initialNow.plusDays(2).with(nextNextDaysSunrises).minusMinutes(5));

        runAndAssertApiCalls(true, nextDayState, defaultBrightness, defaultCt, false, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(3).with(nextNextDaysSunrises));
    }

    @Test
    void parse_nullState_treatedCorrectly_notAddedAsState() {
        addState("1\tsunrise\tct:" + defaultCt);
        addState("1\tsunrise+10");
        startEnforcer();

        ensureScheduledStates(1);
    }

    @Test
    void parse_useLampNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "gKitchen Lamp";
        addLightIdResponse(2);
        addState(name + "\t12:00\tct:" + defaultCt);

        assertLightIdLookup(name);

        startEnforcer();

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
    void parse_invalidCtValue_tooHigh_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 501));
    }

    @Test
    void run_execution_reachable_runsConfirmations_startsAgainNextDay_repeats() {
        addDefaultState();
        startEnforcer();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(true, scheduledRunnables.get(0));
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(true, nextDayState);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addGroupState(10, now, 1, 2, 3);
        assertGroupGetState(10);
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(true, scheduledRunnables.get(0), true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(0));
        addState(1, now, defaultBrightness, defaultCt);
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, nextMorning, defaultBrightness + 100, defaultCt);
        startEnforcer();
        List<ScheduledRunnable> initalStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(true, initalStates.get(0));

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable confirmRunnable = ensureScheduledStates(1).get(0);

        confirmRunnable.run(); // does not call any API, as its past its end

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addState(1, now);
        addState(1, now.minusHours(1));
        startEnforcer();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        ScheduledRunnable nextDayState = initialStates.get(1);
        assertScheduleStart(nextDayState, now.plusHours(23));

        advanceTimeAndRunAndAssertApiCalls(true, nextDayState);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2).minusHours(1));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addState(1, now);
        addState(1, now.plusHours(1));
        startEnforcer();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        assertScheduleStart(initialStates.get(0), now);
        assertScheduleStart(initialStates.get(1), now.plusHours(1));

        advanceTimeAndRunAndAssertApiCalls(true, initialStates.get(0));
        runAndAssertConfirmations();

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));
        
        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_firstUnreachable_triesAgainOneSecondLater_secondTimeReachable_success() {
        addDefaultState();
        startEnforcer();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(false, scheduledRunnables.get(0));

        ScheduledRunnable retryState = ensureRetryState();

        advanceTimeAndRunAndAssertApiCalls(true, retryState);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_multipleStates_unreachable_stopsRetryingIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(id, secondStateStart, brightness2, ct2);
        startEnforcer();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(false, initialStates.get(0));

        ScheduledRunnable retryState = ensureRetryState();
        setCurrentTimeTo(secondStateStart);

        retryState.run();  /* this aborts without any api calls, as the current state already ended */

        ensureRunnable(initialNow.plusDays(1));

        /* run and assert second state: */

        advanceTimeAndRunAndAssertApiCalls(true, initialStates.get(1), brightness2, ct2, false, true);

        runAndAssertConfirmations(id, brightness2, ct2, false);

        ensureRunnable(secondStateStart.plusDays(1));
    }

    @Test
    void run_execution_multipleStates_reachable_stopsConfirmationIfNextIntervallStarts_resetsConfirms() {
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusMinutes(10);
        addState(id, secondStateStart);
        startEnforcer();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(true, initialStates.get(0));
        ensureAndRunSingleConfirmation(true);

        setCurrentTimeTo(secondStateStart);
        ScheduledRunnable furtherConfirmRunnable = ensureScheduledStates(1).get(0);

        furtherConfirmRunnable.run(); // aborts and does not call any api calls

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(true, nextDayRunnable);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_firstReachable_butDuringConfirmationUnreachableAgain_resetsConfirms() {
        addDefaultState();
        startEnforcer();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(true, scheduledRunnables.get(0));
        ensureAndRunSingleConfirmation(true);
        ensureAndRunSingleConfirmation(false);

        ScheduledRunnable retryRunnable = ensureRetryState();

        advanceTimeAndRunAndAssertApiCalls(true, retryRunnable);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_putNotSuccessful_butReachable_whichHappensWhenLightIsSoftwareBasedOff_triesAgain_withoutCallingGetStatus() {
        addDefaultState();
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        apiPutSuccessful = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), defaultBrightness, defaultCt, false, null);

        ScheduledRunnable retryState = ensureRetryState();
        apiPutSuccessful = true;
        advanceTimeAndRunAndAssertApiCalls(true, retryState);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_putSuccessful_reachable_butOff_triesAgain() {
        addDefaultState();
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(true, scheduledRunnables.get(0), defaultBrightness, defaultCt, false, false);

        ScheduledRunnable retryState = ensureRetryState();
        advanceTimeAndRunAndAssertApiCalls(true, retryState, defaultBrightness, defaultCt, false, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_notExecuted_justUsedToProvideEndToPreviousState() {
        addDefaultState();
        addNullState(now.plusMinutes(5));
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceCurrentTime(Duration.ofMinutes(5));

        scheduledRunnables.get(0).run(); // no API calls, as already ended

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_allInTheFuture_stillIgnored() {
        addState(1, now.plusMinutes(5));
        addNullState(now.plusMinutes(10));
        startEnforcer();

        ensureScheduledStates(1);
    }

    @Test
    void run_execution_off_reachable_turnedOff_noConfirm() {
        addOffState();
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOffApiCall(true, scheduledRunnables.get(0), false);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_unreachable_treatedAsSuccess_noConfirm() {
        addOffState();
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOffApiCall(false, scheduledRunnables.get(0), true);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_putFailed_retries_secondTimeSuccess_noConfirms() {
        addOffState();
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        apiPutSuccessful = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), null, null, false, false);

        ScheduledRunnable retryState = ensureRetryState();
        apiPutSuccessful = true;
        advanceTimeAndRunAndAssertTurnOffApiCall(false, retryState, true);

        ensureRunnable(initialNow.plusDays(1));
    }

    private static final class GetState {
        int id;

        public GetState(int id) {
            this.id = id;
        }
    }

    private static final class PutState {
        int id;
        Integer bri;
        Double x;
        Double y;
        Integer ct;
        Boolean on;
        boolean groupState;

        public PutState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, boolean groupState) {
            this.id = id;
            this.bri = bri;
            this.x = x;
            this.y = y;
            this.ct = ct;
            this.on = on;
            this.groupState = groupState;
        }
    }
}
