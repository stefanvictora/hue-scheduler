package at.sv.hue;

import at.sv.hue.api.HueApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

class HueEnforcerTest {

    private DummyScheduledExecutorService executor;
    private HueEnforcer enforcer;
    private LocalDateTime now;
    private ArrayList<GetState> getStates;
    private ArrayList<PutState> putStates;
    private ArrayList<LightState> lightStateResponses;
    private int defaultCt;
    private int defaultBrightness;
    private int id;

    private void setCurrentTimeTo(LocalDateTime secondStateStart) {
        now = secondStateStart;
    }

    private void create() {
        HueApi hueApi = new HueApi() {
            @Override
            public LightState getState(int id) {
                getStates.add(new GetState(id));
                assertFalse(lightStateResponses.isEmpty(), "TestCase mis-configured: no lightState response available");
                return lightStateResponses.remove(0);
            }

            @Override
            public boolean putState(int id, Integer bri, Double x, Double y, Integer ct) {
                putStates.add(new PutState(id, bri, x, y, ct));
                return true;
            }
        };
        enforcer = new HueEnforcer(hueApi, executor, () -> now);
    }

    private void addState(int id, LocalDateTime start) {
        addState(id, start, defaultBrightness, defaultCt);
    }

    private void addState(int id, LocalDateTime start, int brightness, int ct) {
        enforcer.addState(id, start.toLocalTime(), brightness, ct);
    }

