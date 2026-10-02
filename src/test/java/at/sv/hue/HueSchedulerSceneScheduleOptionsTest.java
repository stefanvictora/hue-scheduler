package at.sv.hue;

import at.sv.hue.api.Identifier;
import com.launchdarkly.eventsource.MessageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class HueSchedulerSceneScheduleOptionsTest extends AbstractHueSchedulerTest {
    @AfterEach
    void discardFutureTasks() {
        discardScheduledTasks();
    }

    private void prepareScenes(boolean interpolate) {
        interpolateAll = interpolate;
        enableAutoSceneStates();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4);
    }

    private Identifier scene(int id, String name, int brightness) {
        return mockSceneLightStates(1, id, name,
                ScheduledLightState.builder().id("/lights/4").bri(brightness));
    }

    private void mockGetAllScenes(Identifier... scenes) {
        when(mockedHueApi.getAllScenes()).thenReturn(List.of(scenes));
    }

    private void simulateSceneCreatedOrUpdated(String sceneId) {
        scheduler.getSceneDiscoveryListener().onSceneCreatedOrRenamed(sceneId);
    }

    private void simulateSceneDeletion(String sceneId) {
        scheduler.getSceneDiscoveryListener().onSceneDeleted(sceneId);
    }

    @ParameterizedTest
    @CsvSource({"'',0", "' [i:false]',120", "' [tr-b:10min]',110"})
    void startupUsesGlobalInterpolationAndPreservesExplicitChoices(String flags, int startMinutes) {
        prepareScenes(true);
        mockGetAllScenes(scene(1, "00:00 [i:false]", 100), scene(2, "02:00" + flags, 200));
        if (startMinutes == 0) {
            startScheduler(expectedRunnable(now, now.plusDays(1)),
                    expectedRunnable(now.plusDays(1), now.plusDays(1)));
        } else {
            startScheduler(expectedRunnable(now, now.plusMinutes(startMinutes)),
                    expectedRunnable(now.plusMinutes(startMinutes), now.plusDays(1)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createdOrRenamedSceneUsesGlobalInterpolation(boolean rename) {
        prepareScenes(true);
        var first = scene(1, "00:00 [i:false]", 100);
        if (rename) {
            mockGetAllScenes(first, scene(2, "02:00 [i:false]", 200));
        } else {
            mockGetAllScenes(first);
        }
        startScheduler(rename ? 2 : 1);
        var updated = scene(2, "02:00", 200);
        simulateSceneCreatedOrUpdated(updated.id());
        ensureScheduledStates(expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)));
    }

    @Test
    void gapIgnoresStoredActionsAndTheirEditsAndStopsInterpolation() {
        prepareScenes(true);
        var first = scene(1, "00:00 [i:false]", 100);
        var gap = mockSceneLightStates(1, 2, "01:00 [gap]",
                ScheduledLightState.builder().id("/lights/4").on(false));
        var last = scene(3, "02:00 [i]", 200);
        mockGetAllScenes(first, gap, last);
        var tasks = startScheduler(2);
        assertEnd(tasks.getFirst(), now.plusHours(1));
        assertThat(tasks.get(1).getStart()).isEqualTo(now.plusHours(2));
        tasks.getFirst().run();
        assertGroupPutCalls(expectedGroupPutCall(1).bri(100));

        setCurrentTimeTo(initialNow.plusHours(1));
        scene(2, "01:00 [gap]", 250); // even actions made active in the Hue app stay irrelevant
        scheduler.onSceneResourceModified(gap.id());
        simulateLightOnEvent("/groups/1");
        runDueTasks();
        assertGroupPutCalls();
        assertAllScenePutCallsAsserted();

        setCurrentTimeTo(initialNow.plusHours(2));
        tasks.get(1).run();
        assertGroupPutCalls(expectedGroupPutCall(1).bri(200));
    }

    @Test
    void gapAndOffWithIdenticalStoredActionsHaveDifferentSchedulingBehavior() {
        prepareScenes(false);
        var active = scene(1, "00:00", 100);
        var gap = mockSceneLightStates(1, 2, "01:00 [gap]",
                ScheduledLightState.builder().id("/lights/4").on(false));
        var off = mockSceneLightStates(1, 3, "02:00 [off]",
                ScheduledLightState.builder().id("/lights/4").on(false));
        mockGetAllScenes(active, gap, off);

        startScheduler();
        runDueTasks();
        assertGroupPutCalls(expectedGroupPutCall(1).bri(100));

        setCurrentTimeTo(initialNow.plusHours(1));
        simulateLightOnEvent("/groups/1");
        runDueTasks();
        assertGroupPutCalls();
        assertAllScenePutCallsAsserted();

        setCurrentTimeTo(initialNow.plusHours(2));
        runDueTasks();
        assertGroupPutCalls(expectedGroupPutCall(1).on(false));
    }

    @Test
    void gapDoesNotWaitForItsStoredActionsToMatchNewRoomMembership() throws Exception {
        prepareScenes(false);
        var active = scene(1, "00:00", 100);
        var gap = scene(2, "02:00 [gap]", 200);
        mockGetAllScenes(active, gap);
        startScheduler(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, 1, "00:00",
                ScheduledLightState.builder().id("/lights/4").bri(100),
                ScheduledLightState.builder().id("/lights/5").bri(100));
        scheduler.onSceneResourceModified(active.id());
        scheduler.createHueEventHandler().onMessage("message", new MessageEvent("""
                [{"type":"update","data":[{"id":"room","type":"room"}]}]
                """));
        runDueTasks();
        assertGroupPutCalls(expectedGroupPutCall(1).bri(100));
    }

    @Test
    void renamingAndDeletingGapUpdatesItsBoundary() {
        prepareScenes(false);
        var active = scene(1, "00:00", 100);
        var gap = scene(2, "02:00 [gap]", 200);
        mockGetAllScenes(active, gap);
        assertEnd(startScheduler(1).getFirst(), now.plusHours(2));

        simulateSceneCreatedOrUpdated(scene(2, "03:00 [gap]", 200).id());
        assertEnd(ensureScheduledStates(1).getFirst(), now.plusHours(3));
        simulateSceneCreatedOrUpdated(scene(2, "03:00", 200).id());
        assertThat(ensureScheduledStates(2).get(1).getStart()).isEqualTo(now.plusHours(3));
        simulateSceneCreatedOrUpdated(scene(2, "03:00 [gap]", 200).id());
        ensureScheduledStates(1);
        simulateSceneDeletion(gap.id());
        assertEnd(ensureScheduledStates(1).getFirst(), now.plusDays(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void gapHonorsWeekdayRestrictions(boolean weekend) {
        if (weekend) setCurrentAndInitialTimeTo(now.plusDays(1)); // Friday -> Saturday
        prepareScenes(false);
        mockGetAllScenes(scene(1, "00:00", 100), scene(2, "12:00 [Mo-Fr,gap]", 200));
        assertEnd(startScheduler(1).getFirst(), weekend ? now.plusDays(1) : now.plusHours(12));
    }

    @Test
    void gapLetsOverlappingGroupSupplySceneSyncValues() {
        enableAutoSceneStates();
        enableSceneSync();
        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 7, 8);
        mockGroupLightsForId(2, 7);
        mockAssignedGroups(7, 1, 2);
        mockAssignedGroups(8, 1);
        addState("g1", "00:00", "bri:110");
        addState("g1", "00:10", "bri:210");
        var active = mockSceneLightStates(2, "00:00",
                ScheduledLightState.builder().id("/lights/7").bri(120));
        var gap = mockSceneLightStates(2, "00:10 [gap]",
                ScheduledLightState.builder().id("/lights/7").on(false));
        mockGetAllScenes(active, gap);
        var tasks = startScheduler(3);
        advanceTimeAndRunAndAssertGroupPutCalls(tasks.get(2), expectedGroupPutCall(1).bri(210));
        assertSceneUpdate("/groups/1", expectedPutCall(7).bri(210), expectedPutCall(8).bri(210));
        assertSceneUpdate("/groups/2", expectedPutCall(7).bri(210));
    }

    @Test
    void nestedTransitionExpressionDeterminesEarlyStart() {
        prepareScenes(false);
        String name = "02:00 [tr-b:max(min(01:00,01:30),00:30),f]";
        mockGetAllScenes(scene(1, "00:00", 100), scene(2, name, 200));
        assertThat(startScheduler(2).get(1).getStart()).isEqualTo(now.plusHours(1));
    }
}
