package at.sv.hue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class HueSchedulerInterpolationWaitTest extends AbstractHueSchedulerTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "7s")
    void syncedSceneRecall_waitsForPreviousTransition_beforeResumingInterpolation(String targetTransition) {
        setCurrentAndInitialTimeTo(now.withHour(21).withMinute(30));
        defaultInterpolationTransitionTimeInMs = "4";
        enableSceneSync();
        requireSceneActivation();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 4, 5);
        mockSceneLightStates(1, "Evening",
                ScheduledLightState.builder().id("/lights/4").bri(100),
                ScheduledLightState.builder().id("/lights/5").bri(120));
        var target = mockSceneLightStates(1, "Night",
                ScheduledLightState.builder().id("/lights/4").bri(200),
                ScheduledLightState.builder().id("/lights/5").bri(220));
        addState("g1", "21:10", "scene:Evening", "interpolate:true", "tr:2s");
        addState("g1", "22:00", "scene:Night", "interpolate:true", "tr:" + targetTransition);

        startScheduler();
        var active = ensureScheduledStates(2)
                .stream()
                .filter(runnable -> runnable.getStart().equals(now))
                .findFirst()
                .orElseThrow();
        active.run();

        // The synced scene actually stores the previous definition's 2s transition.
        assertSceneUpdate("/groups/1",
                expectedPutCall(4).bri(140).transitionTime(tr("2s")),
                expectedPutCall(5).bri(160).transitionTime(tr("2s")));
        ensureScheduledStates(2); // next sync and next day's schedule
        verifyNoInteractions(sleepMillis);

        simulateSyncedSceneActivated("/groups/1", "/lights/4", "/lights/5");
        ensureScheduledStates(expectedPowerOnEnd(initialNow.withHour(22).withMinute(0))).getFirst().run();

        // No intermediate put: the scene has already applied it. Wait before sending the long transition.
        var order = inOrder(sleepMillis, mockedHueApi);
        order.verify(sleepMillis).accept(2000L);
        order.verify(mockedHueApi).putSceneState(eq("/groups/1"), eq(target.id()), anyList());
        verifyNoMoreInteractions(sleepMillis);
        assertScenePutCalls(1, target.id(),
                expectedPutCall(4).bri(200).transitionTime(tr("30min")),
                expectedPutCall(5).bri(220).transitionTime(tr("30min")));
    }

    @ParameterizedTest
    @CsvSource(value = {"2s, 4, 20", "NULL, 4, 4", "0, 4, 0", "NULL, NULL, NULL"}, nullValues = "NULL")
    void interpolation_waitsBetweenInitialAndTargetPut_usingPreviousTransitionOrDefault(
            String previousTransition, String defaultTransition, Integer expectedTransition) {
        defaultInterpolationTransitionTimeInMs = defaultTransition;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "tr:" + previousTransition);
        addState(1, now.plusMinutes(10), "bri:200", "interpolate:true", "tr:7s");
        setCurrentTimeTo(now.plusMinutes(5));
        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1))
        );

        var initialPut = expectedPutCall(1).bri(150).transitionTime(expectedTransition).build();
        var targetPut = expectedPutCall(1).bri(200).transitionTime(tr("5min")).build();
        runnables.getFirst().run();

        var order = inOrder(mockedHueApi, sleepMillis);
        order.verify(mockedHueApi).putState(initialPut);
        if (expectedTransition == null) {
            verifyNoInteractions(sleepMillis);
        } else {
            order.verify(sleepMillis).accept(expectedTransition * 100L);
            verifyNoMoreInteractions(sleepMillis);
        }
        order.verify(mockedHueApi).putState(targetPut);
        assertPutCalls(initialPut.toBuilder(), targetPut.toBuilder());
        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void stateWithoutInterpolation_doesNotWaitForItsTransition() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "tr:2s");
        var runnable = startScheduler(expectedRunnable(now, now.plusDays(1))).getFirst();

        runAndAssertPutCalls(runnable, expectedPutCall(1).bri(100).transitionTime(tr("2s")));

        verifyNoInteractions(sleepMillis);
        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));
    }
}
