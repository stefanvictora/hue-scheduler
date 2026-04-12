package at.sv.hue;

import at.sv.hue.api.Identifier;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.when;

public class HueSchedulerSceneStateTest extends AbstractHueSchedulerTest {

    @Test
    void autoSceneStates_notEnabled_ignoresSceneOnStartup() {
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
    void autoSceneStates_notEnabled_ignoresSceneCreationEvents() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);

        startScheduler(0);

        // New scene created that matches pattern
        Identifier scene = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        simulateSceneCreatedOrUpdated(scene.id());

        // ignored
        ensureScheduledStates(0);
    }

    @Test
    void autoSceneStates_apiFailureDuringInitialDiscovery_ignoresAffectedScene_continuesWithNextScene() {
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
        when(mockedHueApi.getSceneLightStates(scene1.id())).thenThrow(new RuntimeException("API failure"));

        startScheduler(
                expectedRunnable(now, now.plusHours(7))
        );
    }

    @Test
    void autoSceneStates_apiFailureDuringAddition_ignoresScene() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);

        startScheduler(0);

        // New scene created that matches pattern
        Identifier scene = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        when(mockedHueApi.getSceneLightStates(scene.id())).thenThrow(new RuntimeException("API failure"));
        simulateSceneCreatedOrUpdated(scene.id());

        // ignored
        ensureScheduledStates(0);
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

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene1.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(7));
    }

    @Test
    void autoSceneStates_matchingFormat_withOnOrOffFlags_correctlyInterpreted() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene1 = mockSceneLightStates(1, "00:00 [off,tr:2s]",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        Identifier scene2 = mockSceneLightStates(1, "07:00 [on]",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(254)
                                   .ct(40));
        mockGetAllScenes(scene1, scene2);

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusHours(7)),
                expectedRunnable(now.plusHours(7), now.plusDays(1))
        );

        // Sets "on:false" and removes other properties

        advanceTimeAndRunAndAssertGroupPutCalls(states.getFirst(),
                expectedGroupPutCall(1).on(false).transitionTime(tr("2s"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(7)); // next day

        // Sets "on:true"

        advanceTimeAndRunAndAssertScenePutCalls(states.get(1), 1, scene2.id(),
                expectedPutCall(4).bri(200).ct(20).on(true),
                expectedPutCall(5).bri(254).ct(40).on(true)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(7), initialNow.plusDays(2)); // next day

        // Keeps on also on scene reload

        simulateSceneModified(1, "07:00 [on]");

        ScheduledRunnable rescheduledSecondState = ensureRunnable(now, initialNow.plusDays(1));

        advanceTimeAndRunAndAssertScenePutCalls(rescheduledSecondState, 1, scene2.id(),
                expectedPutCall(4).bri(200).ct(20).on(true),
                expectedPutCall(5).bri(254).ct(40).on(true)
        );
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

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene.id()); // was canceled
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

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ScheduledRunnable nextDayRunnable = ensureNextDayRunnable();

        // delete scene
        simulateSceneDeletion(scene.id());

        // cancels also power-on
        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(now.plusDays(1))).getFirst();

        advanceTimeAndRunAndAssertScenePutCalls(powerOnRunnable, 1, scene.id()); // was canceled

        advanceTimeAndRunAndAssertScenePutCalls(nextDayRunnable, 1, scene.id()); // was canceled
    }

    @Test
    void autoSceneStates_onSceneDeleted_firstState_multiplesStates_adjustsOtherState() {
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

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene1.id()); // was canceled

        // reschedules scene2 state, now with start at 00:00
        ScheduledRunnable adjustedScene2Runnable = ensureRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertScenePutCalls(adjustedScene2Runnable, 1, scene2.id(),
                expectedPutCall(4).bri(200).ct(20),
                expectedPutCall(5).bri(100).ct(40)
        );

        ensureRunnable(initialNow.plusHours(12), initialNow.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertScenePutCalls(states.get(1), 1, scene2.id()); // was canceled
    }

    @Test
    void autoSceneStates_onSceneDeleted_secondState_multiplesStates_adjustsOtherState() {
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

        startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        // Delete scene2
        simulateSceneDeletion(scene2.id());

        // reschedules scene1 state, now with adjusted end at 00:00
        ScheduledRunnable adjustedScene1Runnable = ensureRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertScenePutCalls(adjustedScene1Runnable, 1, scene1.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void autoSceneStates_onSceneCreated_noOtherState_automaticallyCreatesState() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);

        startScheduler(0);

        // New scene created that matches pattern
        Identifier scene = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        simulateSceneCreatedOrUpdated(scene.id());

        List<ScheduledRunnable> states = ensureScheduledStates(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void autoSceneStates_onExistingSceneStateRenamed_automaticallyRecreatesState() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene = mockSceneLightStates(1, 1, "00:00",
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

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // Existing scene renamed
        Identifier updatedScene = mockSceneLightStates(1, 1, "12:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        simulateSceneCreatedOrUpdated(updatedScene.id());

        // Reschedules updated state with new start/end time
        List<ScheduledRunnable> rescheduledStates = ensureScheduledStates(
                expectedRunnable(now, now.plusHours(12))
        );

        advanceTimeAndRunAndAssertScenePutCalls(rescheduledStates.getFirst(), 1, scene.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureRunnable(initialNow.plusHours(12), initialNow.plusDays(1).plusHours(12));

        // Old next day state was canceled
        advanceTimeAndRunAndAssertScenePutCalls(nextDayRunnable, 1, scene.id()); // was canceled
    }

    @Test
    void autoSceneStates_onUnrelatedSceneRenamed_ignored() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene = mockSceneLightStates(1, 1, "00:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        Identifier normalScene = mockSceneLightStates(1, 2, "Just a normal scene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(52)
                                   .ct(42));
        mockGetAllScenes(scene, normalScene);

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // Unrelated scene renamed
        Identifier updatedScene = mockSceneLightStates(1, 2, "Still a normal scene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        simulateSceneCreatedOrUpdated(updatedScene.id());

        // Nothing rescheduled
        ensureScheduledStates(0);

        // Next day state was not canceled
        advanceTimeAndRunAndAssertScenePutCalls(nextDayRunnable, 1, scene.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(3)); // next day
    }

    @Test
    void autoSceneStates_onExistingSceneStateRenamed_newNameNotValidAnymore_removed() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene1 = mockSceneLightStates(1, 1, "00:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(110),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(51));
        Identifier scene2 = mockSceneLightStates(1, 2, "07:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(120),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(52));
        Identifier scene3 = mockSceneLightStates(1, 3, "12:00[i]",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(130),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(53));
        mockGetAllScenes(scene1, scene2, scene3);

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusHours(7)),
                expectedRunnable(now.plusHours(7), now.plusHours(7)), // zero length
                expectedRunnable(now.plusHours(7), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene1.id(),
                expectedPutCall(4).bri(110),
                expectedPutCall(5).bri(51)
        );

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(7)); // next day

        Identifier updatedScene2 = mockSceneLightStates(1, 2, "INVALID_NAME",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(120),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(52));
        simulateSceneCreatedOrUpdated(updatedScene2.id());

        ensureScheduledStates(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertScenePutCalls(states.get(1), 1, scene2.id()); // was canceled
        advanceTimeAndRunAndAssertScenePutCalls(states.get(2), 1, scene3.id()); // was canceled
        advanceTimeAndRunAndAssertScenePutCalls(nextDayRunnable, 1, scene1.id()); // was canceled
    }

    @Test
    void autoSceneStates_interpolate_withBackgroundInterpolation_cancelsOnDeletion() {
        enableAutoSceneStates();
        enableSupportForOffLightUpdates(); // enables background interpolation
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
        Identifier scene2 = mockSceneLightStates(1, "12:00[i]",
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
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        // Run first state -> creates background interpolation and follow-up split call

        setCurrentTimeToAndRun(states.getFirst());

        assertScenePutCalls(1, scene2.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        assertScenePutCalls(1, scene2.id(),
                expectedPutCall(4).bri(114).ct(20).transitionTime(tr("1h40min")),
                expectedPutCall(5).bri(57).ct(40).transitionTime(tr("1h40min"))
        );

        assertAllScenePutCallsAsserted();

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(54), initialNow.plusDays(1)), // next background interpolation
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split call
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );

        // Run next background interpolation -> no-op since group is on
        setCurrentTimeToAndRun(followUpStates.getFirst());

        // Run next split call

        advanceTimeAndRunAndAssertScenePutCalls(followUpStates.get(1), 1, scene2.id(),
                expectedPutCall(4).bri(128).ct(20).transitionTime(tr("1h40min")),
                expectedPutCall(5).bri(64).ct(40).transitionTime(tr("1h40min"))
        );

        List<ScheduledRunnable> followUpStates2 = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(112), initialNow.plusDays(1)), // next background interpolation
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS), initialNow.plusDays(1)) // next split call
        );

        // Delete scene2
        simulateSceneDeletion(scene2.id());

        mockIsGroupOff(1, true);

        // Reschedules scene1 state
        ensureRunnable(now, initialNow.plusDays(1));

        advanceTimeAndRunAndAssertScenePutCalls(followUpStates2.getFirst(), 1, scene2.id()); // was canceled
        advanceTimeAndRunAndAssertScenePutCalls(followUpStates2.get(1), 1, scene2.id()); // was canceled
        advanceTimeAndRunAndAssertScenePutCalls(followUpStates.get(2), 1, scene2.id()); // was canceled
    }

    @Test
    void autoSceneStates_interpolate_sceneSync_stopsBackgroundSceneSync_reSyncsOnReschedule() {
        enableAutoSceneStates();
        enableSceneSync();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene1 = mockSceneLightStates(1, 1, "00:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50)
                                   .ct(40));
        Identifier scene2 = mockSceneLightStates(1, 2, "12:00[i]",
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
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        setCurrentTimeToAndRun(states.getFirst());

        assertScenePutCalls(1, scene2.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        assertScenePutCalls(1, scene2.id(),
                expectedPutCall(4).bri(114).ct(20).transitionTime(tr("1h40min")),
                expectedPutCall(5).bri(57).ct(40).transitionTime(tr("1h40min"))
        );

        assertAllScenePutCallsAsserted();

        assertSceneUpdate("/groups/1",
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(54), initialNow.plusDays(1)), // next scene sync
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split call
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );

        Identifier updatedScene2 = mockSceneLightStates(1, 2, "12:00[i,tr:5s]",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200)
                                   .ct(20),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100)
                                   .ct(40));
        simulateSceneCreatedOrUpdated(updatedScene2.id());

        List<ScheduledRunnable> reschedulesStates = ensureScheduledStates(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        setCurrentTimeToAndRun(reschedulesStates.getFirst());

        assertScenePutCalls(1, scene2.id(),
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        assertScenePutCalls(1, scene2.id(),
                expectedPutCall(4).bri(114).ct(20).transitionTime(tr("1h40min")),
                expectedPutCall(5).bri(57).ct(40).transitionTime(tr("1h40min"))
        );

        assertAllScenePutCallsAsserted();

        assertSceneUpdate("/groups/1",
                expectedPutCall(4).bri(100).ct(20),
                expectedPutCall(5).bri(50).ct(40)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(54), initialNow.plusDays(1)), // next scene sync
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split call
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );

        // Ensure that old background scene sync was stopped

        setCurrentTimeToAndRun(followUpStates.getFirst()); // background scene sync was canceled
        advanceTimeAndRunAndAssertScenePutCalls(followUpStates.get(1), 1, scene2.id()); // was canceled
        advanceTimeAndRunAndAssertScenePutCalls(followUpStates.get(2), 1, scene2.id()); // was canceled

        assertAllSceneUpdatesAsserted();
    }

    @Test
    void autoSceneStates_modificationTracking_resetsManualOverrideOnReschedule() {
        enableAutoSceneStates();
        enableUserModificationTracking();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene1 = mockSceneLightStates(1, "00:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50));
        Identifier scene2 = mockSceneLightStates(1, "12:00",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(200),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100));
        mockGetAllScenes(scene1, scene2);

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        // State 1 -> runs normally

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene1.id(),
                expectedPutCall(4).bri(100),
                expectedPutCall(5).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(12)); // next day

        // State 2 -> manual override

        setGroupStateResponses(1,
                expectedState().id("/lights/4").brightness(200), // overridden
                expectedState().id("/lights/5").brightness(50)
        );
        advanceTimeAndRunAndAssertScenePutCalls(states.get(1), 1, scene2.id()); // detects override

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2)); // next day

        // Delete scene2
        simulateSceneDeletion(scene2.id());

        // Reschedules scene1 state -> resets manual override
        ScheduledRunnable adjustedScene1Runnable = ensureRunnable(now, initialNow.plusDays(1));

        advanceTimeAndRunAndAssertScenePutCalls(adjustedScene1Runnable, 1, scene1.id(),
                expectedPutCall(4).bri(100),
                expectedPutCall(5).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void autoSceneStates_withManualSceneState_onDelete_alsoRemovesManualStates() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene = mockSceneLightStates(1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50));
        mockGetAllScenes(scene);
        addState("g1", now, "scene:TestScene");

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // apply state normally

        advanceTimeAndRunAndAssertScenePutCalls(states.getFirst(), 1, scene.id(),
                expectedPutCall(4).bri(100),
                expectedPutCall(5).bri(50)
        );

        ScheduledRunnable nextDayRunnable = ensureNextDayRunnable();

        // delete scene
        simulateSceneDeletion(scene.id());

        advanceTimeAndRunAndAssertScenePutCalls(nextDayRunnable, 1, scene.id()); // was canceled
    }

    @Test
    void autoSceneStates_withManualSceneStates_onRename_removesAndReschedules() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        Identifier scene = mockSceneLightStates(1, 1, "TestScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50));
        mockGetAllScenes(scene);
        addState("g1", "00:00", "scene:TestScene");
        addState("g1", "07:00", "scene:TestScene", "bri:150%");
        addState("g1", "12:00", "bri:80%");

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusHours(7)),
                expectedRunnable(now.plusHours(7), now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        Identifier renamedScene = mockSceneLightStates(1, 1, "RenamedScene",
                ScheduledLightState.builder()
                                   .id("/lights/4")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(50));
        simulateSceneCreatedOrUpdated(renamedScene.id());

        ensureScheduledStates(
                expectedRunnable(now, now.plusHours(12))
        );
    }

    @Test
    void autoSceneStates_overlappingGroups_onDelete_removesAdditionalDelayOnReschedule() {
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        Identifier scene1 = mockSceneLightStates(1, 1, "TestScene1",
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/6")
                                   .bri(50),
                ScheduledLightState.builder()
                                   .id("/lights/7")
                                   .bri(20));
        Identifier scene2 = mockSceneLightStates(2, 2, "TestScene2",
                ScheduledLightState.builder()
                                   .id("/lights/5")
                                   .bri(100),
                ScheduledLightState.builder()
                                   .id("/lights/6")
                                   .bri(50));
        addState("g1", now, "scene:TestScene1");
        addState("g2", now, "scene:TestScene2");
        addState("g1", now.plusMinutes(10), "scene:TestScene1", "bri:50%");
        addState("g2", now.plusMinutes(10), "scene:TestScene2", "bri:50%");

        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // g2
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1))
        );

        simulateSceneDeletion(scene1.id());

        ensureScheduledStates(0);

        advanceTimeAndRunAndAssertScenePutCalls(states.get(0), 1, scene1.id()); // g1.1 was canceled

        // g2.1 unaffected
        advanceTimeAndRunAndAssertScenePutCalls(states.get(1), 2, scene2.id(),
                expectedPutCall(5).bri(100),
                expectedPutCall(6).bri(50)
        );

        // Next day does not have any offset anymore
        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10));

        advanceTimeAndRunAndAssertScenePutCalls(states.get(2), 1, scene1.id()); // g1.2 was canceled

        // g2.2 unaffected
        advanceTimeAndRunAndAssertScenePutCalls(states.get(3), 2, scene2.id(),
                expectedPutCall(5).bri(50),
                expectedPutCall(6).bri(25)
        );

        // Next day does not have any offset anymore
        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2));
    }

    private void mockGetAllScenes(Identifier... scenes) {
        when(mockedHueApi.getAllScenes()).thenReturn(Arrays.asList(scenes));
    }

    private void simulateSceneDeletion(String sceneId) {
        scheduler.getSceneDiscoveryListener().onSceneDeleted(sceneId);
    }

    private void simulateSceneCreatedOrUpdated(String sceneId) {
        scheduler.getSceneDiscoveryListener().onSceneCreatedOrRenamed(sceneId);
    }
}
