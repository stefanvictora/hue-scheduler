package at.sv.hue;

import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupNotFoundException;
import com.launchdarkly.eventsource.MessageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class HueSchedulerMembershipTest extends AbstractHueSchedulerTest {

    @AfterEach
    void discardFutureTasks() {
        discardScheduledTasks();
    }

    @ParameterizedTest
    @ValueSource(strings = {"room", "zone"})
    void groupMembershipChangeImmediatelyAppliesCurrentGroupProperties(String resourceType) throws Exception {
        controlGroupLightsIndividually = true;
        create();
        addDefaultGroupState(1, now, 4, 5);
        startScheduler();
        runDueTasks();
        assertPutCalls(expectedPutCall(4).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT),
                expectedPutCall(5).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));

        mockGroupLightsForId(1, 4, 5, 6);
        membershipChanged(resourceType, 4, 5, 6);
        runDueTasks();

        assertPutCalls(expectedPutCall(4).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT),
                expectedPutCall(5).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT),
                expectedPutCall(6).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
    }

    private void roomMembershipChanged(Integer... lights) throws Exception {
        membershipChanged("room", lights);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletedGroupDoesNotPreventOtherMembershipUpdates(boolean deletedGroupFirst) throws Exception {
        controlGroupLightsIndividually = true;
        create();
        if (deletedGroupFirst) addDefaultGroupState(1, now, 4);
        addDefaultGroupState(2, now, 5);
        if (!deletedGroupFirst) addDefaultGroupState(1, now, 4);
        var staleTasks = startScheduler(2);

        when(mockedHueApi.getGroupLights("/groups/1")).thenThrow(new GroupNotFoundException("Deleted room"));
        mockAssignedGroups(4);
        mockGroupLightsForId(2, 5, 6);
        roomMembershipChanged(5, 6);
        staleTasks.forEach(ScheduledRunnable::run);
        runDueTasks();
        assertPutCalls(expectedPutCall(5).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT),
                expectedPutCall(6).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));

        doThrow(new AssertionError("Deleted group should have been removed"))
                .when(mockedHueApi).getGroupLights("/groups/1");
        mockGroupLightsForId(2, 6);
        mockAssignedGroups(5);
        roomMembershipChanged(6);
        runDueTasks();
        assertPutCalls(expectedPutCall(6).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
    }

    @Test
    void deletedGroupDuringSceneReloadInvalidatesWorkAndReschedulesOverlappingTargets() {
        controlGroupLightsIndividually = true;
        create();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
        var scene = mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100));
        addState("g1", now, "scene:Colors");
        addDefaultGroupState(2, now, 4, 5);
        mockAssignedGroups(4, 1, 2);
        var staleTasks = startScheduler(2);

        when(mockedHueApi.getGroupLights("/groups/1")).thenThrow(new GroupNotFoundException("Deleted room"));
        mockAssignedGroups(4, 2);
        scheduler.onSceneResourceModified(scene.id());
        staleTasks.forEach(ScheduledRunnable::run);
        runDueTasks();
        assertPutCalls(expectedPutCall(4).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT),
                expectedPutCall(5).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
    }

    private void membershipChanged(String resourceType, Integer... lights) throws Exception {
        String children = Arrays.stream(lights)
                .map(light -> "{\"rid\":\"/lights/" + light + "\",\"rtype\":\"light\"}")
                .collect(Collectors.joining(","));
        scheduler.createHueEventHandler().onMessage("message", new MessageEvent("""
                [{"type":"update","data":[{"id":"group-resource-1","type":"%s","children":[%s]}]}]
                """.formatted(resourceType, children)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void membershipAndAutomaticSceneUpdatesPreserveRecordedManualOverride(boolean roomEventFirst) throws Exception {
        enableUserModificationTracking();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        var scene = mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300));
        addState("g1", now, "scene:Colors");
        addState("g1", now.plusHours(1), "scene:Colors");
        startScheduler();
        runDueTasks();
        assertScenePutCalls(1, scene.id(), expectedPutCall(4).bri(100).ct(200), expectedPutCall(5).bri(50).ct(300));

        setGroupStateResponses(1,
                expectedState().id("/lights/4").brightness(200).colorTemperature(200).colormode(ColorMode.CT),
                expectedState().id("/lights/5").brightness(200).colorTemperature(300).colormode(ColorMode.CT));
        setCurrentTimeTo(now.plusHours(1));
        runDueTasks(); // the scheduler detects and records the user's manual adjustment
        assertAllScenePutCallsAsserted();

        if (roomEventFirst) {
            mockGroupLightsForId(1, 4, 5, 6);
            roomMembershipChanged(4, 5, 6);
            runDueTasks();
        }
        mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300),
                ScheduledLightState.builder().id("/lights/6").bri(75).ct(400));
        simulateSceneModified(1, "Colors");
        runDueTasks();
        if (!roomEventFirst) {
            mockGroupLightsForId(1, 4, 5, 6);
            roomMembershipChanged(4, 5, 6);
            runDueTasks();
        }

        assertAllScenePutCallsAsserted(); // no reapplication while paused
    }

    @Test
    void addedSceneLightReceivesInheritedColorInCurrentGroupSchedule() {
        enableSceneSync();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300));
        addState("g1", now, "bri:180");
        addState("g1", now.plusHours(2), "scene:Colors");
        startScheduler();
        runDueTasks();
        assertScenePutCalls(1, null, expectedPutCall(4).bri(180).ct(200), expectedPutCall(5).bri(180).ct(300));
        assertSceneUpdate("/groups/1", expectedPutCall(4).bri(180).ct(200), expectedPutCall(5).bri(180).ct(300));

        mockGroupLightsForId(1, 4, 5, 6);
        mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300),
                ScheduledLightState.builder().id("/lights/6").bri(75).ct(400));
        simulateSceneModified(1, "Colors");
        runDueTasks();

        assertSceneUpdate("/groups/1", expectedPutCall(4).bri(180).ct(200),
                expectedPutCall(5).bri(180).ct(300), expectedPutCall(6).bri(180).ct(400));
        assertScenePutCalls(1, null, expectedPutCall(4).bri(180).ct(200),
                expectedPutCall(5).bri(180).ct(300), expectedPutCall(6).bri(180).ct(400));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sceneSyncWaitsForMatchingRoomAndSceneMembership(boolean roomEventFirst) throws Exception {
        enableSceneSync();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300));
        addState("g1", now, "bri:180");
        addState("g1", now.plusHours(2), "scene:Colors");
        startScheduler();
        runDueTasks();
        assertScenePutCalls(1, null, expectedPutCall(4).bri(180).ct(200), expectedPutCall(5).bri(180).ct(300));
        assertSceneUpdate("/groups/1", expectedPutCall(4).bri(180).ct(200), expectedPutCall(5).bri(180).ct(300));

        if (roomEventFirst) {
            mockGroupLightsForId(1, 4, 5, 6);
            roomMembershipChanged(4, 5, 6);
        } else {
            addThirdSceneLight();
        }
        runDueTasks();
        assertAllScenePutCallsAsserted();
        assertAllSceneUpdatesAsserted(); // do not write a scene using incomplete membership

        simulateSceneModified(1, "Colors"); // a repeated scene notification must not bypass the wait
        runDueTasks();
        assertAllScenePutCallsAsserted();
        assertAllSceneUpdatesAsserted();

        if (roomEventFirst) {
            addThirdSceneLight();
        } else {
            mockGroupLightsForId(1, 4, 5, 6);
            roomMembershipChanged(4, 5, 6);
        }
        runDueTasks();
        assertScenePutCalls(1, null, expectedPutCall(4).bri(180).ct(200),
                expectedPutCall(5).bri(180).ct(300), expectedPutCall(6).bri(180).ct(400));
        assertSceneUpdate("/groups/1", expectedPutCall(4).bri(180).ct(200),
                expectedPutCall(5).bri(180).ct(300), expectedPutCall(6).bri(180).ct(400));
    }

    private void addThirdSceneLight() {
        mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300),
                ScheduledLightState.builder().id("/lights/6").bri(75).ct(400));
        simulateSceneModified(1, "Colors");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyGroupStaysDormantAndResumesWhenALightReturns(boolean sceneDefinition) throws Exception {
        enableSceneSync();
        controlGroupLightsIndividually = true;
        create();
        if (sceneDefinition) {
            mockDefaultGroupCapabilities(1);
            mockGroupLightsForId(1, 4);
            mockSceneLightStates(1, "Colors", ScheduledLightState.builder().id("/lights/4")
                    .bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
            addState("g1", now, "scene:Colors");
        } else {
            addDefaultGroupState(1, now, 4);
        }
        startScheduler();
        runDueTasks();
        assertPutCalls(expectedPutCall(4).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
        assertSceneUpdate("/groups/1", expectedPutCall(4).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));

        when(mockedHueApi.getGroupLights("/groups/1")).thenThrow(new EmptyGroupException("No lights"));
        roomMembershipChanged();
        if (sceneDefinition) {
            mockSceneLightStates(1, "Colors");
            simulateSceneModified(1, "Colors");
        }
        simulateLightOnEvent("/groups/1"); // any old waiting callback must also remain inert
        runDueTasks();
        setCurrentTimeTo(now.plusDays(1).plusHours(1));
        runDueTasks(); // old queued work must not control or sync the empty group
        assertAllSceneUpdatesAsserted();

        doReturn(List.of("/lights/5")).when(mockedHueApi).getGroupLights("/groups/1");
        mockAssignedGroups(5, 1);
        roomMembershipChanged(5);
        if (sceneDefinition) {
            mockSceneLightStates(1, "Colors", ScheduledLightState.builder().id("/lights/5")
                    .bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
            simulateSceneModified(1, "Colors");
        }
        runDueTasks();
        assertPutCalls(expectedPutCall(5).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
        assertSceneUpdate("/groups/1", expectedPutCall(5).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void membershipReapplicationPreservesIndividualLightPrecedence(boolean sceneEvent) throws Exception {
        addDefaultGroupState(1, now, 4, 5);
        addKnownLightIdsWithDefaultCapabilities(4);
        addState(4, now, "bri:200", "ct:300");
        if (sceneEvent) {
            mockSceneLightStates(1, "Colors",
                    ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                    ScheduledLightState.builder().id("/lights/5").bri(50).ct(300));
            addState("g1", now.plusHours(2), "scene:Colors");
        }
        startScheduler();
        runDueTasks();
        setCurrentTimeTo(now.plusSeconds(2));
        runDueTasks();
        assertGroupPutCalls(expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
        assertPutCalls(expectedPutCall(4).bri(200).ct(300));

        mockGroupLightsForId(1, 4, 5, 6);
        if (sceneEvent) addThirdSceneLight();
        else roomMembershipChanged(4, 5, 6);
        runDueTasks();
        setCurrentTimeTo(now.plusSeconds(2));
        runDueTasks();
        assertGroupPutCalls(expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT));
        assertPutCalls(expectedPutCall(4).bri(200).ct(300));
    }

    @Test
    void removedSceneLightIsExcludedFromCommandsAndSyncedScene() throws Exception {
        enableSceneSync();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5, 6);
        mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300),
                ScheduledLightState.builder().id("/lights/6").bri(75).ct(400));
        addState("g1", now, "bri:180");
        addState("g1", now.plusHours(2), "scene:Colors");
        startScheduler();
        runDueTasks();
        assertScenePutCalls(1, null, expectedPutCall(4).bri(180).ct(200),
                expectedPutCall(5).bri(180).ct(300), expectedPutCall(6).bri(180).ct(400));
        assertSceneUpdate("/groups/1", expectedPutCall(4).bri(180).ct(200),
                expectedPutCall(5).bri(180).ct(300), expectedPutCall(6).bri(180).ct(400));

        mockGroupLightsForId(1, 4, 5);
        mockAssignedGroups(6);
        roomMembershipChanged(4, 5);
        mockSceneLightStates(1, "Colors",
                ScheduledLightState.builder().id("/lights/4").bri(100).ct(200),
                ScheduledLightState.builder().id("/lights/5").bri(50).ct(300));
        simulateSceneModified(1, "Colors");
        runDueTasks();
        assertScenePutCalls(1, null, expectedPutCall(4).bri(180).ct(200), expectedPutCall(5).bri(180).ct(300));
        assertSceneUpdate("/groups/1", expectedPutCall(4).bri(180).ct(200), expectedPutCall(5).bri(180).ct(300));
    }
}