    private void addLightStateResponse(int brightness, int ct, boolean reachable) {
        lightStateResponses.add(new LightState(null, brightness, ct, null, null, reachable));
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

    private void assertPutState(int id, Integer bri, Double x, Double y, Integer ct) {
        assertTrue(putStates.size() > 0, "No PUT API calls happened");
        PutState putState = putStates.remove(0);
        assertThat("Brightness differs", putState.bri, is(bri));
        assertThat("X differs", putState.x, is(x));
        assertThat("Y differs", putState.y, is(y));
        assertThat("CT differs", putState.ct, is(ct));
    }

    private void assertGetState(int id) {
        assertTrue(getStates.size() > 0, "No more get states to assert");
        GetState getState = getStates.remove(0);
        assertThat("ID differs", getState.id, is(id));
    }

    private void runAndAssertConfirmRunnables() {
        runAndAssertConfirmRunnables(id, defaultBrightness, defaultCt, 120);
    }

    private void runAndAssertConfirmRunnables(int id, int brightness, int ct, int confirmTimes) {
        for (int i = 0; i < confirmTimes; i++) {
            List<ScheduledRunnable> confirmRunnable = ensureScheduledRunnable(1);
            ScheduledRunnable runnable = confirmRunnable.get(0);
            assertDuration(runnable, Duration.ofSeconds(1));

            runAndAssertApiCalls(true, runnable, brightness, ct);
        }
    }

    private void addDefaultState() {
        addState(1, now, defaultBrightness, defaultCt);
    }

    private void runAndAssertApiCalls(boolean reachable, ScheduledRunnable runnable) {
        runAndAssertApiCalls(reachable, runnable, defaultBrightness, defaultCt);
    }

    private void runAndAssertApiCalls(boolean reachable, ScheduledRunnable runnable, int brightness, int ct) {
        addLightStateResponse(brightness, ct, reachable);

        runnable.run();

        assertPutState(id, brightness, null, null, ct);
        assertGetState(id);
    }

    private ScheduledRunnable ensureNextDayRunnable() {
        ScheduledRunnable runnable = ensureScheduledRunnable(1).get(0);
        assertDuration(runnable, Duration.ofHours(24));
        return runnable;
    }

    private ScheduledRunnable ensureRetryRunnable() {
        ScheduledRunnable runnable = ensureScheduledRunnable(1).get(0);
        assertDuration(runnable, Duration.ofSeconds(1));
        return runnable;
    }

    private void ensureAndRunSingleConfirmation(boolean reachable) {
        ScheduledRunnable runnable = ensureScheduledRunnable(1).get(0);
        assertDuration(runnable, Duration.ofSeconds(1));

        runAndAssertApiCalls(reachable, runnable);
    }

    @BeforeEach
    void setUp() {
        executor = new DummyScheduledExecutorService();
        setCurrentTimeTo(LocalDateTime.of(2021, 1, 1, 10, 0));
        getStates = new ArrayList<>();
        putStates = new ArrayList<>();
        lightStateResponses = new ArrayList<>();
        create();
        defaultCt = 500;
        defaultBrightness = 50;
        id = 1;
    }

    @AfterEach
    void tearDown() {
        assertEquals(0, getStates.size(), "Not all getState calls asserted");
        assertEquals(0, putStates.size(), "Not all putState calls asserted");
        assertEquals(0, lightStateResponses.size(), "Not all lightStateResponses returned");
        ensureScheduledRunnable(0);
    }

    @Test
    void run_singleState_inOneHour_singleRunnableScheduled_correctDelay() {
        addState(22, now.plus(1, ChronoUnit.HOURS));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(1);
        assertDuration(scheduledRunnable.get(0), Duration.ofHours(1));
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
        addState(22, now.plusHours(1));
        addState(22, now.plusHours(2));

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(2);
        assertDuration(scheduledRunnable.get(0), Duration.ofHours(1));
        assertDuration(scheduledRunnable.get(1), Duration.ofHours(2));
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        addState(13, now.plusHours(1));
        addState(13, now.minusHours(1));
        LocalDateTime secondStart = now.minusHours(2);
        addState(13, secondStart);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(3);
        assertDuration(scheduledRunnable.get(0), Duration.ZERO);
        assertDuration(scheduledRunnable.get(1), Duration.ofHours(1));
        assertDuration(scheduledRunnable.get(2), Duration.ofHours(22));
    }

    @Test
    void parse_parsesInputLine_brightnessAndColorTemperature_createsMultipleStates() {
        String time = now.plusHours(1).toLocalTime().toString();
        enforcer.addState("1,2,3" + "\t" + time + "\tbri:" + defaultBrightness + "\tct:" + defaultCt);

        startEnforcer();

        List<ScheduledRunnable> scheduledRunnable = ensureScheduledRunnable(3);
        assertDuration(scheduledRunnable.get(0), Duration.ofHours(1));
        assertDuration(scheduledRunnable.get(1), Duration.ofHours(1));
        assertDuration(scheduledRunnable.get(2), Duration.ofHours(1));

        runAndAssertApiCalls(true, scheduledRunnable.get(0));

        runAndAssertConfirmRunnables();

        ensureNextDayRunnable();
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
    void run_execution_twoStates_overNight_detectsEndCorrectly() {
        setCurrentTimeTo(now.withHour(23).withMinute(0));
        addState(1, now, defaultBrightness, defaultCt);
        LocalDateTime nextMorning = now.plusHours(8);
        addState(1, nextMorning, defaultBrightness + 100, defaultCt);
        startEnforcer();
        List<ScheduledRunnable> initialRunnables = ensureScheduledRunnable(2);

        runAndAssertApiCalls(true, initialRunnables.get(0));

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable confirmRunnable = ensureScheduledRunnable(1).get(0);

        confirmRunnable.run(); // does not call any API, as its past its end

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_updatesEndCorrectly() {
        addState(1, now.minusHours(1));
        addState(1, now);
        startEnforcer();
        List<ScheduledRunnable> initialRunnables = ensureScheduledRunnable(2);

        setCurrentTimeTo(now.minusHours(1).plusDays(1));

        runAndAssertApiCalls(true, initialRunnables.get(1));

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
    void run_execution_multipleStates_unreachable_stopsRetryingIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        LocalDateTime secondStateStart = now.plusSeconds(10);
        addState(id, secondStateStart, brightness2, ct2);
        startEnforcer();
        List<ScheduledRunnable> initialRunnable = ensureScheduledRunnable(2);

        runAndAssertApiCalls(false, initialRunnable.get(0));

        ScheduledRunnable retryRunnable = ensureRetryRunnable();
        setCurrentTimeTo(secondStateStart);

        retryRunnable.run();  /* this aborts without any api calls, as the current state already ended */

        ensureNextDayRunnable();

        /* run and assert second state: */

        runAndAssertApiCalls(true, initialRunnable.get(1), brightness2, ct2);

        runAndAssertConfirmRunnables(id, brightness2, ct2, 120);

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_multipleStates_reachable_stopsConfirmationIfNextIntervallStarts_resetsConfirms() {
        addDefaultState();
        LocalDateTime secondStateStart = now.plusSeconds(10);
        addState(id, secondStateStart);
        startEnforcer();
        List<ScheduledRunnable> initialRunnable = ensureScheduledRunnable(2);

        runAndAssertApiCalls(true, initialRunnable.get(0));

        ensureAndRunSingleConfirmation(true);

        setCurrentTimeTo(secondStateStart);

        ScheduledRunnable furtherConfirmRunnable = ensureScheduledRunnable(1).get(0);

        furtherConfirmRunnable.run(); // aborts and does not call any api calls

        ScheduledRunnable nextDayRunnable = ensureNextDayRunnable();
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

        public PutState(int id, Integer bri, Double x, Double y, Integer ct) {
            this.id = id;
            this.bri = bri;
            this.x = x;
            this.y = y;
            this.ct = ct;
        }
    }
}
