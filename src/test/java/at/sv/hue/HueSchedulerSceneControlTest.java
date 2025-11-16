package at.sv.hue;

import at.sv.hue.api.Capability;
import at.sv.hue.api.LightCapabilities;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class HueSchedulerSceneControlTest extends AbstractHueSchedulerTest {

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
    void sceneControl_withSceneSync_considersThem() {
        enableSceneSync();

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

        assertSceneUpdate("/groups/1",
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );
    }

    @Test
    void sceneControl_withSceneSync_overlappingGroups_considersThem() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 4, 5);
        mockGroupLightsForId(2, 4, 5, 6);
        mockAssignedGroups(4, 1, 2);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(240)
                                   .ct(340),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(250)
                                   .ct(350),
                ScheduledLightState.builder()
                                   .id("/lights/6")
                                   .bri(254)
                                   .ct(200));
        addState("g1", now, "scene:TestScene");
        addState("g2", now, "scene:TestScene");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 2,
                expectedPutCall(4).bri(240).ct(340), // todo: we should consider the overrides also here in the future; but they are not officially supported yet
                expectedPutCall(5).bri(250).ct(350),
                expectedPutCall(6).bri(254).ct(200)
        );

        assertSceneUpdate("/groups/2",
                expectedPutCall(4).bri(100).ct(20), // from group 1
                expectedPutCall(5).bri(50).ct(40), // from group 1
                expectedPutCall(6).bri(254).ct(200)
        );
        assertSceneUpdate("/groups/1",
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.get(1), 1,
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        assertSceneUpdate("/groups/2",
                expectedPutCall(4).bri(100).ct(20), // from group 1
                expectedPutCall(5).bri(50).ct(40), // from group 1
                expectedPutCall(6).bri(254).ct(200)
        );
        assertSceneUpdate("/groups/1",
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_gradient_withSceneSync_sceneToScene_firstMoreGradientPoints_correctNextSyncTime() {
        enableSceneSync();
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2, 0.2),
                                                             Pair.of(0.2, 0.3),
                                                             Pair.of(0.2, 0.4)
                                                     ))
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150));
        mockSceneLightStates(2, "AnotherTestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2, 0.3),
                                                             Pair.of(0.5, 0.4)
                                                     ))
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150));
        addState("g2", now, "scene:TestScene");
        addState("g2", now.plusMinutes(10), "scene:AnotherTestScene", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        setCurrentTimeToAndRun(runnables.getFirst());

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.2),
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.2, 0.4)
                                                    ))
                                                    .build()),
                expectedPutCall(5).bri(150)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.5, 0.4)
                                                    ))
                                                    .build())
                                  .transitionTime(tr("10min")),
                expectedPutCall(5).bri(150).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        assertSceneUpdate("/groups/2",
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.2),
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.2, 0.4)
                                                    ))
                                                    .build()),
                expectedPutCall(5).bri(150)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), now.plusDays(1)), // next scene sync
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_gradient_withSceneSync_sceneToScene_secondMoreGradientPoints_correctNextSyncTime() {
        enableSceneSync();
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2, 0.2),
                                                             Pair.of(0.2, 0.4)
                                                     ))
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150));
        mockSceneLightStates(2, "AnotherTestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2, 0.2),
                                                             Pair.of(0.3, 0.2),
                                                             Pair.of(0.5, 0.4)
                                                     ))
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150));
        addState("g2", now, "scene:TestScene");
        addState("g2", now.plusMinutes(10), "scene:AnotherTestScene", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        setCurrentTimeToAndRun(runnables.getFirst());

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.2),
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.2, 0.4)
                                                    ))
                                                    .build()),
                expectedPutCall(5).bri(150)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.2),
                                                            Pair.of(0.3, 0.2),
                                                            Pair.of(0.5, 0.4)
                                                    ))
                                                    .build())
                                  .transitionTime(tr("10min")),
                expectedPutCall(5).bri(150).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        assertSceneUpdate("/groups/2",
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.2),
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.2, 0.4)
                                                    ))
                                                    .build()),
                expectedPutCall(5).bri(150)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), now.plusDays(1)), // next scene sync
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
    void sceneControl_lightsAreOff_supportsOffUpdates_controlsLightsIndividually() {
        enableSupportForOffLightUpdates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(200));
        addState("g1", now, "scene:TestScene", "tr:5s"); // transition time ignored for off lights

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        mockIsGroupOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(4).bri(100),
                expectedPutCall(5).bri(200)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_controlLightsIndividually_performsIndividualUpdates() {
        controlGroupLightsIndividually = true;
        create();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .on(false));
        addState("g1", now, "scene:TestScene");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(4).bri(100),
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

        assertThrows(InvalidConfigurationLine.class, () -> addState("g1", now, "effect:candle", "scene:TestScene"));
    }

    @Test
    void sceneControl_init_withGradient_exception() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
        mockSceneLightStates(1, "TestScene", ScheduledLightState.builder().id("/lights/4").bri(100));

        assertThrows(InvalidConfigurationLine.class, () -> addState("g1", now,
                "gradient:[rgb(10 10 10), rgb(10 10 11)]", "scene:TestScene"));
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

        // Light 4 gets scaled, Light 5 is ignored (since no bri in scene)
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

    @Test
    void sceneControl_withModificationTracking_correctlyDetectsOverride() {
        enableUserModificationTracking();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(200),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(125)
                                   .ct(225));
        mockSceneLightStates(1, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(225)
                                   .ct(325));
        addState("g1", now, "scene:TestScene1");
        addState("g1", now.plusMinutes(10), "scene:TestScene2");


        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(100).ct(200),
                expectedPutCall(5).bri(125).ct(225)
        );

        ScheduledRunnable nextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();

        // No override, yet
        setGroupStateResponses(1,
                expectedState().id("/lights/4").brightness(100).colorTemperature(200).colormode(ColorMode.CT),
                expectedState().id("/lights/5").brightness(125).colorTemperature(225).colormode(ColorMode.CT)
        );
        advanceTimeAndRunAndAssertScenePutCalls(runnables.get(1), 1,
                expectedPutCall(4).bri(200).ct(300),
                expectedPutCall(5).bri(225).ct(325)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        // User override: switched light state 4 and 5
        setGroupStateResponses(1,
                expectedState().id("/lights/4").brightness(225).colorTemperature(325).colormode(ColorMode.CT),
                expectedState().id("/lights/5").brightness(200).colorTemperature(300).colormode(ColorMode.CT)
        );
        advanceTimeAndRunAndAssertScenePutCalls(nextDay, 1); // override detected

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_withModificationTracking_groupThenScene_correctlyDetectsOverride() {
        enableUserModificationTracking();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(300),
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
                expectedPutCall(4).bri(200).ct(300),
                expectedPutCall(5).bri(100).ct(250)
        );

        ScheduledRunnable nextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();

        // No override, yet
        setGroupStateResponses(1,
                expectedState().id("/lights/4").brightness(200).colorTemperature(300).colormode(ColorMode.CT),
                expectedState().id("/lights/5").brightness(100).colorTemperature(250).colormode(ColorMode.CT)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).bri(50).ct(200)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        // User override
        setGroupStateResponses(1,
                expectedState().id("/lights/4").brightness(100).colorTemperature(200).colormode(ColorMode.CT),
                expectedState().id("/lights/5").brightness(100).colorTemperature(200).colormode(ColorMode.CT)
        );
        advanceTimeAndRunAndAssertScenePutCalls(nextDay, 1); // override detected

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_sceneThenScene_correctlyHandlesGroupMissmatch() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 5, 6);
        mockSceneLightStates(2, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(300),
                ScheduledLightState.builder()
                                   .id("/lights/6")
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
                                   .ct(500),
                ScheduledLightState.builder()
                                   .id("/lights/6")
                                   .bri(300)
                                   .ct(500));
        addState("g2", now, "scene:TestScene");
        addState("g2", now.plusMinutes(10), "scene:AnotherTestScene", "tr-before:5min");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 2,
                expectedPutCall(5).bri(100).ct(300),
                expectedPutCall(6).bri(150).ct(400)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)) // next day
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.get(1), 2,
                expectedPutCall(4).bri(200).ct(400).transitionTime(tr("5min")),
                expectedPutCall(5).bri(250).ct(500).transitionTime(tr("5min")),
                expectedPutCall(6).bri(300).ct(500).transitionTime(tr("5min"))
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(5), initialNow.plusDays(2)) // next day
        );
    }

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
                                                     .mode("interpolated_palette")
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
                                                            .mode("interpolated_palette")
                                                            .build()),
                expectedPutCall(5).bri(100).ct(250)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneControl_sceneToScene_oneWithGradient_trBefore_calculatesFullPicture_correctPutCalls() {
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
                                                             Pair.of(0.2, 0.3),
                                                             Pair.of(0.4, 0.4)
                                                     ))
                                                     .mode("random_pixelated")
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(250));
        addState("g1", now, "scene:TestScene1");
        addState("g1", now.plusMinutes(10), "scene:TestScene2", "tr-before:5min");


        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.getFirst(), 1,
                expectedPutCall(4).bri(100).gradient(Gradient.builder()
                                                             .points(List.of(
                                                                     Pair.of(0.2, 0.3),
                                                                     Pair.of(0.4, 0.4)
                                                             ))
                                                             .mode("random_pixelated")
                                                             .build()),
                expectedPutCall(5).bri(200).ct(250)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)) // next day
        );

        advanceTimeAndRunAndAssertScenePutCalls(runnables.get(1), 1,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.4, 0.4)
                                                    ))
                                                    .mode("random_pixelated")
                                                    .build()).transitionTime(tr("5min")),
                expectedPutCall(5).bri(100).ct(250).transitionTime(tr("5min"))
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(5), initialNow.plusDays(2)) // next day
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

        setCurrentTimeToAndRun(runnables.getFirst());

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

        setCurrentTimeToAndRun(runnables.getFirst());

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

        setCurrentTimeToAndRun(runnables.getFirst());

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

        setCurrentTimeToAndRun(runnables.getFirst());

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

        setCurrentTimeToAndRun(runnables.getFirst());

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

        setCurrentTimeToAndRun(runnables.getFirst());

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
    void sceneControl_interpolate_sceneToScene_lightIsOffInBothScenes_stayOffInInterpolatedCall() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .on(false),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .on(false),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(250));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        setCurrentTimeToAndRun(runnables.getFirst());

        assertScenePutCalls(2,
                expectedPutCall(4).on(false),
                expectedPutCall(5).bri(150)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).on(false).transitionTime(tr("10min")),
                expectedPutCall(5).bri(250).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneControl_interpolate_sceneToScene_lightIsOffInPreviousScenes_interpolatesCorrectly() {
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 4, 5);
        mockSceneLightStates(2, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .on(false),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(150));
        mockSceneLightStates(2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(250));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        setCurrentTimeToAndRun(runnables.getFirst());

        assertScenePutCalls(2,
                expectedPutCall(4).bri(1), // min brightness value
                expectedPutCall(5).bri(150)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).bri(100).transitionTime(tr("10min")),
                expectedPutCall(5).bri(250).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // after 5 minutes power on -> interpolates brightness

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/2",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        powerOnRunnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).bri(50),  // interpolated
                expectedPutCall(5).bri(200)  // interpolated
        );
        assertScenePutCalls(2,
                expectedPutCall(4).bri(100).transitionTime(tr("5min")),
                expectedPutCall(5).bri(250).transitionTime(tr("5min"))
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
                                                     .mode("interpolated_palette")
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
                                                     .build()), // null mode = interpolated_palette
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(250));
        addState("g2", now, "scene:TestScene1");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        setCurrentTimeToAndRun(runnables.getFirst());

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

        // after 5 minutes power on

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/2",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        powerOnRunnables.getFirst().run();

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.234, 0.567), // interpolated
                                                            Pair.of(0.2924, 0.608)  // interpolated
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

    @Test
    void sceneControl_interpolate_sceneToScene_withGradients_differentModes_takesModeFromTarget() {
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
                                                     .mode("interpolated_palette")
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
                                                     .mode("random_pixelated")
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

        setCurrentTimeToAndRun(runnables.getFirst());

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.1637, 0.455),
                                                            Pair.of(0.234, 0.567)
                                                    ))
                                                    .mode("random_pixelated")
                                                    .build()),
                expectedPutCall(5).bri(150)
        );

        assertScenePutCalls(2,
                expectedPutCall(4).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.345, 0.678),
                                                            Pair.of(0.456, 0.789)
                                                    ))
                                                    .mode("random_pixelated")
                                                    .build())
                                  .transitionTime(tr("10min")),
                expectedPutCall(5).bri(250).transitionTime(tr("10min"))
        );
        assertAllScenePutCallsAsserted();

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

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
                                   .effect(Effect.builder().effect("candle").build()), // only effect
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
                expectedPutCall(4).bri(100).effect(Effect.builder().effect("candle").build()),
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
}
