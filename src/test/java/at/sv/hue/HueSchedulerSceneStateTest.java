package at.sv.hue;

import at.sv.hue.api.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.when;

public class HueSchedulerSceneStateTest extends AbstractHueSchedulerTest {

    @Test
    void autoSceneStates_notEnabled_ignoresScene() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene1 = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                        .id("/lights/4")
                        .bri(100)
                        .ct(20),
                ScheduledLightState.builder()
                        .id("/lights/5")
                        .bri(50)
                        .ct(40));
        mockGetAllScenes(scene1);

        startScheduler(0);
    }

    @Test
    void autoSceneStates_matchingFormat_addedAsStates_canBeCombinedWithManualStates() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene1 = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                        .id("/lights/4")
                        .bri(100)
                        .ct(20),
                ScheduledLightState.builder()
                        .id("/lights/5")
                        .bri(50)
                        .ct(40));
        Identifier scene2 = mockSceneLightStates(1, "07:00",
                ScheduledLightState.builder()
                        .id("/lights/4")
                        .bri(200)
                        .ct(20),
                ScheduledLightState.builder()
                        .id("/lights/5")
                        .bri(254)
                        .ct(40));
        mockGetAllScenes(scene1, scene2);
        addState("g1", "12:00", "bri:50");

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusHours(7)),
                expectedRunnable(now.plusHours(7), now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1,
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(7));
    }

    @Test
    void autoSceneStates_onSceneDeleted_singleState_cancelsState() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                        .id("/lights/4")
                        .bri(100)
                        .ct(20),
                ScheduledLightState.builder()
                        .id("/lights/5")
                        .bri(50)
                        .ct(40));
        mockGetAllScenes(scene);

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        simulateSceneDeletion(scene.id());

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1); // was canceled
    }

    @Test
    void autoSceneStates_onSceneDeleted_singleState_deleteAfterApply_powerOn_alsoCanceled() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                        .id("/lights/4")
                        .bri(100)
                        .ct(20),
                ScheduledLightState.builder()
                        .id("/lights/5")
                        .bri(50)
                        .ct(40));
        mockGetAllScenes(scene);

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // apply state normally

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1,
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ScheduledRunnable nextDayRunnable = ensureNextDayRunnable();

        // delete scene
        simulateSceneDeletion(scene.id());

        // cancels also power-on
        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(now.plusDays(1))).getFirst();

        advanceTimeAndRunAndAssertScenePutCalls(powerOnRunnable, 1); // was canceled

        advanceTimeAndRunAndAssertScenePutCalls(nextDayRunnable, 1); // was canceled
    }

    @Test
    void autoSceneStates_onSceneDeleted_multiplesStates_adjustsOtherState() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene1 = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                        .id("/lights/4")
                        .bri(100)
                        .ct(20),
                ScheduledLightState.builder()
                        .id("/lights/5")
                        .bri(50)
                        .ct(40));
        Identifier scene2 = mockSceneLightStates(1, "12:00",
                ScheduledLightState.builder()
                        .id("/lights/4")
                        .bri(200)
                        .ct(20),
                ScheduledLightState.builder()
                        .id("/lights/5")
                        .bri(100)
                        .ct(40));
        mockGetAllScenes(scene1, scene2);

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        simulateSceneDeletion(scene1.id());

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1); // was canceled

        // reschedules scene2 state, now with start at 00:00
        ScheduledRunnable adjustedScene2Runnable = ensureRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertScenePutCalls(adjustedScene2Runnable, 1,
                expectedPutCall(4).bri(200).ct(20),
                expectedPutCall(5).bri(100).ct(40)
        );

        ensureRunnable(initialNow.plusHours(12), initialNow.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertScenePutCalls(states.get(1), 1); // was canceled
    }

    private void mockGetAllScenes(Identifier... scenes) {
        when(mockedHueApi.getAllScenes()).thenReturn(Arrays.asList(scenes));
    }

    private void simulateSceneDeletion(String sceneId) {
        scheduler.getSceneDiscoveryListener().onSceneDeleted(sceneId);
    }
}
