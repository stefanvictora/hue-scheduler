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
import java.time.format.DateTimeParseException;
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
    private HueScheduler scheduler;
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
    private int defaultTransitionTime;

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
            public boolean putState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, Integer transitionTime,
                                    boolean groupState) {
                putStates.add(new PutState(id, bri, x, y, ct, on, transitionTime, groupState));
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
        scheduler = new HueScheduler(hueApi, stateScheduler, (input, dateTime) -> {
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
        scheduler.addState("Name", id, start.toLocalTime().toString(), brightness, ct, null, null, on, defaultTransitionTime);
    }

    private void addState(String input) {
        scheduler.addState(input);
    }

    private void addGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        groupLightsResponses.add(Arrays.asList(lights));
        scheduler.addGroupState("Name", groupId, start.toLocalTime().toString(), defaultBrightness, defaultCt,
                null, null, null, defaultTransitionTime);
    }

    private void addLightStateResponse(boolean reachable, boolean on) {
        lightStateResponses.add(new LightState(defaultBrightness, defaultCt, null, null, reachable, on));
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

    private void assertPutState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, Integer transitionTime,
                                boolean groupState) {
        assertTrue(putStates.size() > 0, "No PUT API calls happened");
        PutState putState = putStates.remove(0);
        assertThat("Brightness differs", putState.bri, is(bri));
        assertThat("X differs", putState.x, is(x));
        assertThat("Y differs", putState.y, is(y));
        assertThat("CT differs", putState.ct, is(ct));
        assertThat("On differs", putState.on, is(on));
        assertThat("TransitionTime differs", putState.transitionTime, is(transitionTime));
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
        runAndAssertConfirmations(defaultBrightness, defaultCt, groupState);
    }

    private void runAndAssertConfirmations(int brightness, int ct, boolean groupState) {
        runAndAssertConfirmations(true, true, groupState, brightness, ct, null, null, null, defaultTransitionTime);
    }

    private void runAndAssertConfirmations(boolean reachable, boolean onState, boolean groupState, Integer putBrightness,
                                           Integer putCt, Double putX, Double putY, Boolean putOn, Integer putTransitionTime) {
        runAndAssertConfirmations(state ->
                advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, putX, putY, putOn, putTransitionTime, groupState));
    }

    private void runAndAssertConfirmations(Consumer<ScheduledRunnable> repeatedState) {
        for (int i = 0; i < ScheduledState.CONFIRM_AMOUNT; i++) {
            List<ScheduledRunnable> confirmRunnable = ensureScheduledStates(1);
            ScheduledRunnable state = confirmRunnable.get(0);
            assertScheduleStart(state, now.plusSeconds(confirmDelay));

            LOG.info("Confirm state {} [{}/{}]", id, i, ScheduledState.CONFIRM_AMOUNT);
            repeatedState.accept(state);
        }
    }

    private void addDefaultState() {
        addState(id, now, defaultBrightness, defaultCt);
    }

    private void addNullState(ZonedDateTime start) {
        addState(id, start, null, null);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, false);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, true, defaultBrightness, defaultCt, groupState);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState,
                                                    Integer putBrightness, Integer putCt, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, null, null, null, defaultTransitionTime, groupState);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState, Integer putBrightness,
                                                    Integer putCt, Double putX, Double putY, Boolean putOn, Integer putTransitionTime, boolean groupState) {
        setCurrentTimeTo(state.getStart());
        runAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, putX, putY, putOn, putTransitionTime, groupState);
    }

    private void runAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState, Integer putBrightness,
                                      Integer putCt, Double putX, Double putY, Boolean putOn, Integer putTransitionTime, boolean groupState) {
        addLightStateResponse(reachable, onState);
        runAndAssertPutCall(state, putBrightness, putCt, putX, putY, putOn, putTransitionTime, groupState);
        assertLightGetState(id);
    }

    private void runAndAssertPutCall(ScheduledRunnable state, Integer putBrightness, Integer putCt, Double putX, Double putY,
                                     Boolean putOn, Integer putTransitionTime, boolean groupState) {
        state.run();

        assertPutState(id, putBrightness, putX, putY, putCt, putOn, putTransitionTime, groupState);
    }

    private void advanceTimeAndRunAndAssertTurnOffApiCall(boolean reachable, ScheduledRunnable state, boolean onState) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, null, null, null, null, false,
                defaultTransitionTime, false);
    }

    private void advanceTimeAndRunAndAssertTurnOnApiCall(ScheduledRunnable state) {
        advanceTimeAndRunAndAssertApiCalls(state, true, true, null, null, null, null, true,
                defaultTransitionTime, false);
    }

    private void advanceTimeAndRunAndAssertPutCall(ScheduledRunnable state, Integer brightness, Integer ct, boolean groupState,
                                                   Boolean on, Integer transitionTime) {
        setCurrentTimeTo(state.getStart());
        runAndAssertPutCall(state, brightness, ct, null, null, on, transitionTime, groupState);
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

        advanceTimeAndRunAndAssertApiCalls(runnable, reachable);
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
        defaultTransitionTime = 2;
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
    }

    @Test
    void run_groupState_andLightState_sameId_treatedDifferently_endIsCalculatedIndependently() {
        addGroupState(1, now, 1);
        addState(1, now.plusHours(1));

        assertGroupGetState(1);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now);

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true, true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_becauseOfDayWrapAround() {
        addState(22, now.plus(1, ChronoUnit.HOURS));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_theNextWithCorrectDelay() {
        addState(1, now.plusHours(1));
        addState(1, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        addState(11, now.minus(1, ChronoUnit.HOURS));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
    }

    @Test
    void run_multipleStates_sameId_differentTimes_correctlyScheduled() {
        addState(22, now);
        addState(22, now.plusHours(1));
        addState(22, now.plusHours(2));

        startScheduler();

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

        startScheduler();

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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now);
        assertScheduleStart(scheduledRunnables.get(2), now);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_unknownFlag_exception() {
        assertThrows(UnknownStateProperty.class, () -> addState("1\t10:00\tUNKNOWN:1"));
    }

    @Test
    void parse_missingTime_exception() {
        assertThrows(DateTimeParseException.class, () -> {
            addState("1\tct:" + defaultCt);
            startScheduler();
        });
    }

    @Test
    void parse_setsTransitionTime() {
        String time = now.toLocalTime().toString();
        addState("1\t" + time + "\tbri:" + defaultBrightness + "\ttr:" + 5);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true, true, defaultBrightness,
                null, null, null, null, 5, false);

        runAndAssertConfirmations(true, true, false, defaultBrightness, null, null, null, null,
                5);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY() {
        String time = now.toLocalTime().toString();
        double x = 0.6075;
        double y = 0.3525;
        addState("1\t" + time + "\tx:" + x + "\ty:" + y);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true, true, null,
                null, x, y, null, defaultTransitionTime, false);

        runAndAssertConfirmations(true, true, false, null, null, x, y, null,
                defaultTransitionTime);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_correctlyTranslatedToMired() {
        String time = now.toLocalTime().toString();
        int kelvin = 6500;
        addState("1\t" + time + "\tk:" + kelvin);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true, true, null,
                153, null, null, null, defaultTransitionTime, false);

        runAndAssertConfirmations(true, true, false, null, 153, null, null, null,
                defaultTransitionTime);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_detectsOnProperty() {
        String time = now.toLocalTime().toString();
        addState("1\t" + time + "\ton:" + true);

        startScheduler();

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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true); // sunrise state
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1).with(nextDaySunrise));
    }

    @Test
    void parse_sunrise_singleState_callsStartTimeProvider_calculatesInitialAndNextEndCorrectly() {
        setCurrentAndInitialTimeTo(now.with(sunrise));
        addState("1\tsunrise\tbri:" + defaultBrightness + "\tct:" + defaultCt);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);

        setCurrentTimeTo(initialNow.plusDays(1).with(nextDaySunrise).minusMinutes(5));

        runAndAssertApiCalls(scheduledRunnables.get(0), true, true, defaultBrightness, defaultCt, null, null, null, defaultTransitionTime, false);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(2).with(nextNextDaysSunrises));

        setCurrentTimeTo(initialNow.plusDays(2).with(nextNextDaysSunrises).minusMinutes(5));

        runAndAssertApiCalls(nextDayState, true, true, defaultBrightness, defaultCt, null, null, null, defaultTransitionTime, false);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(3).with(nextNextDaysSunrises));
    }

    @Test
    void parse_nullState_treatedCorrectly_notAddedAsState() {
        addState("1\tsunrise\tct:" + defaultCt);
        addState("1\tsunrise+10");
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_useLampNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "gKitchen Lamp";
        addLightIdResponse(2);
        addState(name + "\t12:00\tct:" + defaultCt);

        assertLightIdLookup(name);

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
    void parse_invalidCtValue_tooHigh_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 501));
    }

    @Test
    void run_execution_reachable_runsConfirmations_startsAgainNextDay_repeats() {
        addDefaultState();
        startScheduler();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(nextDayState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addGroupState(10, now, 1, 2, 3);
        assertGroupGetState(10);
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true, true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(0));
        addState(1, now, defaultBrightness, defaultCt);
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, nextMorning, defaultBrightness + 100, defaultCt);
        startScheduler();
        List<ScheduledRunnable> initalStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(initalStates.get(0), true);

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable confirmRunnable = ensureScheduledStates(1).get(0);

        confirmRunnable.run(); // does not call any API, as its past its end

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addState(1, now);
        addState(1, now.minusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        ScheduledRunnable nextDayState = initialStates.get(1);
        assertScheduleStart(nextDayState, now.plusHours(23));

        advanceTimeAndRunAndAssertApiCalls(nextDayState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2).minusHours(1));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addState(1, now);
        addState(1, now.plusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        assertScheduleStart(initialStates.get(0), now);
        assertScheduleStart(initialStates.get(1), now.plusHours(1));

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), true);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));

        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_firstUnreachable_triesAgainOneSecondLater_secondTimeReachable_success() {
        addDefaultState();
        startScheduler();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), false);

        ScheduledRunnable retryState = ensureRetryState();

        advanceTimeAndRunAndAssertApiCalls(retryState, true);
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
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), false);

        ScheduledRunnable retryState = ensureRetryState();
        setCurrentTimeTo(secondStateStart);

        retryState.run();  /* this aborts without any api calls, as the current state already ended */

        ensureRunnable(initialNow.plusDays(1));

        /* run and assert second state: */

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(1), true, true, brightness2, ct2, false);

        runAndAssertConfirmations(brightness2, ct2, false);

        ensureRunnable(secondStateStart.plusDays(1));
    }

    @Test
    void run_execution_multipleStates_reachable_stopsConfirmationIfNextIntervallStarts_resetsConfirms() {
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusMinutes(10);
        addState(id, secondStateStart);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), true);
        ensureAndRunSingleConfirmation(true);

        setCurrentTimeTo(secondStateStart);
        ScheduledRunnable furtherConfirmRunnable = ensureScheduledStates(1).get(0);

        furtherConfirmRunnable.run(); // aborts and does not call any api calls

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(nextDayRunnable, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_firstReachable_butDuringConfirmationUnreachableAgain_resetsConfirms() {
        addDefaultState();
        startScheduler();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true);
        ensureAndRunSingleConfirmation(true);
        ensureAndRunSingleConfirmation(false);

        ScheduledRunnable retryRunnable = ensureRetryState();

        advanceTimeAndRunAndAssertApiCalls(retryRunnable, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_putNotSuccessful_butReachable_whichHappensWhenLightIsSoftwareBasedOff_triesAgain_withoutCallingGetStatus() {
        addDefaultState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        apiPutSuccessful = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), defaultBrightness, defaultCt, false, null, defaultTransitionTime);

        ScheduledRunnable retryState = ensureRetryState();
        apiPutSuccessful = true;
        advanceTimeAndRunAndAssertApiCalls(retryState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_putSuccessful_reachable_butOff_triesAgain() {
        addDefaultState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true, false, defaultBrightness, defaultCt, false);

        ScheduledRunnable retryState = ensureRetryState();
        advanceTimeAndRunAndAssertApiCalls(retryState, true, true, defaultBrightness, defaultCt, false);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_notExecuted_justUsedToProvideEndToPreviousState() {
        addDefaultState();
        addNullState(now.plusMinutes(5));
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceCurrentTime(Duration.ofMinutes(5));

        scheduledRunnables.get(0).run(); // no API calls, as already ended

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
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOffApiCall(true, scheduledRunnables.get(0), false);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_unreachable_treatedAsSuccess_noConfirm() {
        addOffState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOffApiCall(false, scheduledRunnables.get(0), true);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_putFailed_retries_secondTimeSuccess_noConfirms() {
        addOffState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        apiPutSuccessful = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), null, null, false, false, defaultTransitionTime);

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
        Integer transitionTime;
        boolean groupState;

        public PutState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, Integer transitionTime, boolean groupState) {
            this.id = id;
            this.bri = bri;
            this.x = x;
            this.y = y;
            this.ct = ct;
            this.on = on;
            this.transitionTime = transitionTime;
            this.groupState = groupState;
        }
    }
}
