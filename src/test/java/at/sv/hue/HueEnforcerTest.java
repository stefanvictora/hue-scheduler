package at.sv.hue;

import at.sv.hue.api.HueApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

class HueEnforcerTest {

    private DummyScheduledExecutorService executor;
    private HueEnforcer enforcer;
    private ZonedDateTime now;
    private List<GetState> lightGetStates;
    private List<GetState> groupGetStates;
    private List<PutState> putStates;
    private List<LightState> lightStateResponses;
    private List<List<Integer>> groupLightsResponses;
    private int defaultCt;
    private int defaultBrightness;
    private int id;
    private LocalTime sunrise;
    private boolean apiPutSuccessful;
    private int retryDelay;
    private int confirmDelay;

    private void setCurrentTimeTo(ZonedDateTime secondStateStart) {
        now = secondStateStart;
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
            public boolean putState(int id, Integer bri, Double x, Double y, Integer ct, boolean groupState) {
                putStates.add(new PutState(id, bri, x, y, ct, groupState));
                return apiPutSuccessful;
            }

            @Override
            public List<Integer> getGroupLights(int groupId) {
                groupGetStates.add(new GetState(groupId));
                assertFalse(groupLightsResponses.isEmpty(), "TestCase mis-configured: no groupLights response available");
                return groupLightsResponses.remove(0);
            }
        };
        retryDelay = 1;
        confirmDelay = 2;
        enforcer = new HueEnforcer(hueApi, executor, input -> {
            if (input.equals("sunrise")) {
                return sunrise;
            } else {
                return LocalTime.parse(input);
            }
        }, () -> now, retryDelay, confirmDelay);
    }

    private void addState(int id, ZonedDateTime start) {
        addState(id, start, defaultBrightness, defaultCt);
    }

    private void addState(int id, ZonedDateTime start, int brightness, int ct) {
        enforcer.addState(id, start.toLocalTime().toString(), brightness, ct);
    }

    private void addGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        groupLightsResponses.add(Arrays.asList(lights));
        enforcer.addGroupState(groupId, start.toLocalTime().toString(), defaultBrightness, defaultCt);
    }

    private void addLightStateResponse(int brightness, int ct, boolean reachable, boolean on) {
        lightStateResponses.add(new LightState(brightness, ct, null, null, reachable, on));
    }

    private void startEnforcer() {
        enforcer.start();
    }

    private List<ScheduledRunnable> ensureScheduledRunnable(int expectedSize) {
        List<ScheduledRunnable> scheduledRunnable = executor.getScheduledRunnable();
        assertThat("Runnable size differs", scheduledRunnable.size(), is(expectedSize));
        executor.clear();
        return scheduledRunnable;
    }

    private void assertDuration(ScheduledRunnable scheduledRunnable, Duration duration) {
        assertEquals(duration, scheduledRunnable.getSecondsUntil(), "Duration differs");
    }

    private void assertPutState(int id, Integer bri, Double x, Double y, Integer ct, boolean groupState) {
        assertTrue(putStates.size() > 0, "No PUT API calls happened");
        PutState putState = putStates.remove(0);
        assertThat("Brightness differs", putState.bri, is(bri));
        assertThat("X differs", putState.x, is(x));
        assertThat("Y differs", putState.y, is(y));
        assertThat("CT differs", putState.ct, is(ct));
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

    private void runAndAssertConfirmRunnables() {
        runAndAssertConfirmRunnables(false);
    }

    private void runAndAssertConfirmRunnables(boolean groupState) {
        runAndAssertConfirmRunnables(id, defaultBrightness, defaultCt, EnforcedState.CONFIRM_AMOUNT, groupState);
    }

    private void runAndAssertConfirmRunnables(int id, int brightness, int ct, int confirmTimes, boolean groupState) {
        for (int i = 0; i < confirmTimes; i++) {
            List<ScheduledRunnable> confirmRunnable = ensureScheduledRunnable(1);
            ScheduledRunnable runnable = confirmRunnable.get(0);
            assertDuration(runnable, Duration.ofSeconds(2));

            runAndAssertApiCalls(true, runnable, brightness, ct, groupState, true);
        }
    }

    private void addDefaultState() {
        addState(1, now, defaultBrightness, defaultCt);
    }

    private void runAndAssertApiCalls(boolean reachable, ScheduledRunnable runnable) {
        runAndAssertApiCalls(reachable, runnable, false);
    }

    private void runAndAssertApiCalls(boolean reachable, ScheduledRunnable runnable, boolean groupState) {
        runAndAssertApiCalls(reachable, runnable, defaultBrightness, defaultCt, groupState, true);
    }

    private void runAndAssertApiCalls(boolean reachable, ScheduledRunnable runnable, int brightness, int ct, boolean groupState, boolean on) {
        addLightStateResponse(brightness, ct, reachable, on);
        runAndAssertPutCall(reachable, runnable, brightness, ct, groupState);
        assertLightGetState(id);
    }

    private void runAndAssertPutCall(boolean reachable, ScheduledRunnable runnable, int brightness, int ct, boolean groupState) {
        runnable.run();

        assertPutState(id, brightness, null, null, ct, groupState);
    }

    private ScheduledRunnable ensureNextDayRunnable() {
        return ensureRunnable(Duration.ofHours(24));
    }

    private ScheduledRunnable ensureRunnable(Duration duration) {
        ScheduledRunnable runnable = ensureScheduledRunnable(1).get(0);
        assertDuration(runnable, duration);
        return runnable;
    }

    private ScheduledRunnable ensureRetryRunnable() {
        return ensureRunnable(Duration.ofSeconds(retryDelay));
    }

    private void ensureAndRunSingleConfirmation(boolean reachable) {
        ScheduledRunnable runnable = ensureRunnable(Duration.ofSeconds(confirmDelay));

        runAndAssertApiCalls(reachable, runnable);
    }

    @BeforeEach
    void setUp() {
        apiPutSuccessful = true;
        sunrise = LocalTime.of(6, 0);
        executor = new DummyScheduledExecutorService();
        setCurrentTimeTo(ZonedDateTime.of(2021, 1, 1, 10, 0, 0, 0, ZoneId.systemDefault()));
        lightGetStates = new ArrayList<>();
        putStates = new ArrayList<>();
        lightStateResponses = new ArrayList<>();
        groupGetStates = new ArrayList<>();
        groupLightsResponses = new ArrayList<>();
        create();
        defaultCt = 500;
        defaultBrightness = 50;
        id = 1;
    }

    @AfterEach
    void tearDown() {
        assertEquals(0, lightGetStates.size(), "Not all lightGetState calls asserted");
        assertEquals(0, putStates.size(), "Not all putState calls asserted");
        assertEquals(0, lightStateResponses.size(), "Not all lightStateResponses returned");
        assertEquals(0, groupGetStates.size(), "Not all groupGetState calls asserted");
        assertEquals(0, groupLightsResponses.size(), "Not all groupLightResponses returned");
        ensureScheduledRunnable(0);
    }

    @Test
    void run_groupState_looksUpContainingLights_addsState() {
        addGroupState(9, now, 1, 2, 3);

        assertGroupGetState(9);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);
        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_becauseOfDayWrapAround() {
        addState(22, now.plus(1, ChronoUnit.HOURS));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);
        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_theNextWithCorrectDelay() {
        addState(1, now.plusHours(1));
        addState(1, now.plusHours(2));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(2);
        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
        assertDuration(scheduledRunnable.get(1), Duration.ofHours(1));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        addState(11, now.minus(1, ChronoUnit.HOURS));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
    }

    @Test
    void run_multipleStates_sameId_differentTimes_multipleScheduled_addedLastFirst() {
        addState(22, now);
        addState(22, now.plusHours(1));
        addState(22, now.plusHours(2));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(3);
        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
        assertDuration(scheduledRunnable.get(1), Duration.ofHours(1));
        assertDuration(scheduledRunnable.get(2), Duration.ofHours(2));
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        addState(13, now.plusHours(1));
        addState(13, now.minusHours(1));
        ZonedDateTime secondStart = now.minusHours(2);
        addState(13, secondStart);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(3);
        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
        assertDuration(scheduledRunnable.get(1), Duration.ofHours(1));
        assertDuration(scheduledRunnable.get(2), Duration.ofHours(22));
    }

    @Test
    void parse_parsesInputLine_brightnessAndColorTemperature_createsMultipleStates_canHandleGroups() {
        String time = now.plusHours(1).toLocalTime().toString();
        groupLightsResponses.add(Collections.singletonList(77));
        enforcer.addState("1,2,g9\t" + time + "\tbri:" + defaultBrightness + "\tct:" + defaultCt);
        assertGroupGetState(9);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(3);
        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
        assertDuration(scheduledRunnable.get(1), Duration.ZERO);
        assertDuration(scheduledRunnable.get(2), Duration.ZERO);

        setCurrentTimeTo(now.plusHours(1));

        runAndAssertApiCalls(true, scheduledRunnable.get(0));

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
    }

    @Test
    void parse_sunrise_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        addState(1, now);
        enforcer.addState("1\tsunrise\tbri:" + defaultBrightness + "\tct:" + defaultCt);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(2);

        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
        assertDuration(scheduledRunnable.get(1), Duration.ofHours(20));

        setCurrentTimeTo(now.plusDays(1).minusHours(4));
        sunrise = sunrise.plusHours(1);

        runAndAssertApiCalls(true, scheduledRunnable.get(1));
        runAndAssertConfirmRunnables();

        ensureRunnable(Duration.ofHours(25));
    }

    @Test
    void run_execution_reachable_runsConfirmations_startsAgainNextDay_repeats() {
        addDefaultState();
        startEnforcer();
        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        runAndAssertApiCalls(true, scheduledRunnable.get(0));

        runAndAssertConfirmRunnables();

        ScheduledRunnable nextDayRunnable = ensureNextDayRunnable();
        setCurrentTimeTo(now.plusDays(1));

        runAndAssertApiCalls(true, nextDayRunnable);

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addGroupState(10, now, 1, 2, 3);
        assertGroupGetState(10);
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        runAndAssertApiCalls(true, scheduledRunnable.get(0), true);

        runAndAssertConfirmRunnables(true);

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentTimeTo(now.withHour(23).withMinute(0));
        addState(1, now, defaultBrightness, defaultCt);
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, nextMorning, defaultBrightness + 100, defaultCt);
        startEnforcer();
        List<ScheduledRunnable> initialRunnables = ensureScheduledRunnable(2);

        runAndAssertApiCalls(true, initialRunnables.get(0));

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable confirmRunnable = ensureScheduledRunnable(1).get(0);

        confirmRunnable.run(); // does not call any API, as its past its end

        ensureRunnable(Duration.ofHours(24).minusHours(8));
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addState(1, now);
        addState(1, now.minusHours(1));
        startEnforcer();
        List<ScheduledRunnable> initialRunnables = ensureScheduledRunnable(2);
        ScheduledRunnable nextDayRunnable = initialRunnables.get(1);
        assertDuration(nextDayRunnable, Duration.ofHours(23));

        setCurrentTimeTo(now.minusHours(1).plusDays(1));

        runAndAssertApiCalls(true, nextDayRunnable);

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_firstUnreachable_triesAgainOneSecondLater_secondTimeReachable_success() {
        addDefaultState();
        startEnforcer();
        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        runAndAssertApiCalls(false, scheduledRunnable.get(0));

        ScheduledRunnable retryRunnable = ensureRetryRunnable();

        runAndAssertApiCalls(true, retryRunnable);

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_nextDayRunnableUsesStartTimeAsReference_notCurrentTime() {
        addDefaultState();
        startEnforcer();
        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        runAndAssertApiCalls(true, scheduledRunnable.get(0));

        setCurrentTimeTo(now.plusHours(4));

        runAndAssertConfirmRunnables();

        ensureRunnable(Duration.ofHours(20));
    }

    @Test
    void run_execution_multipleStates_unreachable_stopsRetryingIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(id, secondStateStart, brightness2, ct2);
        startEnforcer();
        List<ScheduledRunnable> initialRunnable = ensureScheduledRunnable(2);

        runAndAssertApiCalls(false, initialRunnable.get(0));

        ScheduledRunnable retryRunnable = ensureRetryRunnable();
        setCurrentTimeTo(secondStateStart);

        retryRunnable.run();  /* this aborts without any api calls, as the current state already ended */

        ensureRunnable(Duration.ofHours(24).minusSeconds(10));

        /* run and assert second state: */

        runAndAssertApiCalls(true, initialRunnable.get(1), brightness2, ct2, false, true);

        runAndAssertConfirmRunnables(id, brightness2, ct2, EnforcedState.CONFIRM_AMOUNT, false);

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_multipleStates_reachable_stopsConfirmationIfNextIntervallStarts_resetsConfirms() {
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(id, secondStateStart);
        startEnforcer();
        List<ScheduledRunnable> initialRunnable = ensureScheduledRunnable(2);

        runAndAssertApiCalls(true, initialRunnable.get(0));

        ensureAndRunSingleConfirmation(true);

        setCurrentTimeTo(secondStateStart);

        ScheduledRunnable furtherConfirmRunnable = ensureScheduledRunnable(1).get(0);

        furtherConfirmRunnable.run(); // aborts and does not call any api calls

        ScheduledRunnable nextDayRunnable = ensureRunnable(Duration.ofHours(24).minusSeconds(10));
        setCurrentTimeTo(now.plusDays(1).minusSeconds(10));

        runAndAssertApiCalls(true, nextDayRunnable);

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_firstReachable_butDuringConfirmationUnreachableAgain_resetsConfirms() {
        addDefaultState();
        startEnforcer();
        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        runAndAssertApiCalls(true, scheduledRunnable.get(0));

        ensureAndRunSingleConfirmation(true);
        ensureAndRunSingleConfirmation(false);

        ScheduledRunnable retryRunnable = ensureRetryRunnable();

        runAndAssertApiCalls(true, retryRunnable);

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_putNotSuccessful_butReachable_whichHappensWhenLightIsSoftwareBasedOff_triesAgain_withoutCallingGetStatus() {
        addDefaultState();
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        apiPutSuccessful = false;
        runAndAssertPutCall(true, scheduledRunnable.get(0), defaultBrightness, defaultCt, false);

        ScheduledRunnable retryRunnable = ensureRetryRunnable();
        apiPutSuccessful = true;
        runAndAssertApiCalls(true, retryRunnable);

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_putSuccessful_reachable_butOff_triesAgain() {
        addDefaultState();
        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);

        runAndAssertApiCalls(true, scheduledRunnable.get(0), defaultBrightness, defaultCt, false, false);

        ScheduledRunnable retryRunnable = ensureRetryRunnable();
        runAndAssertApiCalls(true, retryRunnable, defaultBrightness, defaultCt, false, true);

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
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
        boolean groupState;

        public PutState(int id, Integer bri, Double x, Double y, Integer ct, boolean groupState) {
            this.id = id;
            this.bri = bri;
            this.x = x;
            this.y = y;
            this.ct = ct;
            this.groupState = groupState;
        }
    }
}
