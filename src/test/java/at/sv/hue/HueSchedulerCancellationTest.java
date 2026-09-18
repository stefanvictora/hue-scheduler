package at.sv.hue;

import at.sv.hue.api.ApiFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class HueSchedulerCancellationTest extends AbstractHueSchedulerTest {

    @Test
    void invalidationWaitsForAnAdmittedBridgeWrite() throws Exception {
        addDefaultState();
        ScheduledRunnable task = startAndGetSingleRunnable();
        ScheduledState state = states("/lights/1").getFirst();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(_ -> blockedRead(entered, resume, false, null)).when(mockedHueApi).putState(any());
        Thread invalidating = new Thread(state::invalidate);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(task::run);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                invalidating.start();
                await().atMost(5, TimeUnit.SECONDS).until(() ->
                        invalidating.getState() == Thread.State.BLOCKED || !invalidating.isAlive());
                assertThat(invalidating.isAlive())
                        .as("Invalidation must wait until the admitted bridge write finishes")
                        .isTrue();
            } finally {
                resume.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
            invalidating.join(5000);
            assertThat(invalidating.isAlive()).isFalse();
        }

        assertPutCalls(defaultPutCall());
        scheduler.getHueEventListener().onLightOn("/lights/1");
        setCurrentTimeTo(now.plusDays(1));
        runDueTasks(); // follow-ups admitted before invalidation must be inert
    }

    @Test
    void temporaryCopiesKeepTheSourceSnapshotGeneration() throws Exception {
        addDefaultState();
        ScheduledState state = states("/lights/1").getFirst();
        ScheduledStateSnapshot snapshot = state.getSnapshot(now);
        ScheduledState copy = snapshot.createTemporaryCopy();

        state.invalidate();

        assertThat(copy.getSnapshot(now).isCancelled()).isTrue();
        assertThat(snapshot.createTemporaryCopy().getSnapshot(now).isCancelled()).isTrue();
        assertThat(ScheduledState.createTemporaryCopy(copy).getSnapshot(now).isCancelled()).isTrue();
        assertThat(state.getSnapshot(now).isCancelled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidationDuringForegroundReadPreventsWritesCopiesAndRetries(boolean readFails) throws Exception {
        addDefaultState();
        ScheduledRunnable task = startAndGetSingleRunnable();
        ScheduledState state = states("/lights/1").getFirst();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(_ -> blockedRead(entered, resume, readFails, false))
                .when(mockedHueApi).isLightOff("/lights/1");

        invalidateDuring(task, state, entered, resume);

        ensureScheduledStates(0);
        scheduler.getHueEventListener().onLightOn("/lights/1");
        ensureScheduledStates(0);
        assertThat(state.getLastSeen()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidationDuringSceneSyncReadPreventsWritesAndRetries(boolean readFails) throws Exception {
        sceneSyncDelayInSeconds = 5;
        enableSceneSync();
        addDefaultGroupState(1, now, 4);
        ScheduledRunnable task = startAndGetSingleRunnable();
        runAndAssertGroupPutCalls(task, defaultGroupPutCall());
        ScheduledRunnable sync = ensureScheduledStates(2).getFirst();
        setCurrentTimeTo(sync);
        ScheduledState state = states("/groups/1").getFirst();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(_ -> blockedRead(entered, resume, readFails, List.of("/lights/4")))
                .when(mockedHueApi).getGroupLights("/groups/1");

        invalidateDuring(sync, state, entered, resume);

        ensureScheduledStates(0);
        scheduler.getHueEventListener().onLightOn("/groups/1");
        ensureScheduledStates(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidationDuringBackgroundReadPreventsWritesAndRescheduling(boolean readFails) throws Exception {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");
        List<ScheduledRunnable> tasks = startScheduler(3);
        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(tasks.get(1), expectedPutCall(1).bri(50));
        ScheduledRunnable background = ensureScheduledStates(2).getFirst();
        setCurrentTimeTo(background);
        ScheduledState state = states("/lights/1").get(1);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(_ -> blockedRead(entered, resume, readFails, true))
                .when(mockedHueApi).isLightOff("/lights/1");

        invalidateDuring(background, state, entered, resume);

        ensureScheduledStates(0);
    }

    private List<ScheduledState> states(String id) throws Exception {
        var field = HueScheduler.class.getDeclaredField("stateRegistry");
        field.setAccessible(true);
        return ((ScheduledStateRegistry) field.get(scheduler)).findStatesForId(id);
    }

    private static <T> T blockedRead(CountDownLatch entered, CountDownLatch resume, boolean fails, T result)
            throws InterruptedException {
        entered.countDown();
        assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
        if (fails) throw new ApiFailure("Read failed after invalidation");
        return result;
    }

    private static void invalidateDuring(ScheduledRunnable task, ScheduledState state,
                                         CountDownLatch entered, CountDownLatch resume) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(task::run);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                state.invalidate();
            } finally {
                resume.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        }
    }
}
