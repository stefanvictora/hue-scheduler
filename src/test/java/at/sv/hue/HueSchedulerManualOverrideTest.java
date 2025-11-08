package at.sv.hue;

import at.sv.hue.api.LightState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.*;

public class HueSchedulerManualOverrideTest extends AbstractHueSchedulerTest {

    @Test
    void hassEntityId_detectsManualOverrides_brightness() {
        enableUserModificationTracking();
        mockLightCapabilities("light.test", defaultCapabilities);
        addState("light.test", "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState("light.test", "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall("light.test").bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setLightStateResponse("light.test", expectedState().brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void hassEntityId_detectsManualOverrides_ct() {
        enableUserModificationTracking();
        mockLightCapabilities("light.test", defaultCapabilities);
        addState("light.test", "00:00", "ct:" + DEFAULT_CT);
        addState("light.test", "01:00", "ct:" + (DEFAULT_CT + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall("light.test").ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setLightStateResponse("light.test", expectedState().colormode(ColorMode.CT).colorTemperature(DEFAULT_CT + 65)); // overridden
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void multipleStates_userChangedStateManuallyBetweenStates_secondStateIsNotApplied_untilPowerCycle_detectsManualChangesAgainAfterwards() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);
        addState(1, now.plusHours(3), DEFAULT_BRIGHTNESS + 30, DEFAULT_CT);
        addState(1, now.plusHours(4), DEFAULT_BRIGHTNESS + 40, DEFAULT_CT);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(5);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);
        ScheduledRunnable fifthState = scheduledRunnables.get(4);

        // first state is set normally
        advanceTimeAndRunAndAssertPutCalls(firstState,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> update skipped and retry scheduled
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD) // modified
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode(ColorMode.CT));

        advanceTimeAndRunAndAssertPutCalls(secondState); // detects change, sets manually changed flag

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        advanceTimeAndRunAndAssertPutCalls(thirdState); // directly skipped since override was detected

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(3)); // next day

        // simulate power on -> sets enforce flag, rerun third state
        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)),
                expectedPowerOnEnd(initialNow.plusHours(2)),
                expectedPowerOnEnd(initialNow.plusHours(3))
        );

        powerOnEvents.get(0).run(); // temporary, already ended
        powerOnEvents.get(1).run(); // already ended

        // re-run third state after power on -> applies state as state is enforced
        advanceTimeAndRunAndAssertPutCalls(powerOnEvents.get(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT)
        );

        // no modification detected, fourth state set normally
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 20)
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode(ColorMode.CT));
        advanceTimeAndRunAndAssertPutCalls(fourthState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3), initialNow.plusDays(1).plusHours(4)); // fourth state, for next day

        // second modification detected, fifth state skipped again
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 5)
                                                .colorTemperature(DEFAULT_CT)
                                                .colormode(ColorMode.CT));
        setCurrentTimeTo(fifthState);

        fifthState.run(); // detects manual modification again

        ensureRunnable(initialNow.plusDays(1).plusHours(4), initialNow.plusDays(2)); // next day

        verify(mockedHueApi, times(3)).getLightState("/lights/1");
    }

    @Test
    void manualOverride_groupState_allLightsWithSameCapabilities_correctlyComparesState() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 10);
        mockDefaultGroupCapabilities(1);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState("g1", now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20));
        addState("g1", now.plusHours(3), "bri:" + (DEFAULT_BRIGHTNESS + 30));
        addState("g1", now.plusHours(4), "bri:" + (DEFAULT_BRIGHTNESS + 40));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(5);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);
        ScheduledRunnable fifthState = scheduledRunnables.get(4);

        // first state is set normally
        advanceTimeAndRunAndAssertGroupPutCalls(firstState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified group state between first and second state -> update skipped and retry scheduled
        LightState.LightStateBuilder userModifiedLightState = expectedState().id("/lights/9")
                                                                             .brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD)
                                                                             .colormode(ColorMode.CT);
        LightState.LightStateBuilder sameAsFirst = expectedState().id("/lights/10")
                                                                  .brightness(DEFAULT_BRIGHTNESS)
                                                                  .colormode(ColorMode.CT);
        setGroupStateResponses(1, sameAsFirst, userModifiedLightState);
        setCurrentTimeTo(secondState);

        secondState.run(); // detects change, sets manually changed flag

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        setCurrentTimeTo(thirdState);

        thirdState.run(); // directly skipped

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(3)); // next day

        // simulate power on -> sets enforce flag, rerun third state
        simulateLightOnEvent("/groups/1");

        List<ScheduledRunnable> powerOnEvents = ensureScheduledStates(3);

        powerOnEvents.get(0).run(); // temporary, already ended
        powerOnEvents.get(1).run(); // already ended

        // re-run third state after power on -> applies state as state is enforced
        advanceTimeAndRunAndAssertGroupPutCalls(powerOnEvents.get(2),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 20)
        );

        // no modification detected (same as third), fourth state set normally
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS + 20).colormode(ColorMode.CT),
                expectedState().id("/lights/10").brightness(DEFAULT_BRIGHTNESS + 20).colormode(ColorMode.CT)
        );

        advanceTimeAndRunAndAssertGroupPutCalls(fourthState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 30)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3), initialNow.plusDays(1).plusHours(4)); // fourth state, for next day

        // second modification detected, fifth state skipped again
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS + 5).colormode(ColorMode.CT),
                expectedState().id("/lights/10").brightness(DEFAULT_BRIGHTNESS + 5).colormode(ColorMode.CT)
        );
        setCurrentTimeTo(fifthState);

        fifthState.run(); // detects manual modification again

        ensureRunnable(initialNow.plusDays(1).plusHours(4), initialNow.plusDays(2)); // next day

        verify(mockedHueApi, times(3)).getGroupStates("/groups/1");
    }

    @Test
    void manualOverride_groupState_someLightsNotReachable_ignoredInComparison() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 10);
        mockDefaultGroupCapabilities(1);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        // first state is set normally
        advanceTimeAndRunAndAssertGroupPutCalls(firstState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        // user modified group state between first and second state -> not relevant, since light is considered unreachable; even if the light state reports "on:true"
        LightState.LightStateBuilder userModifiedLightState = expectedState().id("/lights/9")
                                                                             .unavailable(true)
                                                                             .brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD)
                                                                             .colormode(ColorMode.CT);
        LightState.LightStateBuilder sameAsFirst = expectedState().id("/lights/10")
                                                                  .brightness(DEFAULT_BRIGHTNESS)
                                                                  .colormode(ColorMode.CT);
        setGroupStateResponses(1, sameAsFirst, userModifiedLightState);

        advanceTimeAndRunAndAssertGroupPutCalls(secondState,
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no change detected
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void manualOverride_group_overlappingStates_overriddenBySchedule_notDetectedAsOverridden() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 10, 11);
        mockDefaultGroupCapabilities(1);
        addKnownLightIdsWithDefaultCapabilities(9, 10);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(9, now, "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState(10, now, "bri:" + (DEFAULT_BRIGHTNESS - 20));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.get(1),
                expectedPutCall(10).bri(DEFAULT_BRIGHTNESS - 20)
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.get(2),
                expectedPutCall(9).bri(DEFAULT_BRIGHTNESS - 10)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2))
        );

        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - 10), // modified by schedule
                expectedState().id("/lights/10").brightness(DEFAULT_BRIGHTNESS - 20), // modified by schedule
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // unmodified
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no override detected
        );

        // next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingStates_manuallyOverriddenGroupLight_detected() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 11);
        mockDefaultGroupCapabilities(1);
        addKnownLightIdsWithDefaultCapabilities(9);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(9, now, "bri:" + DEFAULT_BRIGHTNESS); // same property

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // individual light: same as group
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1),
                expectedPutCall(9).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS),
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD) // manually modified
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingStates_manuallyOverriddenGroupLight_differentFromSchedule_detected() {
        enableUserModificationTracking();

        mockGroupLightsForId(1, 9, 11);
        mockDefaultGroupCapabilities(1);
        addKnownLightIdsWithDefaultCapabilities(9);
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(9, now, "bri:" + DEFAULT_BRIGHTNESS); // same property

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // individual light: same as group
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1),
                expectedPutCall(9).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)),
                expectedRunnable(initialNow.plusDays(1).plusSeconds(1), initialNow.plusDays(2))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD), // manually modified
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_overriddenBySchedule_notDetectedAsOverridden() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 9, 11);
        mockGroupLightsForId(2, 9, 30);
        mockGroupLightsForId(3, 9, 55);
        mockAssignedGroups(9, Arrays.asList(1, 2, 4, 3)); // group 4 has no schedules
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g2", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g3", now, "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // second group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        // third group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(3).bri(DEFAULT_BRIGHTNESS - 10)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)),
                expectedRunnable(now.plusDays(1), now.plusDays(2)),
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );

        // next group call
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - 10), // overridden by third group schedule
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // no modification
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no manual override detected
        );

        // next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_manuallyOverridden_noMatchingGroup_detectsChanges() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 9, 11);
        mockGroupLightsForId(2, 9, 30);
        mockGroupLightsForId(3, 9, 55);
        mockAssignedGroups(9, Arrays.asList(1, 2, 3));
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g2", now, "bri:" + (DEFAULT_BRIGHTNESS - 20));
        addState("g3", now, "bri:" + (DEFAULT_BRIGHTNESS - 30));
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(0),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // second group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(2).bri(DEFAULT_BRIGHTNESS - 20)
        );

        // third group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(2),
                expectedGroupPutCall(3).bri(DEFAULT_BRIGHTNESS - 30)
        );

        // next day runnables
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)),
                expectedRunnable(now.plusDays(1), now.plusDays(2)),
                expectedRunnable(now.plusDays(1), now.plusDays(2))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD), // manually overridden (matches no other group)
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // no modification
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(3)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_onlyCurrentGroupScheduled_correctlyDetectsOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 9, 11);
        mockAssignedGroups(9, Arrays.asList(1, 2, 3)); // groups 2 and 3 are not scheduled
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10))
        );

        // next group call -> detects override
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD), // manually overridden
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS) // no modification
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // no put call

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_group_overlappingGroups_onlyCurrentGroupScheduled_noChanges_noOverride() {
        enableUserModificationTracking();

        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 9, 11);
        mockAssignedGroups(9, Arrays.asList(1, 2, 3)); // groups 2 and 3 are not scheduled
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // group
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // next day runnable
        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10))
        );

        // next group call
        setGroupStateResponses(1,
                expectedState().id("/lights/9").brightness(DEFAULT_BRIGHTNESS),
                expectedState().id("/lights/11").brightness(DEFAULT_BRIGHTNESS)
        );
        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        // next day
        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2))
        );
    }

    @Test
    void manualOverride_stateIsDirectlyRescheduled() {
        enableUserModificationTracking();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden("/lights/" + ID); // start with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ensureScheduledStates(1);

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void group_manualOverride_notScheduledWhenContainedLightIsTurnedOnViaSoftware() {
        enableUserModificationTracking();
        int groupId = 4;
        int lightId = 66;
        mockGroupLightsForId(groupId, lightId);
        mockDefaultGroupCapabilities(groupId);
        mockDefaultLightCapabilities(lightId);

        addState("g" + groupId, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(lightId, now, "ct:" + DEFAULT_CT);
        manualOverrideTracker.onManuallyOverridden("/groups/" + groupId);
        manualOverrideTracker.onManuallyOverridden("/lights/" + lightId);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);

        scheduledRunnables.get(0).run();
        ensureScheduledStates(1); // next day
        scheduledRunnables.get(1).run();
        ensureScheduledStates(1); // next day

        simulateLightOnEvent("/lights/" + lightId); // non-physical light on event

        ensureScheduledStates(1); // only light is rescheduled, assigned group is ignored
    }

    @Test
    void twoGroups_manualOverride_bothRescheduledWhenContainedLightIsTurnedOnPhysically() {
        enableUserModificationTracking();
        mockDefaultLightCapabilities(1);
        mockDefaultGroupCapabilities(5);
        mockDefaultGroupCapabilities(6);
        mockGroupLightsForId(5, 1);
        mockGroupLightsForId(6, 1);
        mockAssignedGroups(1, 5, 6); // light is part of two groups
        when(mockedHueApi.getAffectedIdsByDevice("/device/10")).thenReturn(List.of("/lights/1", "/groups/5", "/groups/6"));

        addState(1, now, "ct:" + DEFAULT_CT);
        addState("g5", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g6", now, "bri:" + DEFAULT_BRIGHTNESS);
        manualOverrideTracker.onManuallyOverridden("/lights/1");
        manualOverrideTracker.onManuallyOverridden("/groups/5");
        manualOverrideTracker.onManuallyOverridden("/groups/6");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);

        scheduledRunnables.get(0).run();
        ensureScheduledStates(1); // next day
        scheduledRunnables.get(1).run();
        ensureScheduledStates(1); // next day
        scheduledRunnables.get(2).run();
        ensureScheduledStates(1); // next day

        scheduler.getHueEventListener().onPhysicalOn("/device/10");

        ensureScheduledStates(3); // individual light, as well as contained groups are rescheduled
    }

    @Test
    void manualOverride_resetAfterPowerOn() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);

        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first state -> current light state ignored

        setLightStateResponse(1, expectedState().brightness(50)); // ignored for first run
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();


        // second state -> detects manual override

        setLightStateResponse(1, expectedState().brightness(50));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // detects manual override

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        // power on of second state -> resets overridden flag again

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(110)
        );

        // next day -> applied normally again

        setLightStateResponse(1, expectedState().brightness(110));
        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)) // next day
        );
    }

    @Test
    void manualOverride_resetAfterPhysicalPowerOn() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        when(mockedHueApi.getAffectedIdsByDevice("/device/354")).thenReturn(List.of("/lights/1"));

        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        // first state -> current light state ignored

        setLightStateResponse(1, expectedState().brightness(20)); // ignored for first run
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100)
        );

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();


        // second state -> detects manual override

        setLightStateResponse(1, expectedState().brightness(20));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // detects manual override

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        // physical power on of second state -> resets overridden flag again

        scheduler.getHueEventListener().onPhysicalOn("/device/354");

        List<ScheduledRunnable> powerOnRunnables = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(110)
        );

        // next day -> applied normally again

        setLightStateResponse(1, expectedState().brightness(110));
        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).bri(100)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)) // next day
        );
    }

    @Test
    void manualOverride_stateIsDirectlyRescheduledOnRun() {
        enableUserModificationTracking();

        addDefaultState();
        manualOverrideTracker.onManuallyOverridden("/lights/" + ID); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeToAndRun(scheduledRunnable);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void manualOverride_forGroup_stateIsDirectlyRescheduledOnRun() {
        enableUserModificationTracking();

        addDefaultGroupState(2, now, 1);
        manualOverrideTracker.onManuallyOverridden("/groups/2"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeToAndRun(scheduledRunnable);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        simulateLightOnEvent("/groups/2");
        ensureScheduledStates(1);
    }

    @Test
    void manualOverride_multipleStates_powerOnAfterNextDayStart_beforeNextState_alreadyRescheduledBefore() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.getFirst();

        firstState.run(); // directly reschedule

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setCurrentTimeTo(initialNow.plusDays(1).plusMinutes(30)); // after next day start of state, but before next day start of second state

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run(); // already ended
    }

    @Test
    void manualOverride_multipleStates_powerOnAfterNextDayStart_afterNextState_alreadyRescheduledBefore() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS + 10, DEFAULT_CT);
        addState(1, now.plusHours(2), DEFAULT_BRIGHTNESS + 20, DEFAULT_CT);

        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstState = scheduledRunnables.getFirst();

        firstState.run(); // directly reschedule

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1).plusMinutes(30)); // after start of next day for first state, but after start of next state

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run(); // already ended
    }

    @Test
    void manualOverride_forDynamicSunTimes_turnedOnEventOnlyNextDay_correctlyReschedulesStateOnSameDay() {
        enableUserModificationTracking();
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2));
        setCurrentAndInitialTimeTo(sunrise);

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "sunrise+60", "bri:" + (DEFAULT_BRIGHTNESS + 10));
        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        setCurrentTimeToAndRun(firstState);

        ensureRunnable(nextDaySunrise, nextDaySunrise.plusHours(1)); // next day

        setCurrentTimeToAndRun(secondState);

        ensureRunnable(nextDaySunrise.plusHours(1), nextNextDaySunrise); // next day

        setCurrentTimeTo(initialNow.plusDays(1).minusHours(1)); // next day, one hour before next schedule
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(sunrise.plusHours(1)), // already ended
                expectedPowerOnEnd(nextDaySunrise)
        );

        powerOnRunnables.getFirst().run(); // already ended
    }

    @Test
    void manualOverride_multipleStates_detectsChangesIfMadeDuringCrossOverState() {
        enableUserModificationTracking();

        addState(1, now.plusHours(1), DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + DEFAULT_CT, "force:false"); // force:false = default

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2))

        );
        ScheduledRunnable crossOverState = scheduledRunnables.get(0);
        ScheduledRunnable firstState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT) // runs through normally
        );

        ScheduledRunnable secondState = ensureRunnable(now.plusHours(2), now.plusDays(1).plusHours(1));

        // user modified light state before first state -> update skipped and retry scheduled
        setLightStateResponse(1, expectedState()
                .brightness(DEFAULT_BRIGHTNESS + 20 + BRIGHTNESS_OVERRIDE_THRESHOLD)
                .colorTemperature(DEFAULT_CT)
                .colormode(ColorMode.CT));

        advanceTimeAndRunAndAssertPutCalls(firstState);  // no update, as modification was detected

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2)); // next day

        advanceTimeAndRunAndAssertPutCalls(secondState);  // no update, as modification was detected

        ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(2).plusHours(1)); // next day
    }

    @Test
    void manualOverride_forceProperty_true_ignored() {
        enableUserModificationTracking();

        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10), "force:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(firstState, defaultPutCall());
        ensureRunnable(initialNow.plusDays(1)); // for next day

        // user modified light state between first and second state -> ignored since force:true is set
        setLightStateResponse(1, expectedState()
                .brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD)
                .colorTemperature(DEFAULT_CT)
                .colormode(ColorMode.CT));

        advanceTimeAndRunAndAssertPutCalls(secondState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // enforced despite user changes
        );

        ensureRunnable(initialNow.plusHours(1).plusDays(1), initialNow.plusDays(2)); // for next day
    }

    @Test
    void manualOverride_lightConsideredOffAtSecondPutCall_doesNotDetectChangesForThirdState() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));
        addState(1, now.plusHours(2), "bri:" + (DEFAULT_BRIGHTNESS + 20));
        addState(1, now.plusHours(3), "bri:" + (DEFAULT_BRIGHTNESS + 30));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusHours(3)),
                expectedRunnable(now.plusHours(3), now.plusDays(1))
        );
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);
        ScheduledRunnable thirdState = scheduledRunnables.get(2);
        ScheduledRunnable fourthState = scheduledRunnables.get(3);

        advanceTimeAndRunAndAssertPutCalls(firstState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1)); // for next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS)); // same as first

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(secondState); // no put calls as off

        ensureRunnable(initialNow.plusDays(1).plusHours(1)); // for next day

        mockIsLightOff(1, false);

        advanceTimeAndRunAndAssertPutCalls(thirdState); // still no put call expected, as light has been set to off

        ensureRunnable(initialNow.plusDays(1).plusHours(2)); // for next day

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(2)), // already ended
                expectedPowerOnEnd(initialNow.plusHours(3))
        );
        // light state is still like first state -> not recognized as override as last seen state has not been updated through second state

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20)
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 20)); // same as third

        advanceTimeAndRunAndAssertPutCalls(fourthState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3)); // for next day
    }

    @Test
    void manualOverride_firstCallFailed_powerOn_lastSeenCorrectlySet_usedForModificationTracking_detectedCorrectly() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(1), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        mockIsLightOff(1, true);
        // not marked as seen
        advanceTimeAndRunAndAssertPutCalls(firstState);

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1)); // next day

        mockIsLightOff(1, false);

        // Power on -> reruns first state

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now.plusHours(1));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        // Second state -> detects manual override

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden

        advanceTimeAndRunAndAssertPutCalls(secondState); // detected as overridden -> no put calls

        ensureRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void manualOverride_transitionTimeBefore_longDuration_detectsChangesCorrectly() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(6), "bri:" + (DEFAULT_BRIGHTNESS + 50), "tr-before:6h");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 14).transitionTime(MAX_TRANSITION_TIME) // first split call
        );

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpStates.getFirst();

        // second split -> no override detected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 14)); // same as first

        advanceTimeAndRunAndAssertPutCalls(secondSplit,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 28).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable thirdSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // third split -> still no override detected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 28)); // same as second

        advanceTimeAndRunAndAssertPutCalls(thirdSplit,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 42).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable fourthSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // fourth split -> detects override

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 42 + BRIGHTNESS_OVERRIDE_THRESHOLD)); // overridden

        advanceTimeAndRunAndAssertPutCalls(fourthSplit); // detects override

        // simulate light on -> re run fourth (and last) split

        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS * 3, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1)) // fourth split again
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnEvents.get(3),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 42), // interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 50).transitionTime(36000) // last split part
        );
    }

    @Test
    void offState_manualOverride_offStateIsNotRescheduledWhenOn_skippedAllTogether() {
        enableUserModificationTracking();

        addOffState();
        manualOverrideTracker.onManuallyOverridden("/lights/1"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(nextDayRunnable);
        advanceCurrentTime(Duration.ofMinutes(10)); // some delay

        nextDayRunnable.run();

        ensureRunnable(initialNow.plusDays(2));

        // no power-on events have been scheduled

        simulateLightOnEvent();
    }

    @Test
    void offState_forceProperty_reschedulesOffState_alsoIfAlreadyOverridden() {
        enableUserModificationTracking();

        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "force:true");
        manualOverrideTracker.onManuallyOverridden("/lights/1"); // start directly with overridden state

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable); // overridden -> reschedules on power on

        ScheduledRunnable nextDay = ensureRunnable(now.plusDays(1)); // next day

        // first power on after overridden -> turned off again

        ScheduledRunnable powerOnRunnable1 = simulateLightOnEventExpectingSingleScheduledState(now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable1,
                expectedPutCall(1).on(false)
        );

        // next day -> runs through normally

        advanceTimeAndRunAndAssertPutCalls(nextDay,
                expectedPutCall(1).on(false)
        );

        ensureRunnable(now.plusDays(1)); // next day

        // second power on after normal run through -> also rescheduled for power on

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now), // already ended
                expectedPowerOnEnd(now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).on(false)
        );
    }
}
