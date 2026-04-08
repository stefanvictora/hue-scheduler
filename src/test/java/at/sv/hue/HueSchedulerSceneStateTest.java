package at.sv.hue;

import at.sv.hue.api.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.when;

public class HueSchedulerSceneStateTest extends AbstractHueSchedulerTest {

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

    private void mockGetAllScenes(Identifier... scenes) {
        when(mockedHueApi.getAllScenes()).thenReturn(Arrays.asList(scenes));
    }
}
