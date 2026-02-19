package at.sv.hue;

import at.sv.hue.api.ApiFailure;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;

public class HueSchedulerOffUpdateTest extends AbstractHueSchedulerTest {

    @Test
    void lightIsOff_doesNotUpdateLights_unlessStateHasOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10), "force:true"); // force does not have any effect
        addState(1, "02:00", "on:true", "bri:" + (DEFAULT_BRIGHTNESS + 20));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);

        simulateLightOffEvent("/lights/1");
        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // second state is skipped

        advanceTimeAndRunAndAssertPutCalls(secondState); // still no put calls

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        // third state has "on:true" property -> is run normally again
        advanceTimeAndRunAndAssertPutCalls(thirdState,
                expectedPutCall(1).on(true).bri(DEFAULT_BRIGHTNESS + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)),
                expectedPowerOnEnd(initialNow.plusHours(2)),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // already ended

        verify(mockedHueApi, never()).isGroupOff("/groups/1");
    }

    @Test
    void lightIsOff_apiDoesNotAllowOffUpdates_notApplied() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        ensureScheduledStates(); // does not schedule anything on light off, if API does not off updates

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // light is off, no update

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_stillAppliesUpdates() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        // light is off, still applied

        simulateLightOffEvent("/lights/1");
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(60)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_manualOverrides_stillAppliesUpdates() {
        enableSupportForOffLightUpdates();
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:60");
        addState(1, "02:00", "bri:70");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // detects manual override, still on, not applied

        setLightStateResponse(1, expectedState().brightness(50 - BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        // light turned off -> automatically re-applies state

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst());
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.get(1),
                expectedPutCall(1).bri(60)
        );

        // next state applied normally again

        setLightStateResponse(1, expectedState().on(false).brightness(60));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(70)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2)); // next day
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_lightHasOn_removesOnWhenLightIsTurnedOff() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "on:true");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst(),
                expectedPutCall(1).bri(50) // removes "on:true"
        );
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_usesFullPicture() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "ct:300");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).ct(300) // uses full picture
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst(),
                expectedPutCall(1).bri(50).ct(300) // uses full picture
        );
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_removesTransitionTime() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "tr:5s");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        simulateLightOffEvent("/lights/1");
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50) // removes transition time
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_lightHasOn_doesNotRemoveTransitionEvenIfCurrentlyOff() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "on:true", "tr:5s");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        simulateLightOffEvent("/lights/1");
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true).transitionTime(tr("5s")) // keeps transition time
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_lightHasOn_andForce_forcesLightToBeOn() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "on:true", "force:true");
        addState(1, "01:00", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.getFirst(),
                expectedPutCall(1).bri(50).on(true) // keeps "on:true" because of force:true
        );
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_interpolation_stillAppliesUpdates() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is off for interpolated state: only applies interpolated call

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(50)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // first background interpolation

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).bri(58)
        );

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(initialNow.plusMinutes(40), initialNow.plusHours(2));

        // second background interpolation

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(67)
        );

        ScheduledRunnable nextBackgroundInterpolation2 = ensureRunnable(initialNow.plusMinutes(45), initialNow.plusHours(2));

        // third background interpolation

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation2,
                expectedPutCall(1).bri(75)
        );

        ensureRunnable(initialNow.plusMinutes(50), initialNow.plusHours(2));

        // simulate light turn off after time passing

        setCurrentTimeTo(initialNow.plusMinutes(55));

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.get(1),
                expectedPutCall(1).bri(92)
        );

        // next state applied normally again

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(150)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2)); // next day
    }

    @Test
    void apiAllowsOffUpdates_interpolation_lightIsOn_doesNotPerformBackgroundInterpolation() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is on: normal interpolation

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("30min"))
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // first background interpolation: light is still on -> no update

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst());

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(initialNow.plusMinutes(40), initialNow.plusHours(2));

        // second background interpolation: light is off -> updates

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(67)
        );

        ScheduledRunnable nextBackgroundInterpolation2 = ensureRunnable(initialNow.plusMinutes(45), initialNow.plusHours(2));

        setCurrentTimeTo(scheduledRunnables.get(2));

        runAndAssertPutCalls(nextBackgroundInterpolation2); // already ended
    }

    @Test
    void apiAllowsOffUpdates_interpolation_lightHasOn_stillSchedulesBackgroundInterpolation_onlyAppliedWhenOff() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "on:true", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is on: normal interpolation

        mockIsLightOff(1, true); // even if off, on:true forces it to be treated as on
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(50).on(true),
                expectedPutCall(1).bri(100).on(true).transitionTime(tr("30min"))
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // simulate light turned on: applies state again (without on:true this time)

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.getFirst());
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(50).on(true),
                expectedPutCall(1).bri(100).on(true).transitionTime(tr("30min"))
        );

        // background interpolation: light is still on -> no update

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst());

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(initialNow.plusMinutes(40), initialNow.plusHours(2));

        // background interpolation: light is off -> updates

        mockIsLightOff(1, true);

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(67)
        );

        ensureRunnable(initialNow.plusMinutes(45), initialNow.plusHours(2)); // background interpolation 2
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_interpolation_stillAppliesOnOffAfterSyncedSceneActivation() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is on: normal interpolation

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("30min"))
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(35), initialNow.plusHours(2)), // first change for background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)) // next day
        );

        // advance to already reached, then simulate synced scene activation

        advanceCurrentTime(Duration.ofMinutes(30));

        simulateSyncedSceneActivated("/groups/another", "/lights/1");

        List<ScheduledRunnable> powerOnRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // synced scene and already reached, skipped

        // turn off light still inside synced scene ignore window

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds).minusSeconds(1));

        simulateLightOffEvent("/lights/1");

        ScheduledRunnable powerOffRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(2))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOffRunnable,
                expectedPutCall(1).bri(100) // still applied
        );
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_interpolation_apiFailure_retries_backgroundInterpolationsStopOnceReached() {
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:5min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(55)),
                expectedRunnable(now.plusMinutes(55), now.plusDays(1))
        );

        // Normal light state, applied as usual

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(55)); // next day

        // Interpolated state: off

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(50)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plusMinutes(56), initialNow.plusDays(1)), // next background interpolation
                expectedRunnable(initialNow.plusDays(1).plusMinutes(55), initialNow.plusDays(2)) // next day
        );

        // first background interpolation: api failure -> retried
        doThrow(ApiFailure.class).when(mockedHueApi).putState(any());
        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).bri(60)
        );

        ScheduledRunnable retry = ensureRunnable(now.plusMinutes(syncFailureRetryInMinutes), initialNow.plusDays(1));

        resetMockedApi();
        mockIsLightOff(1, true);

        advanceTimeAndRunAndAssertPutCalls(retry,
                expectedPutCall(1).bri(90)
        );

        ScheduledRunnable nextBackgroundInterpolation1 = ensureRunnable(now.plusMinutes(1), initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(nextBackgroundInterpolation1,
                expectedPutCall(1).bri(100)
        );

        // no further background interpolations scheduled
    }

    @Test
    void lightIsOff_apiAllowsOffUpdates_interpolation_requireSceneActivation_notApplied() {
        requireSceneActivation();
        enableSupportForOffLightUpdates();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50");
        addState(1, "01:00", "bri:100", "tr-before:30min");
        addState(1, "02:00", "bri:150");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );

        // Normal light state, no scene activation, ignored

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst());

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(30)); // next day

        // Light is off for interpolated state, no scene activation still ignored

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        // no background interpolation scheduled
        ensureRunnable(initialNow.plusDays(1).plusMinutes(30), initialNow.plusDays(1).plusHours(2)); // next day

        // simulate light turn off after time passing

        setCurrentTimeTo(initialNow.plusMinutes(55));

        simulateLightOffEvent("/lights/1");
        List<ScheduledRunnable> powerOffRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(30)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOffRunnables.get(1)); // no scene activation, ignored

        mockIsLightOff(1, false);
        simulateSyncedSceneActivated("/groups/another", "/lights/1");

        ScheduledRunnable sceneRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusHours(2))
        ).getFirst();

        // turn on synced scene -> applied normally

        advanceTimeAndRunAndAssertPutCalls(sceneRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("5min"))
        );

        // next state applied normally again (turned on by synced scene flag still activate)

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(150)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2)); // next day
    }

    @Test
    void groupIsOff_doesNotUpdateLights_unlessStateHasOnProperty() {
        mockGroupLightsForId(1, 1, 2);
        mockDefaultGroupCapabilities(1);
        addState("g1", "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState("g1", "02:00", "on:true", "bri:" + (DEFAULT_BRIGHTNESS + 20));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);

        mockIsGroupOff(1, true);
        advanceTimeAndRunAndAssertGroupPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // second state is skipped

        advanceTimeAndRunAndAssertGroupPutCalls(secondState); // still no put calls

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        // third state has "on:true" property -> is run normally again
        advanceTimeAndRunAndAssertGroupPutCalls(thirdState,
                expectedGroupPutCall(1).on(true).bri(DEFAULT_BRIGHTNESS + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusHours(1)),
                expectedPowerOnEnd(initialNow.plusHours(2)),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnables.get(1)); // already ended

        verify(mockedHueApi, never()).isLightOff("/lights/1");
    }

    @Test
    void groupIsOff_apiAllowsOffUpdates_controlsLightsIndividually() {
        enableSupportForOffLightUpdates();
        mockGroupLightsForId(1, 1, 2);
        mockDefaultGroupCapabilities(1);
        addState("g1", "00:00", "bri:50", "tr:2s");
        addState("g1", "01:00", "bri:60", "tr:5s");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(50).transitionTime(tr("2s"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        mockIsGroupOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),  // off lights updated individually, no transition time
                expectedPutCall(1).bri(60),
                expectedPutCall(2).bri(60)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void lightIsOff_doesNotMakeAnyCalls_ignoresOffCheckOnPowerOn() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        simulateLightOffEvent("/lights/2");
        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // power on -> ignores off state

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        // second state, detects off again
        simulateLightOffEvent("/lights/2");

        advanceTimeAndRunAndAssertPutCalls(secondState);

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void lightIsOff_detectedViaSingleOffEvent_doesNotMakeAnyCalls_restAfterOnEvent() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        simulateLightOffEvent("/lights/2");

        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // power on -> ignores off state

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusHours(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        // second state, still treated as on

        advanceTimeAndRunAndAssertPutCalls(secondState,
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void lightIsOff_doesNotApplyNextState_evenIfManualTurnOnAndOffHappensInBetween() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, "00:00", "on:false");
        addState(2, "01:00", "bri:" + DEFAULT_BRIGHTNESS);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(firstState,
                expectedPutCall(2).on(false)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // power on -> does not repeat on:false, since no force:true is present

        simulateLightOnEvent("/lights/2");
        ensureScheduledStates(0); // no power-on states scheduled

        // manually turn-off lights again

        simulateLightOffEvent("/lights/2");

        // second state, still treated as off

        advanceTimeAndRunAndAssertPutCalls(secondState);

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }
}
