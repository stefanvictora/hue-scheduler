package at.sv.hue;

import at.sv.hue.api.GroupInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;

public class HueSchedulerSceneSyncTest extends AbstractHueSchedulerTest {

    @Test
    void sceneSync_groupState_createsAndUpdatesScene_evenIfGroupIsOff_stillSchedulesForNextDay() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:150");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        mockIsGroupOff(1, true);
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // no put call

        // still updates scene
        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150));

        // still schedules next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_groupState_singleState_createsScene() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_groupState_withNullState_createsScene() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 6);
        addState("g2", now, "bri:100");
        addState("g2", now.plusMinutes(10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(100)
        );

        assertSceneUpdate("/groups/2", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );
    }

    @Test
    void sceneSync_delayGreaterThanZero_createsScheduledTaskForSync() {
        sceneSyncDelayInSeconds = 5;
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:150");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertAllSceneUpdatesAsserted(); // no scene update just yet

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusSeconds(sceneSyncDelayInSeconds), now.plusMinutes(10)), // scene sync schedule
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        setCurrentTimeTo(followUpRunnables.getFirst());
        followUpRunnables.getFirst().run();

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));
    }

    @Test
    void sceneSync_groupState_noSyncOnPowerOn() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:150");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // Power on -> no scene sync

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/1", expectedPowerOnEnd(now.plusMinutes(10))).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnable,
                expectedGroupPutCall(1).bri(100)
        );
    }

    @Test
    void sceneSync_initiallyOff_turnedOnAfterwards_noAdditionalSyncOnPowerOnRetry() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        addState(1, now, "bri:100");
        addState(1, "12:00", "bri:200");

        mockIsLightOff(1, true);

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst()); // light is off, no put call

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(100));

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(12)) // next day
        );

        mockIsLightOff(1, false);

        List<ScheduledRunnable> powerOnRetryRunnables = simulateLightOnEvent(
                expectedRunnable(now, now.plusHours(12))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRetryRunnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        assertAllSceneUpdatesAsserted(); // no additional scene sync on power-on
    }

    @Test
    void sceneSync_initiallyOff_interpolate_turnedOnAfterwards_noAdditionalSyncOnPowerOnRetry_doesNotAffectInterpolatedSceneSyncs() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:150", "interpolate:true");
        addState(1, now.plusMinutes(20), "bri:200");

        mockIsLightOff(1, true);

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst()); // light is off, no put call

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(100));

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), initialNow.plusMinutes(20)), // scene sync schedule
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(20)) // next day
        );

        mockIsLightOff(1, false);

        List<ScheduledRunnable> powerOnRetryRunnables = simulateLightOnEvent(
                expectedRunnable(now, now.plusMinutes(20))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRetryRunnables.getFirst(),
                expectedPutCall(1).bri(100),
                expectedPutCall(1).bri(150).transitionTime(tr("10min"))
        );

        assertAllSceneUpdatesAsserted(); // no additional scene sync on power-on

        // interpolated sync call schedule is not affected and continues independently
        setCurrentTimeToAndRun(followUpRunnables.getFirst());

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(110));

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), initialNow.plusMinutes(20)) // next scene sync schedule
        );
    }

    @Test
    void sceneSync_longTransition_usesSplitCall_splitCallDoesNotTriggerSceneSync() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5);
        addState("g1", now, "bri:100");
        addState("g1", now.plusHours(2), "bri:200", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100),
                expectedGroupPutCall(1).bri(183).transitionTime(MAX_TRANSITION_TIME)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(100));

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(9), now.plusDays(1)), // scene sync schedule
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // split call
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // Run split call -> triggers no scene sync

        ScheduledRunnable splitCall = followUpRunnables.get(1);
        advanceTimeAndRunAndAssertGroupPutCalls(splitCall,
                expectedGroupPutCall(1).bri(200).transitionTime(tr("20min"))
        );

        // performs no additional scene sync
    }

    @Test
    void sceneSync_groupState_createsAndUpdatesScene_interpolation_updatesSceneWithInterpolatedState_schedulesAdditionalSceneSync() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 5);
        addState("g2", now, "bri:100");
        addState("g2", now.plusMinutes(10), "bri:150", "interpolate:true");
        addState("g2", now.plusMinutes(20), "bri:200");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(100),
                expectedGroupPutCall(2).bri(150).transitionTime(tr("10min"))
        );

        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(100));

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(20)), // scene sync schedule
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(20)) // next day
        );

        setCurrentTimeTo(followUpRunnables.getFirst());
        followUpRunnables.getFirst().run();

        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(110));

        ScheduledRunnable syncRunnable2 = ensureRunnable(now.plusMinutes(2),
                initialNow.plusMinutes(20)); // next sync, correct end

        // power on
        advanceCurrentTime(Duration.ofMinutes(4));
        mockIsGroupOff(2, false);
        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/2",
                expectedRunnable(now, initialNow.plusMinutes(20))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnable,
                expectedGroupPutCall(2).bri(130),
                expectedGroupPutCall(2).bri(150).transitionTime(tr("4min"))
        );

        setCurrentTimeTo(runnables.get(2)); // exactly at end

        syncRunnable2.run(); // already ended, no additional sync
    }

    @Test
    void sceneSync_usesFullPicture_singleGroup_withNullState_stopsFullPictureCalculation() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 7);
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10)); // stops full picture propagation
        addState("g1", now.plusMinutes(20), "ct:500");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        // first state: uses full picture

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100).ct(500) // only because of initial run
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).bri(100).ct(500));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // second state: stops at null state

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).ct(500)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).ct(500));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_usesFullPicture_multipleOverlappingGroups_withNullStateOnSmallerGroup_notInterpretedAsOverride_usesStateFromBiggerGroup() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 7, 8);
        mockGroupLightsForId(2, 7);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addState("g1", now, "bri:110");
        addState("g2", now, "bri:120");
        addState("g1", now.plusMinutes(10), "bri:210");
        addState("g2", now.plusMinutes(10)); // null state, not scheduled but used during full picture to stop propagation

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1 first state
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // g2
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)) // g1 second state
        );

        // g1 first state

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(110)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(7).bri(120));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1 second state

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(1).bri(210)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(7).bri(210), expectedPutCall(8).bri(210)); // no overrides from g2
        assertSceneUpdate("/groups/2", expectedPutCall(7).bri(210)); // takes state from bigger group g1; does not use full picture

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_sceneUpdateConsidersFullPicture_usesMissingPropertiesFromPreviousStates_alsoForLightOn() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 6);
        addState("g1", now, "bri:100", "ct:500");
        addState("g1", now.plusMinutes(10), "bri:150");
        addState("g1", now.plusMinutes(20), "x:0.4", "y:0.5");
        addState("g1", now.plusMinutes(30), "x:0.2", "y:0.2");
        addState("g1", now.plusMinutes(40), "bri:200");
        addState("g1", now.plusMinutes(50), "bri:250");
        addState("g1", now.plusMinutes(60), "on:false");
        addState("g1", now.plusMinutes(70), "ct:200");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusMinutes(40)),
                expectedRunnable(now.plusMinutes(40), now.plusMinutes(50)),
                expectedRunnable(now.plusMinutes(50), now.plusMinutes(60)),
                expectedRunnable(now.plusMinutes(60), now.plusMinutes(70)),
                expectedRunnable(now.plusMinutes(70), now.plusDays(1))
        );

        // state 1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100).ct(500)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(100).ct(500));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        ScheduledRunnable lightOn1 = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(lightOn1,
                expectedGroupPutCall(1).bri(100).ct(500)
        );

        // state 2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).bri(150) // only bri
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150).ct(500)); // with additional ct from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(1).plusMinutes(20)) // next day
        );

        ScheduledRunnable lightOn2 = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusMinutes(20))
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(lightOn2,
                expectedGroupPutCall(1).bri(150).ct(500) // light on also uses full picture
        );

        // state 3

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(1).x(0.4).y(0.5) // only xy
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150).x(0.4).y(0.5)); // with additional bri but ignored ct from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(1).plusMinutes(30)) // next day
        );

        // state 4

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3),
                expectedGroupPutCall(1).x(0.2).y(0.2) // only xy
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(150).x(0.2).y(0.2));

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusMinutes(40)) // next day
        );

        // state 5

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(4),
                expectedGroupPutCall(1).bri(200) // only bri
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(200).x(0.2).y(0.2)); // with additional xy from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(40), initialNow.plusDays(1).plusMinutes(50)) // next day
        );

        // state 6

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(5),
                expectedGroupPutCall(1).bri(250) // only bri
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).bri(250).x(0.2).y(0.2)); // with additional x/y from previous state

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(50), initialNow.plusDays(1).plusMinutes(60)) // next day
        );

        // state 7

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(6),
                expectedGroupPutCall(1).on(false) // only on
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).on(false)); // does not use any other properties

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(60), initialNow.plusDays(1).plusMinutes(70)) // next day
        );

        // state 8

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(7),
                expectedGroupPutCall(1).ct(200) // only ct
        );

        assertSceneUpdate("/groups/1", expectedPutCall(6).ct(200).bri(250)); // skips "off"

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(70), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_considersOtherOverlappingSchedules_buildsFullPicture() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultLightCapabilities(5);
        mockDefaultLightCapabilities(6);
        mockGroupLightsForId(1, 5, 6, 7);
        addState("5", now, "bri:200");
        addState("6", now, "x:0.2", "y:0.2");
        addState("g1", now, "bri:100", "ct:500");
        addState("5", now.plusMinutes(20), "bri:250");
        addState("6", now.plusMinutes(20), "x:0.4", "y:0.3");
        addState("g1", now.plusMinutes(20), "bri:150", "ct:500");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(20)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(20)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(20), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(100).ct(500)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(200), expectedPutCall(6).x(0.2).y(0.2), expectedPutCall(7).bri(100).ct(500));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(20)); // next day

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(3),
                expectedGroupPutCall(1).bri(150).ct(500)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(250), expectedPutCall(6).x(0.4).y(0.3), expectedPutCall(7).bri(150).ct(500));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_considersOtherOverlappingSchedules_otherGroups_andIndividualLights_onlyConsidersGroupsSmallerInSize_buildsFullPicture() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7, 8);
        mockGroupLightsForId(2, 5, 6, 7);
        mockGroupLightsForId(3, 5);
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addKnownLightIdsWithDefaultCapabilities(7);
        addState("g1", now, "bri:100");
        addState("g2", now, "bri:120");
        addState("g3", now, "bri:130");
        addState(7, now, "ct:300");
        addState("g1", now.plusMinutes(10), "bri:111");
        addState("g2", now.plusMinutes(10), "on:false");
        addState("g3", now.plusMinutes(10), "bri:133");
        addState(7, now.plusMinutes(10), "ct:400");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // g2
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)), // g3
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)), // 7
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(2), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(2), now.plusDays(1))
        );

        // g1 bri:100 -> other groups: 2, 3; both are smaller; uses overlapping 5 from 3, 6 from 2, 7 from individual

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300), expectedPutCall(8).bri(100));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g2 bri:120 -> other groups: 1, 3; only 3 and 7 as individual are smaller

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(1),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300), expectedPutCall(8).bri(100));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(120), expectedPutCall(7).ct(300));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(130));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1 bri:111; second state

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(4),
                expectedGroupPutCall(1).bri(111)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400), expectedPutCall(8).bri(111));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(133));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // g2 on:false; second state

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(5),
                expectedGroupPutCall(2).on(false)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400), expectedPutCall(8).bri(111));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(133), expectedPutCall(6).on(false), expectedPutCall(7).ct(400));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(133));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_childGroupHasNoSchedule_createsScene() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        addState("g1", now, "bri:110");
        addState("g1", now.plusMinutes(10), "bri:210");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(110)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(110), expectedPutCall(6).bri(110), expectedPutCall(7).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(110), expectedPutCall(6).bri(110));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_parentGroupsHaveNoSchedule_createsIntermediateScenes() {
        enableSceneSync();

        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 5);
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        addState("g3", now, "bri:130");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(3).bri(130)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(130));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(130));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(3).bri(230)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(230));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(230));
        assertSceneUpdate("/groups/3", expectedPutCall(5).bri(230));

        ensureRunnable(initialNow.plusMinutes(10).plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_individualLights_withOverlappingParentGroups_haveNoSchedule_createsIntermediateScenes() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        mockDefaultLightCapabilities(5);
        addState("5", now, "bri:110");
        addState("5", now.plusMinutes(10), "bri:120");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(5).bri(110)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(110));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_nonOverlappingGroupStates_butBelongToSameRoom_considersAllLightsForRoom() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 7);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1, 3);
        addState("g2", now, "bri:120");
        addState("g3", now, "bri:130");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1))
        );

        // g2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).bri(130));
        assertSceneUpdate("/groups/2", expectedPutCall(5).bri(120), expectedPutCall(6).bri(120));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // g3

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(3).bri(130)
        );

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).bri(130));
        assertSceneUpdate("/groups/3", expectedPutCall(7).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_createsIntermediateParentScenes_parentScenesWithNoScheduleAlsoUseBiggerGroupsForFullPicture() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(4);
        mockGroupLightsForId(1, 5, 6, 7, 8); // uses 5 from g4, 6 and 7 from g2
        mockGroupLightsForId(2, 5, 6, 7);
        mockGroupLightsForId(3, 5, 6); // uses 5 from g4 and 6 from g2 (!)
        mockGroupLightsForId(4, 5);
        mockAssignedGroups(5, 1, 2, 3, 4);
        mockAssignedGroups(6, 1, 2, 3);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addState("g2", now, "bri:120");
        addState("g4", now, "bri:140");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState("g4", now.plusMinutes(10), "bri:240");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(10), now.plusDays(1))
        );

        // g2: update for g1 uses both g2 and g4, while defaulting to off

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120));
        assertSceneUpdate("/groups/4", expectedPutCall(5).bri(140));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g4: update for g3 (no explicit schedule) also uses bigger parent g2 (with schedule)

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(4).bri(140)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(140), expectedPutCall(6).bri(120));
        assertSceneUpdate("/groups/4", expectedPutCall(5).bri(140));

        ensureRunnable(initialNow.plusSeconds(1).plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_doesNotUseParentIfExplicitScheduleDefined() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7, 8);
        mockGroupLightsForId(2, 5, 6, 7);
        mockGroupLightsForId(3, 5, 6);
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2, 3);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addState("g1", now, "bri:110");
        addState("g2", now, "bri:120");
        addState("g3", now, "bri:130");
        addState("g1", now.plusMinutes(10), "bri:210");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1).plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(2).plusMinutes(10), now.plusDays(1))
        );

        // g1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(110)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g3

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(3).bri(130)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120), expectedPutCall(8).bri(110));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130), expectedPutCall(7).bri(120));
        assertSceneUpdate("/groups/3",
                expectedPutCall(5).bri(130), expectedPutCall(6).bri(130));

        ensureRunnable(initialNow.plusDays(1).plusSeconds(2), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void sceneSync_singleSchedule_childSceneActivated_usesParentState_doesNotResetOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6);
        mockGroupLightsForId(2, 5);
        mockGroupLightsForId(3, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 3);
        addState("g1", now, "bri:120");
        addState("g1", now.plusMinutes(10), "bri:220");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // g1.1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(120)
        );

        ScheduledRunnable nextDay1 = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1.2 -> detects override

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // override detected

        ScheduledRunnable nextDay2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // Activate synced scene for g2 -> only updates light 5

        simulateSyncedSceneActivated("/groups/2", "/lights/5");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable2);

        // next day, still detected as overridden

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(220),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertGroupPutCalls(nextDay1); // still overridden

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // activate synced scene for group 1 -> resets override

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable3 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(10))
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable3);

        // Applied normally again:

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(nextDay2,
                expectedGroupPutCall(1).bri(220)
        );

        ensureRunnable(initialNow.plusDays(2).plusMinutes(10), initialNow.plusDays(3)); // next day
    }

    @Test
    void sceneSync_singleSchedule_withInterpolation_childSceneActivated_usesParentState_resetsOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6);
        mockGroupLightsForId(2, 5);
        mockGroupLightsForId(3, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 3);
        addState("g1", now.plusMinutes(5), "bri:120", "tr-before:5min");
        addState("g1", now.plusMinutes(10), "bri:220", "tr-before:5min");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        // g1.1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(220), // interpolated
                expectedGroupPutCall(1).bri(120).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay1 = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(5)); // next day

        // g1.2 -> detects override

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // override detected

        ScheduledRunnable nextDay2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(5), initialNow.plusDays(2)); // next day

        // Activate synced scene for g2 -> re-triggers the parent state, rests override

        simulateSyncedSceneActivated("/groups/2", "/lights/5");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable2,
                expectedGroupPutCall(1).bri(220).transitionTime(tr("5min"))
        );

        // next day, still detected as overridden

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(220),
                expectedState().id("/lights/6").brightness(220)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(nextDay1,
                expectedGroupPutCall(1).bri(120).transitionTime(tr("5min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // activate synced scene for group 1

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable3 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(5))
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable3,
                expectedGroupPutCall(1).bri(120).transitionTime(tr("5min"))
        );

        // Next day 2

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(nextDay2,
                expectedGroupPutCall(1).bri(220).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(2).plusMinutes(5), initialNow.plusDays(3)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_childSceneActivated_usesParentState_doesNotResetOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6);
        mockGroupLightsForId(2, 5);
        mockGroupLightsForId(3, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 3);
        addState("g1", now, "bri:120");
        addState("g3", now, "bri:130");
        addState("g1", now.plusMinutes(10), "bri:220");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1))
        );

        // g1.1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(120)
        );

        ScheduledRunnable nextDayG1_1 = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g3.1

        manualOverrideTracker.onManuallyOverridden("/groups/3"); // simulate override

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1));

        ScheduledRunnable nextDayG3_1 = ensureRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g1.2 -> also detects override

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2)); // override detected

        ScheduledRunnable nextDayG1_2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // Activate synced scene for g2 -> only updates light 5

        simulateSyncedSceneActivated("/groups/2", "/lights/5");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable2);

        // g3.2 -> still overridden

        setGroupStateResponses(3,
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // still overridden
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3));

        ScheduledRunnable nextDayG3_2 = ensureRunnable(initialNow.plusDays(1).plusMinutes(10).plusSeconds(1), initialNow.plusDays(2));

        // next day G1_1, still detected as overridden

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(230),
                expectedState().id("/lights/6").brightness(120 - BRIGHTNESS_OVERRIDE_THRESHOLD) // overridden
        );
        advanceTimeAndRunAndAssertGroupPutCalls(nextDayG1_1); // still overridden

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // activate synced scene for group 1 -> resets override

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable3 = ensureScheduledStates(
                // for g1
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(10)),
                // for g3
                expectedRunnable(now.plusSeconds(1), initialNow.plusMinutes(10)), // already ended
                expectedRunnable(now.plusSeconds(1), initialNow.plusDays(1)) // already ended
        ).get(1);

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable3);

        // Applied normally again:

        // G3_1

        setGroupStateResponses(3,
                expectedState().id("/lights/6").brightness(130)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(nextDayG3_1,
                expectedGroupPutCall(3).bri(130)
        );

        ensureRunnable(initialNow.plusDays(2).plusSeconds(1), initialNow.plusDays(2).plusMinutes(10)); // next day

        // G1_2

        setGroupStateResponses(1,
                expectedState().id("/lights/5").brightness(120),
                expectedState().id("/lights/6").brightness(130)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(nextDayG1_2,
                expectedGroupPutCall(1).bri(220)
        );

        ensureRunnable(initialNow.plusDays(2).plusMinutes(10), initialNow.plusDays(3)); // next day
    }

    @Test
    void sceneSync_multipleSchedules_offStateIsCorrectlyReCheckedAfterSyncedSceneTurnedOnContainingOffStates_bugCase() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7, 8);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 7, 8);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1, 3);
        mockAssignedGroups(8, 1, 3);
        addState("g2", now, "bri:120");
        addState("g3", now, "on:false");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // g2.1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(120)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).on(false), expectedPutCall(8).on(false));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(120), expectedPutCall(6).bri(120));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // g3.1

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(3).on(false)
        );
        simulateLightOffEvent("/groups/3");

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(120), expectedPutCall(6).bri(120), expectedPutCall(7).on(false), expectedPutCall(8).on(false));
        assertSceneUpdate("/groups/3",
                expectedPutCall(7).on(false), expectedPutCall(8).on(false));

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // Activate synced scene for g1 -> only applies g2 state again

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6", "/lights/7", "/lights/8");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusMinutes(10))).getFirst();
        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable); // no further update needed, no interpolations

        // g2.2

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(2).bri(220)
        );

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(220), expectedPutCall(6).bri(220), expectedPutCall(7).bri(230), expectedPutCall(8).bri(230));
        assertSceneUpdate("/groups/2",
                expectedPutCall(5).bri(220), expectedPutCall(6).bri(220));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // g3.2: bug case -> synced scene activation triggers turnOn events internally; we need to still recheck off state to not apply states where the lights are off

        mockIsGroupOff(3, true);
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3)); // no update, re-checks off state by api

        assertSceneUpdate("/groups/1",
                expectedPutCall(5).bri(220), expectedPutCall(6).bri(220), expectedPutCall(7).bri(230), expectedPutCall(8).bri(230));
        assertSceneUpdate("/groups/3",
                expectedPutCall(7).bri(230), expectedPutCall(8).bri(230));

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void sceneSync_interpolate_withDayCrossover_correctlyFindsActivePutCall() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        setCurrentAndInitialTimeTo(now.withHour(13)); // 13:00
        addState(1, "12:00", "bri:100");
        addState(1, "01:00", "bri:200", "interpolate:true"); // 01:00 -> 12:00

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)),
                expectedRunnable(now.plusDays(1).minusHours(1), now.plusDays(1).minusHours(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(108),
                expectedPutCall(1).bri(113).transitionTime(tr("40min"))
        );

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(108));

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(40), initialNow.plusDays(1).minusHours(1)), // next split call
                expectedRunnable(now.plusMinutes(61), now.plusDays(1).minusHours(1)), // next scene sync
                expectedRunnable(initialNow.plusDays(1).minusHours(1), initialNow.plusDays(2).minusHours(1)) // next day
        );
    }

    @Test
    void sceneSync_withDayCrossover_startedAfterMidnight_correctSync() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        addState(1, "12:00", "bri:100");
        addState(1, "22:00", "bri:200");

        setCurrentAndInitialTimeTo(now.plusDays(1).withHour(3)); // 03:00

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusHours(9)),
                expectedRunnable(now.plusHours(9), now.plusHours(19))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(200)
        );

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(200));

        ensureScheduledStates(
                expectedRunnable(now.plusHours(19), now.plusDays(1).plusHours(9)) // next day
        );
    }

    @Test
    void sceneSync_withAdditionalAreas_considersThem_ignoresDuplicate() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 1);
        when(mockedHueApi.getAdditionalAreas(List.of("/lights/1")))
                .thenReturn(List.of(
                        new GroupInfo("/groups/7", List.of("/lights/1", "/lights/3")),
                        new GroupInfo("/groups/5", List.of("/lights/1")) // duplicate
                ));
        addState(1, "00:00", "bri:100");
        addState(1, "12:00", "bri:200");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        assertSceneUpdate("/groups/5", expectedPutCall(1).bri(100));
        assertSceneUpdate("/groups/7", expectedPutCall(1).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusHours(12)) // next day
        );
    }

    @Test
    void sceneSync_apiThrowsError_doesNotSkipSchedule_retriesSync() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5);
        addState("g1", now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        // Let scene sync fail -> schedules a retry
        mockSceneSyncFailure("/groups/1");

        // Schedule updates group normally
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plusMinutes(syncFailureRetryInMinutes), now.plusDays(1)), // sync retry
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
        ScheduledRunnable retrySync = followUpRunnables.getFirst();

        resetMockedApi();
        mockGroupLightsForId(1, 5);

        setCurrentTimeTo(retrySync);
        retrySync.run();

        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(100));
    }

    @Test
    void sceneSync_apiThrowsError_interpolate_retries() {
        enableSceneSync();

        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(2, 5, 6);
        addState("g2", now, "bri:100");
        addState("g2", now.plusMinutes(10), "bri:150", "interpolate:true");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        mockSceneSyncFailure("/groups/2");

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(2).bri(100),
                expectedGroupPutCall(2).bri(150).transitionTime(tr("10min"))
        );
        expectedSceneUpdates++;

        ensureScheduledStates(
                expectedRunnable(now.plusMinutes(syncFailureRetryInMinutes), now.plusDays(1)), // scene sync retry
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_lightState_ignored() {
        enableSceneSync();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void sceneSync_groupState_notEnabled_ignored() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6);
        addState("g1", now, "bri:100");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void clearCachesAndReSyncScenes_syncsCurrentActiveStates_ignoresRepeatedSyncsForInterpolations() {
        enableSceneSync();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 11);
        mockGroupLightsForId(2, 22);
        mockGroupLightsForId(3, 33);
        addState("g1", now, "bri:110");
        addState("g2", now, "bri:120");
        addState("g3", now, "on:false");
        addState("g1", now.plusMinutes(10), "bri:210");
        addState("g2", now.plusMinutes(10), "bri:220", "tr-before:5min");
        addState("g3", now.plusMinutes(10), "bri:230");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        scheduler.clearCachesAndReSyncScenes();

        assertSceneUpdate("/groups/1", expectedPutCall(11).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(22).bri(120));
        assertSceneUpdate("/groups/3", expectedPutCall(33).on(false));

        advanceCurrentTime(Duration.ofMinutes(7));

        scheduler.clearCachesAndReSyncScenes();

        assertSceneUpdate("/groups/1", expectedPutCall(11).bri(110));
        assertSceneUpdate("/groups/2", expectedPutCall(22).bri(160)); // interpolated value
        assertSceneUpdate("/groups/3", expectedPutCall(33).on(false));

        advanceCurrentTime(Duration.ofMinutes(3));

        scheduler.clearCachesAndReSyncScenes();

        assertSceneUpdate("/groups/1", expectedPutCall(11).bri(210));
        assertSceneUpdate("/groups/2", expectedPutCall(22).bri(220));
        assertSceneUpdate("/groups/3", expectedPutCall(33).bri(230));

        verify(mockedHueApi, times(3)).clearCaches();
    }

    @Test
    void clearCachesAndReSyncScenes_sceneSyncNotEnabled_justClearsCaches() {
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 11);
        addState("g1", now, "bri:110");
        addState("g1", now.plusMinutes(10), "bri:210");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        scheduler.clearCachesAndReSyncScenes();

        verify(mockedHueApi).clearCaches();
    }
}
