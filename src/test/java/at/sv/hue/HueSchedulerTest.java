package at.sv.hue;

import at.sv.hue.api.AffectedId;
import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.time.InvalidStartTimeExpression;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
class HueSchedulerTest extends AbstractHueSchedulerTest {

    @Test
    void run_groupState_looksUpContainingLights_addsState() {
        addDefaultGroupState(9, now, 1, 2, 3);

        startAndGetSingleRunnable(now);
    }

    @Test
    void run_groupState_andLightState_sameId_treatedDifferently_endIsCalculatedIndependently() {
        addDefaultGroupState(1, now, 1);
        addDefaultState(1, now);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1))
        );

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(), defaultGroupPutCall());

        ensureNextDayRunnable();
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_becauseOfDayWrapAround() {
        addDefaultState(1, now.plusHours(1));

        ScheduledRunnable runnable = startAndGetSingleRunnable(now, now.plusHours(1));

        advanceTimeAndRunAndAssertPutCalls(runnable, defaultPutCall());

        ensureRunnable(now.plusHours(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_theNextWithCorrectDelay() {
        addDefaultState(1, now.plusHours(1));
        addDefaultState(1, now.plusHours(2));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(), defaultPutCall());

        ensureRunnable(now.plusHours(2), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        setCurrentTimeTo(now.plusHours(2));
        addDefaultState(11, now.minusHours(1));

        startAndGetSingleRunnable(now, initialNow.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_sameId_differentTimes_correctlyScheduled() {
        addDefaultState(22, now);
        addDefaultState(22, now.plusHours(1));
        addDefaultState(22, now.plusHours(2));

        startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusHours(2)),
                expectedRunnable(now.plusHours(2), now.plusDays(1))
        );
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        setCurrentTimeTo(now.plusHours(3)); // 03:00
        addDefaultState(13, initialNow.plusHours(4));  // 04:00 -> scheduled in one hour
        addDefaultState(13, initialNow.plusHours(2)); // 02:00 -> scheduled immediately
        addDefaultState(13, initialNow.plusHours(1)); // 01:00 -> scheduled next day

        startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), initialNow.plusDays(1).plusHours(1)),
                expectedRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(1).plusHours(2))
        );
    }

    @Test
    void parse_unknownLightId_exception() {
        when(mockedHueApi.getLightIdentifier("/lights/1")).thenThrow(new LightNotFoundException("Light not found"));

        assertThrows(LightNotFoundException.class, () -> addStateNow("1"));
    }

    @Test
    void parse_unknownGroupId_exception() {
        when(mockedHueApi.getGroupIdentifier("/groups/1")).thenThrow(new GroupNotFoundException("Group not found"));

        assertThrows(GroupNotFoundException.class, () -> addStateNow("g1"));
    }

    @Test
    void parse_group_brightness_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, NO_CAPABILITIES);

        assertThrows(BrightnessNotSupported.class, () -> addStateNow("g7", "bri:254"));
    }

    @Test
    void parse_group_colorTemperature_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, NO_CAPABILITIES);

        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("g7", "ct:500"));
    }

    @Test
    void parse_group_color_missingCapabilities_exception() {
        mockGroupLightsForId(7, 2);
        mockGroupCapabilities(7, NO_CAPABILITIES);

        assertThrows(ColorNotSupported.class, () -> addStateNow("g7", "x:1", "y:1"));
    }

    @Test
    void parse_parsesInputLine_createsMultipleStates_canHandleGroups() {
        int groupId = 9;
        mockGroupLightsForId(groupId, 77);
        addKnownLightIdsWithDefaultCapabilities(1, 2);
        mockDefaultGroupCapabilities(groupId);
        addStateNow("1, 2,g" + groupId, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now, now.plusDays(1))
        );

        runAndAssertNextDay(scheduledRunnables.get(1));
    }

    @Test
    void parse_alsoSupportsFourSpacesInsteadOfTabs() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1    " + nowTimeString + "    bri:" + DEFAULT_BRIGHTNESS + "    ct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_multipleTabs_parsedCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1\t12:00\t\tbri:75%\t\tct:3400");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_missingPart_throws() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThrows(InvalidConfigurationLine.class, () -> addState("1  12:00  bri"));
    }

    @Test
    void parse_trimsProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1\t" + nowTimeString + "\t bri:" + DEFAULT_BRIGHTNESS + "  \tct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_x_canHandleSpaces() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "x: 0.1234 ", "y:0.5678 ");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_missingParts_atLeastIdAndTimeNeedsToBeSet() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidConfigurationLine.class, () -> addState("1\t"));
    }

    @Test
    void parse_unknownFlag_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(UnknownStateProperty.class, () -> addStateNow("1", "UNKNOWN:1"));
    }

    @Test
    void parse_missingTime_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidStartTimeExpression.class, () -> {
            addState("1\tct:" + DEFAULT_CT);
            startScheduler();
        });
    }

    @Test
    void parse_setsTransitionTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:" + 5);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).ct(null).transitionTime(5)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_hassEntityId_light_correctlyParsed() {
        mockLightCapabilities("light.test", defaultCapabilities);
        addStateNow("light.test", "bri:" + DEFAULT_BRIGHTNESS);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall("light.test").bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1));
        verify(mockedHueApi).getLightIdentifier("light.test");
    }

    @Test
    void parse_hassEntityId_detectsPowerOffAndOn() {
        mockLightCapabilities("light.test", defaultCapabilities);
        addStateNow("light.test", "bri:" + DEFAULT_BRIGHTNESS);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        mockIsLightOff("light.test", true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable); // no put calls, as light off

        ensureRunnable(initialNow.plusDays(1)); // next day

        mockIsLightOff("light.test", false);

        // simulate power on

        List<ScheduledRunnable> powerOnRunnable = simulateLightOnEvent("light.test",
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable.getFirst(),
                expectedPutCall("light.test").bri(DEFAULT_BRIGHTNESS)
        );
    }



    @Test
    void parse_canParseTransitionTime_withTimeUnits() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:5s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(50)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_minutes() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:100min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(60000)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_hours() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:1h");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(36000)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTime_withTimeUnits_hoursAndMinutesAndSeconds() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + DEFAULT_BRIGHTNESS, "tr:1H20min5s10");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(DEFAULT_BRIGHTNESS).transitionTime(48060)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canParseTransitionTimeBefore_withSunTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:10");
        addState("1", "civil_dusk", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:sunset+10"); // tr-before:16:24:29
        ZonedDateTime sunset = startTimeProvider.getStart("sunset", now); // 16:14:29
        ZonedDateTime civilDusk = startTimeProvider.getStart("civil_dusk", now); // 16:47:46
        ZonedDateTime nextDaySunset = startTimeProvider.getStart("sunset", now.plusDays(1));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, sunset.plusMinutes(10)),
                expectedRunnable(sunset.plusMinutes(10), now.plusDays(1))
        );

        int expectedTransitionTime = (int) (Duration.between(sunset.plusMinutes(10), civilDusk).toMillis() / 100L);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(10), // interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(expectedTransitionTime)
        );

        ensureRunnable(nextDaySunset.plusMinutes(10), initialNow.plusDays(2));
    }

    @Test
    void parse_canParseTransitionTimeBefore_withAbsoluteTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "01:00", "bri:1");
        addState("1", "12:00", "bri:254", "tr-before:01:00");

        startScheduler(
                expectedRunnable(now, now.plusHours(1)), //from previous day
                expectedRunnable(now.plusHours(1), now.plusHours(1)) // zero length state
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_withAbsoluteTime_simple() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1"); // 00:00-10:00
        addState("1", "12:00", "bri:254", "tr-before:10:00"); // 10:00-12:00

        startScheduler(
                expectedRunnable(now, now.plusHours(10)),
                expectedRunnable(now.plusHours(10), now.plusDays(1))
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_withAbsoluteTime_afterStartTime_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1");
        addState("1", "12:00", "bri:254", "tr-before:13:00"); // ignores tr-before

        startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );
    }


    @Test
    void parse_canParseInterpolateProperty_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1", "interpolate:true");
        addState("1", "12:00", "bri:254", "interpolate:true");
        addState("1", "14:00", "bri:100", "interpolate:true");
        addState("1", "15:00", "bri:50", "interpolate:true");
        addState("1", "20:00", "bri:5", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)), // second state
                expectedRunnable(now.plusHours(12), now.plusHours(14)), // third state
                expectedRunnable(now.plusHours(14), now.plusHours(15)), // fourth state
                expectedRunnable(now.plusHours(15), now.plusHours(20)), // fifth state
                expectedRunnable(now.plusHours(20), now.plusDays(1)) // first state
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(100), // interpolated call
                expectedPutCall(1).bri(50).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void parse_canParseInterpolateProperty_correctErrorHandling() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThrows(InvalidPropertyValue.class, () -> addState("1", "00:00", "bri:1", "interpolate:true tr:5"));
    }

    @Test
    void parse_interpolateProperty_andTrBefore_prefersTrBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1");
        addState("1", "12:00", "bri:254", "interpolate:true", "tr-before:1h"); // ignores interpolate:true

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(11)),
                expectedRunnable(now.plusHours(11), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(1), // interpolated call
                expectedPutCall(1).bri(254).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_defaultInterpolateSet_interpolatesAllStatesUnlessExplicitlyDisabled() {
        interpolateAll = true;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1"); // 12:00 (previous day) - 00:00
        addState("1", "01:00", "bri:254"); // 00:00-12:00
        addState("1", "12:00", "bri:100", "interpolate:false"); // zero length state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)), // second state
                expectedRunnable(now.plusHours(12), now.plusDays(1)), // cross over state
                expectedRunnable(now.plusHours(12), now.plusHours(12)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(1), // interpolated call
                expectedPutCall(1).bri(254).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusHours(12)); // next day
    }

    @Test
    void parse_canParseInterpolateProperty_singleState_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1", "00:00", "bri:1", "interpolate:true");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_canParseInterpolateProperty_withSunTimes_correctlyUsesSunTimesFromDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime noon = startTimeProvider.getStart("noon", now);
        ZonedDateTime sunset = startTimeProvider.getStart("sunset", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        addState("1", "sunrise", "bri:1", "interpolate:true");
        addState("1", "noon", "bri:254", "interpolate:true");
        addState("1", "sunset", "bri:100", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, sunrise), // from previous day
                expectedRunnable(sunrise, noon),
                expectedRunnable(noon, sunset)
        );
        ScheduledRunnable previousDaySunriseState = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(previousDaySunriseState,
                expectedPutCall(1).bri(50), // interpolated call
                expectedPutCall(1).bri(47).transitionTime(tr("33min31s"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plus(1891000 + 120000, ChronoUnit.MILLIS), sunrise), // next split start
                expectedRunnable(sunset, nextDaySunrise)
        );
    }

    // todo: should we allow day crossovers also for absolute times in tr-before (see next test)? We need this for interpolate:true now anyways

    @Test
    void parse_canParseTransitionTimeBefore_negativeDuration_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime goldenHour = startTimeProvider.getStart("golden_hour", now);
        ZonedDateTime nextDayGoldenHour = startTimeProvider.getStart("golden_hour", now.plusDays(1));
        ZonedDateTime nextNextDayGoldenHour = startTimeProvider.getStart("golden_hour", now.plusDays(2));

        addState("1", "golden_hour", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:sunset"); // referenced t-before time is AFTER start

        ScheduledRunnable crossOverState = startAndGetSingleRunnable(now, goldenHour);

        // no transition time as state already reached
        advanceTimeAndRunAndAssertPutCalls(crossOverState, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS));

        ScheduledRunnable trBeforeRunnable = ensureRunnable(goldenHour, nextDayGoldenHour);

        // ignored transition time, as negative
        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable, expectedPutCall(1).bri(DEFAULT_BRIGHTNESS));

        ensureRunnable(nextDayGoldenHour, nextNextDayGoldenHour);
    }

    @Test
    void parse_canParseTransitionTimeBefore_maxDuration_doesNotPerformAdditionalInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10");
        addState(1, now.plusHours(1).plusMinutes(50), "bri:200", "tr-before:" + MAX_TRANSITION_TIME);

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10))
        );
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(10),
                expectedPutCall(1).bri(200).transitionTime(MAX_TRANSITION_TIME));

        ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(10)); // next day
    }

    @Test
    void parse_canParseTransitionTimeBefore_longDuration_noPreviousState_doesNotAdjustStart_asNoInterpolationIsPossible() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now); // null state
        addState(1, now.plusHours(3).plusMinutes(40), "bri:200", "tr-before:210min");

        ScheduledRunnable trRunnable = startAndGetSingleRunnable(now.plusHours(3).plusMinutes(40), now.plusDays(1)); // no tr-before adjustment happened

        advanceTimeAndRunAndAssertPutCalls(trRunnable, expectedPutCall(1).bri(200));

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void parse_canParseTransitionTimeBefore_longDuration_splitsCallIntoMultipleParts_interpolatesPropertiesCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");
        // 210min = 3h30min -> split to: 100min + 100min + 10min
        // Start with 40 brightness, the target is 250.
        // After 100 minutes (first split) we should have 140, after 200 minutes (second split) 240, after the final split we reach 250

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(initialNow.plusMinutes(10), initialNow.plusDays(1)), // tr-runnable
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10))
        );
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();

        // first split

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(initialBrightness), // previous state call as interpolation start
                expectedPutCall(1).bri(initialBrightness + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // scheduled second split of transition
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable followUpRunnable = followUpRunnables.getFirst();

        // run follow-up call for the second split

        advanceTimeAndRunAndAssertPutCalls(followUpRunnable,
                // no interpolation as "initialBrightness + 100" already set at end of first part
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME) // second split of transition
        );

        ScheduledRunnable finalSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled third split of transition

        // simulate power-on, ten minutes later: adjusts calls from above

        advanceCurrentTime(Duration.ofMinutes(5));
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.minusMinutes(5)),
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS).minusMinutes(5))
        );

        powerOnRunnables.get(0).run(); // already ended -> no put calls
        // creates another power-on runnable but with adjusted end -> see "already ended" runnable for second power-on
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(initialBrightness + 105), // adjusted to five minutes after
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME - 3000)
        );

        ensureScheduledStates(0);

        // run final split call

        advanceTimeAndRunAndAssertPutCalls(finalSplit,
                // no interpolation as "initialBrightness + 200" already set at end of second part
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000) // remaining 10 minutes
        );
        // simulate second power on, five minutes later: adjusts calls from above

        List<ScheduledRunnable> finalPowerOns = simulateLightOnEvent(
                expectedPowerOnEnd(now),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );
        advanceCurrentTime(Duration.ofMinutes(5));

        finalPowerOns.get(0).run(); // already ended -> no put calls

        runAndAssertPutCalls(finalPowerOns.get(1),
                expectedPutCall(1).bri(initialBrightness + 205),
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(3000) // remaining five minutes
        );

        // move to defined start -> no tr-before transition time left

        advanceCurrentTime(Duration.ofMinutes(5)); // at defined start

        ScheduledRunnable normalPowerOn = simulateLightOnEventExpectingSingleScheduledState(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(normalPowerOn,
                expectedPutCall(1).bri(initialBrightness + 210) // no transition anymore
        );
    }

    @Test
    void parse_transitionTimeBefore_usingInterpolate_longTransition_correctSplitCalls() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(30), "bri:" + (initialBrightness + 210), "interpolate:true");
        // 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();

        assertScheduleStart(trRunnable, now, initialNow.plusDays(1));

        // first split

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(initialBrightness), // previous state call as interpolation start
                expectedPutCall(1).bri(initialBrightness + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // scheduled second split of transition
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable followUpRunnable = followUpRunnables.getFirst();

        // run follow-up call for the second split

        advanceTimeAndRunAndAssertPutCalls(followUpRunnable,
                // no interpolation as "initialBrightness + 100" already set at end of first part
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME) // second split of transition
        );

        ScheduledRunnable finalSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS),
                initialNow.plusDays(1)); // scheduled third split of transition

        advanceTimeAndRunAndAssertPutCalls(finalSplit,
                // no interpolation as "initialBrightness + 200" already set at end of second part
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(tr("10min")) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_manuallyOverridden_stillCorrectlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusHours(2), "bri:160", "tr-before:110min");
        // 110min = 1h50min -> split to: 100min + 10min

        setCurrentTimeTo(now.plusMinutes(10)); // directly at start
        manualOverrideTracker.onManuallyOverridden("/lights/" + 1); // start directly with overridden state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trRunnable = scheduledRunnables.getFirst();
        assertScheduleStart(trRunnable, now, initialNow.plusDays(1));

        trRunnable.run(); // no interaction, as directly skipped

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // power-on event

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(
                now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)); // first split power on

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // previous state call as interpolation start
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        // no user modification since split call -> proceeds normally

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 100 - 2)); // same as split call

        advanceTimeAndRunAndAssertPutCalls(secondSplit,
                // no interpolation, as "DEFAULT_BRIGHTNESS + 100" already set before
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 110).transitionTime(tr("10min")) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsFirstSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min"); // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        setLightStateResponse(1, expectedState().brightness(initialBrightness + BRIGHTNESS_OVERRIDE_THRESHOLD)); // user modification
        setCurrentTimeToAndRun(trRunnable); // detects manual override

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // run second split; still overridden
        advanceTimeAndRunAndAssertPutCalls(secondSplit);

        ScheduledRunnable finalSplit = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)) // scheduled final split of transition
        ).getFirst();

        // power-on event, skips first split, as not relevant anymore

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.minus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)),  // already ended
                expectedPowerOnEnd(now), // already ended; power first split
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS))
        );
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // already ended

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(2),
                expectedPutCall(1).bri(initialBrightness + 100), // end of first split, which was skipped
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(MAX_TRANSITION_TIME) // second split of transition
        );

        // second power on, right at the start of the gap

        advanceCurrentTime(Duration.ofMillis(MAX_TRANSITION_TIME_MS).minusMinutes(2));
        ScheduledRunnable powerOn = simulateLightOnEventExpectingSingleScheduledState(now.plusMinutes(2));

        advanceTimeAndRunAndAssertPutCalls(powerOn,
                expectedPutCall(1).bri(initialBrightness + 198),
                expectedPutCall(1).bri(initialBrightness + 200).transitionTime(tr("2min"))
        );

        // final split

        setLightStateResponse(1, expectedState().brightness(initialBrightness + 200)); // same as second split
        advanceTimeAndRunAndAssertPutCalls(finalSplit,
                // no interpolation, as "initialBrightness + 200" already set at end of second split
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(tr("10min"))// remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsSecondSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // run tr-runnable -> detects manual override

        setLightStateResponse(1, expectedState().brightness(initialBrightness + BRIGHTNESS_OVERRIDE_THRESHOLD)); // user modification
        setCurrentTimeToAndRun(trRunnable); // detects manual override

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // power-on event -> retries first split

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now), // first state, already ended
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)) // powerOn first split
        );

        powerOnRunnables.get(0).run(); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(initialBrightness), // previous state call as interpolation start
                expectedPutCall(1).bri(initialBrightness + 100).transitionTime(MAX_TRANSITION_TIME) // first split of transition
        );

        // second split -> detects second manual override

        setLightStateResponse(1, expectedState().brightness(initialBrightness + 100 + BRIGHTNESS_OVERRIDE_THRESHOLD)); // second modification
        setCurrentTimeToAndRun(secondSplit); // detects manual override

        ScheduledRunnable finalSplit = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)) // scheduled final split of transition
        ).getFirst();

        // final split; still overridden. skip second split
        advanceTimeAndRunAndAssertPutCalls(finalSplit);

        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.minus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(now), // already ended; second split
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(1)); // already ended

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(2),
                expectedPutCall(1).bri(initialBrightness + 200), // end of the second part, which was skipped
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_detectsManualOverrides_skipsFirstAndSecondSplit_correctlyInterpolated() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        setCurrentTimeTo(trRunnable);
        setLightStateResponse(1, expectedState().brightness(initialBrightness + BRIGHTNESS_OVERRIDE_THRESHOLD)); // user modification
        trRunnable.run(); // detects manual override

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // second split -> still overridden
        advanceTimeAndRunAndAssertPutCalls(secondSplit);

        ScheduledRunnable finalSplit = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)) // next split
        ).getFirst();

        // final split -> still overridden
        advanceTimeAndRunAndAssertPutCalls(finalSplit);

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // initial state, already ended
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // first split power on, already ended
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS)), // second split power on, already ended
                expectedPowerOnEnd(initialNow.plusDays(1)) // final split power on
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(0)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(2)); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(3),
                expectedPutCall(1).bri(initialBrightness + 200), // end of the second part, which was skipped
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(6000) // remaining 10 minutes
        );
    }

    @Test
    void parse_canParseTransitionTimeBefore_longTransition_powerOnInGaps_correctlyHandled() {
        minTrGap = 2;
        int MAX_TRANSITION_TIME_WITH_BUFFER = MAX_TRANSITION_TIME - minTrGap * 600;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        int initialBrightness = 40;
        addState(1, now, "bri:" + initialBrightness);
        addState(1, now.plusHours(3).plusMinutes(40), "bri:" + (initialBrightness + 210), "tr-before:210min");  // 03:40 -> 00:10
        // 210min = 3h30min -> split to: 100min + 100min + 10min

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
        ScheduledRunnable initialRunnable = scheduledRunnables.get(0);
        ScheduledRunnable trRunnable = scheduledRunnables.get(1);

        // first state runs normally
        advanceTimeAndRunAndAssertPutCalls(initialRunnable, expectedPutCall(1).bri(initialBrightness));

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)); // next day

        // Run tr-runnable

        advanceTimeAndRunAndAssertPutCalls(trRunnable,
                expectedPutCall(1).bri(initialBrightness + 100 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER) // first split of transition
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // scheduled second split of transition
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpRunnables.getFirst();

        // Power on, right at the start of the gap

        advanceCurrentTime(Duration.ofMillis(MAX_TRANSITION_TIME_MS).minusMinutes(minTrGap));
        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // first state, already ended
                expectedPowerOnEnd(now.plusMinutes(minTrGap)) // first split again
        );

        powerOnRunnables.get(0).run(); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                // performs just the interpolation call
                expectedPutCall(1).bri(initialBrightness + 100 - 2)
        );

        // Power on, one minute in the gap

        advanceCurrentTime(Duration.ofMinutes(1));
        ScheduledRunnable inGapPowerRunnable = simulateLightOnEventExpectingSingleScheduledState(now.plusMinutes(minTrGap - 1));

        advanceTimeAndRunAndAssertPutCalls(inGapPowerRunnable,
                // performs just the interpolation call
                expectedPutCall(1).bri(initialBrightness + 100 - 1)
        );

        advanceTimeAndRunAndAssertPutCalls(secondSplit,
                expectedPutCall(1).bri(initialBrightness + 100),
                expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime(MAX_TRANSITION_TIME_WITH_BUFFER) // first split of transition
        );

        ScheduledRunnable thirdSplit = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1));

        // Advance to middle of second split, and cause power on -> does not over extend the transition, even though its less than the max transition length

        advanceCurrentTime(Duration.ofMillis(MAX_TRANSITION_TIME_MS / 2));

        List<ScheduledRunnable> powerOnRunnables2 = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended power on for first split
                expectedPowerOnEnd(initialNow.plusMinutes(10).plus(MAX_TRANSITION_TIME_MS * 2, ChronoUnit.MILLIS))
        );

        Duration untilThirdSplit = Duration.between(now, thirdSplit.getStart()).minusMinutes(minTrGap);
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables2.get(1),
                expectedPutCall(1).bri(initialBrightness + 150), // interpolated call
                expectedPutCall(1).bri(initialBrightness + 200 - 2).transitionTime((int) (untilThirdSplit.toMillis() / 100)) // transition till start of third and final split (minus buffer)
        );

        // Third and final split

        advanceTimeAndRunAndAssertPutCalls(thirdSplit,
                expectedPutCall(1).bri(initialBrightness + 200),
                expectedPutCall(1).bri(initialBrightness + 210).transitionTime(tr("10min")) // remaining 10 minutes
        );
    }

    @Test
    void parse_transitionTimeBeforeBefore_longDuration_24Hours_zeroLengthStateBefore_performsInterpolationOverWholeDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:254", "tr-before:24h");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length state
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(1),
                expectedPutCall(1).bri(19).transitionTime(MAX_TRANSITION_TIME)
        );

        ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
    }

    @Test
    void parse_transitionTimeBefore_moreThan24Hours_25hours_overAdjusts_treatedAs23Hours_correctlyScheduled() { // todo: this is not officially supported, but there is no validation right now
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr-before:25h");

        startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1).minusHours(1)) // strange artifact
        );
    }

    @Test
    void parse_transitionTImeBefore_longDuration_lightIsConsideredOffForSplitCall_skipsFurtherSplitCallsUntilPowerOn() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:1");
        addState(1, now, "bri:254", "tr-before:24h");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length state
        );
        ScheduledRunnable firstSplit = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(firstSplit,
                expectedPutCall(1).bri(1), // interpolated call
                expectedPutCall(1).bri(19).transitionTime(MAX_TRANSITION_TIME)
        );

        List<ScheduledRunnable> followUpStates = ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), now.plusDays(1)), // next split
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );
        ScheduledRunnable secondSplit = followUpStates.getFirst();

        mockIsLightOff(1, true);
        advanceTimeAndRunAndAssertPutCalls(secondSplit); // no split call
        mockIsLightOff(1, false);

        // still scheduled next split call
        ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)); // next split

        // power on event -> uses powerOn from second split

        List<ScheduledRunnable> powerOnEvents = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)), // already ended
                expectedPowerOnEnd(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS)) // power on second split
        );

        powerOnEvents.get(0).run(); // already ended
        advanceTimeAndRunAndAssertPutCalls(powerOnEvents.get(1),
                expectedPutCall(1).bri(19), // end of first split
                expectedPutCall(1).bri(36).transitionTime(MAX_TRANSITION_TIME)
        );
    }

    @Test
    void parse_transitionTimeBefore_shiftsGivenStartByThatTime_afterPowerCycle_sameStateAgainWithTransitionTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // previous call for interpolation start
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // previous call for interpolation start
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("10min"))
        );
    }

    @Test
    void parse_transitionTimeBefore_allowsBackToBack_zeroLengthState_bri_usedOnlyForInterpolation() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS); // 00:00, zero length
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min"); // 00:00, same start as initial
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 20)); // 10:00, zero length
        addState(1, "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 30), "interpolate:true"); // 10:00 same start as second zero length

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(10)), // 00:10 zero length state
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // 00:00 zero length state, scheduled next day
        );
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable zeroLengthStateAtTen = scheduledRunnables.get(1);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(2);

        // first tr-before runnable
        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // power on after 5 minutes -> correct interpolation

        advanceCurrentTime(Duration.ofMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(initialNow.plusMinutes(10));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 5),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        // run 00:10 zero length runnable -> no interaction

        advanceTimeAndRunAndAssertPutCalls(zeroLengthStateAtTen);

        ensureRunnable(now.plusDays(1), now.plusDays(1)); // next day, again zero length

        // second tr-before

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_allowsBackToBack_zeroLengthState_ct_usedOnlyForInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:160"); // 00:00, zero length
        addState(1, "00:10", "ct:170", "interpolate:true"); // 00:00, same start as initial
        addState(1, "00:10", "ct:180"); // 10:00, zero length
        addState(1, "00:20", "ct:190", "interpolate:true"); // 10:00 same start as second zero length

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(10)), // 00:10 zero length
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // 00:00 zero length state, scheduled next day
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).ct(160),
                expectedPutCall(1).ct(170).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no interaction; zero length

        ensureRunnable(now.plusDays(1), now.plusDays(1)); // next day

        // second tr-before

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).ct(180),
                expectedPutCall(1).ct(190).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_allowsBackToBack_zeroLengthState_x_usedOnlyForInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "x:0.25", "y:0.2"); // 00:00, zero length
        addState(1, "00:10", "x:0.26", "y:0.2", "interpolate:true"); // 00:00, same start as initial
        addState(1, "00:10", "x:0.27", "y:0.2"); // 10:00, zero length
        addState(1, "00:20", "x:0.28", "y:0.2", "interpolate:true"); // 10:00 same start as second zero length

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(10)), // 00:10 zero length
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // 00:00 zero length state, scheduled next day
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).x(0.25).y(0.2),
                expectedPutCall(1).x(0.26).y(0.2).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no interaction; zero length

        ensureRunnable(now.plusDays(1), now.plusDays(1)); // next day

        // second tr-before

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).x(0.27).y(0.2),
                expectedPutCall(1).x(0.28).y(0.2).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_allowsBackToBack_zeroLengthState_y_usedOnlyForInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "x:0.2", "y:0.21"); // 00:00, zero length
        addState(1, "00:10", "x:0.2", "y:0.22", "interpolate:true"); // 00:00, same start as initial
        addState(1, "00:10", "x:0.2", "y:0.23"); // 10:00, zero length
        addState(1, "00:20", "x:0.2", "y:0.24", "interpolate:true"); // 10:00 same start as second zero length

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(10)), // 00:10 zero length
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // 00:00 zero length state, scheduled next day
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).x(0.2).y(0.21),
                expectedPutCall(1).x(0.2).y(0.22).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no interaction; zero length

        ensureRunnable(now.plusDays(1), now.plusDays(1)); // next day

        // second tr-before

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).x(0.2).y(0.23),
                expectedPutCall(1).x(0.2).y(0.24).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_allowsBackToBack_zeroLengthState_gradient_usedOnlyForInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "gradient:[xy(0.2 0.3), xy(0.4 0.4)]"); // 00:00, zero length
        addState(1, "00:10", "gradient:[xy(0.2 0.3), xy(0.4 0.41)]", "interpolate:true"); // 00:00, same start as initial
        addState(1, "00:10", "gradient:[xy(0.2 0.3), xy(0.4 0.42)]"); // 10:00, zero length
        addState(1, "00:20", "gradient:[xy(0.2 0.3), xy(0.4 0.43)]", "interpolate:true"); // 10:00 same start as second zero length

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(10)), // 00:10 zero length
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // 00:00 zero length state, scheduled next day
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.4, 0.4)
                                                    ))
                                                    .build()),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.4, 0.41)
                                                    ))
                                                    .build()).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no interaction; zero length

        ensureRunnable(now.plusDays(1), now.plusDays(1)); // next day

        // second tr-before

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.4, 0.42)
                                                    ))
                                                    .build()),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.2, 0.3),
                                                            Pair.of(0.4, 0.43)
                                                    ))
                                                    .build()).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_viaInterpolate_usesTransitionFromPreviousState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "tr:2");
        addState(1, "00:30", "bri:" + (DEFAULT_BRIGHTNESS + 30), "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(2),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2));
    }

    @Test
    void parse_interpolate_effect_noBrightness_noInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "effect:candle");
        addState(1, "00:30", "bri:" + DEFAULT_BRIGHTNESS, "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).effect(Effect.builder().effect("candle").build())
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(30)); // next day
    }

    @Test
    void parse_interpolate_differentColorMode_butSameProperties_noInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:200");
        addState(1, "00:30", "x:0.3473", "y:0.3523", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).ct(200)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(30)); // next day
    }

    @Test
    void parse_interpolate_differentColorMode_differentProperties_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:153");
        addState(1, "00:30", "x:0.3473", "y:0.3523", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).x(0.3157).y(0.3329), // = ct:153 converted to xy
                expectedPutCall(1).x(0.3473).y(0.3523).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day
    }

    @Test
    void parse_interpolate_effect_withBrightness_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:50", "effect:candle");
        addState(1, "00:30", "bri:100", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50).effect(Effect.builder().effect("candle").build()),
                expectedPutCall(1).bri(100).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day
    }

    @Test
    void parse_interpolate_gradient_differentValues_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "gradient:[xy(0.8 0.2), xy(0.2 0.3)]");
        addState(1, "00:30", "gradient:[xy(0.8 0.2), xy(0.4 0.4)]", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.6915, 0.3083),
                                                            Pair.of(0.2, 0.3)
                                                    ))
                                                    .build()),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.6915, 0.3083),
                                                            Pair.of(0.4, 0.4)
                                                    ))
                                                    .build())
                                  .transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day
    }

    @Test
    void parse_interpolate_gradient_sameValues_noInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "gradient:[xy(0.8 0.2), xy(0.2 0.3)]");
        addState(1, "00:30", "gradient:[xy(0.8 0.2), xy(0.2 0.3)]", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.6915, 0.3083),
                                                            Pair.of(0.2, 0.3)
                                                    ))
                                                    .build())
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(30)); // next day
    }

    @Test
    void parse_interpolate_gradient_sameValues_differentMode_ignored_noInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "gradient:[xy(0.8 0.2), xy(0.2 0.3)]");
        addState(1, "00:30", "gradient:[xy(0.8 0.2), xy(0.2 0.3)]@random_pixelated", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).gradient(Gradient.builder()
                                                    .points(List.of(
                                                            Pair.of(0.6915, 0.3083),
                                                            Pair.of(0.2, 0.3)
                                                    ))
                                                    .build())
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(30)); // next day
    }

    @Test
    void parse_interpolate_gradient_noOverlappingProperties_noInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "gradient:[xy(0.8 0.2), xy(0.2 0.3)]");
        addState(1, "00:30", "bri:100", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).gradient(Gradient.builder()
                                                             .points(List.of(
                                                                     Pair.of(0.6915, 0.3083),
                                                                     Pair.of(0.2, 0.3)
                                                             ))
                                                             .build())
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(30)); // next day
    }

    @Test
    void parse_transitionTimeBefore_performsInterpolationsAfterPowerCycles_usesTransitionFromPreviousState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr:1"); // this transition is used in interpolated call
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day

        ScheduledRunnable powerOnRunnable1 = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable1,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ScheduledRunnable powerOnRunnable2 = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable2,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );
    }

    @Test
    void parse_transitionTimeBefore_ifPreviousHasNotTransitionTime_usesDefault() {
        defaultInterpolationTransitionTimeInMs = "2"; // default value
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS); // no explicit transition time set
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(2),  // uses default
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_usesPreviousTransitionTime_zeroValue_correctlyReused() {
        defaultInterpolationTransitionTimeInMs = "2"; // default value
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS, "tr:0"); // reuses this value
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:9min");

        setCurrentTimeTo(now.plusMinutes(2)); // one minute after tr-before state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, initialNow.plusDays(1)),
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 1).transitionTime(0), // reused previous value
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("8min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_multipleTrBeforeAfterEachOther_repeatedNextDay_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min"); // 00:05 -> 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min"); // 00:10 -> 00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(1);

        // first tr-before, 00:00

        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10), // interpolated call from "previous" state from day before
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay1 = ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // second tr-before

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        // no interpolation as previous state = last seen state
        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay2 = ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(5)); // next day

        // repeat next day, same calls expected

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));

        // no interpolation as previous state = last seen state
        advanceTimeAndRunAndAssertPutCalls(nextDay1,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay3 = ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));

        // no interpolation as previous state = last seen state
        advanceTimeAndRunAndAssertPutCalls(nextDay2,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ScheduledRunnable nextDay4 = ensureRunnable(now.plusDays(1), now.plusDays(2).minusMinutes(5)); // next day

        // repeat next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));

        advanceTimeAndRunAndAssertPutCalls(nextDay3,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)); // next day

        // turn on again, 2 minutes after start

        advanceCurrentTime(Duration.ofMinutes(2));

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(2)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(2).plusMinutes(5))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(4),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 6), // adjusted interpolated call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min"))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        setCurrentTimeTo(nextDay4);
        advanceCurrentTime(Duration.ofMinutes(5)); // "turned on" at defined start, i.e., no interpolation and transition time expected

        runAndAssertPutCalls(nextDay4,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureRunnable(now.plusDays(1).minusMinutes(5), now.plusDays(2).minusMinutes(10)); // next day
    }

    @Test
    void parse_transitionTimeBefore_withDayOfWeek_backToBack_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min"); // only on TU and WE: 00:05 -> 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min", "days:MO,TU"); // 00:10 -> 00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)), // first state from day before
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                // no interpolated call, as second state is not scheduled on Sunday (the day before)
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // no tr since state from day before is already reached
        );

        ScheduledRunnable firstStateOnMonday = ensureRunnable(now.plusMinutes(5), now.plusMinutes(5)); // zero length

        // first state on monday -> zero length

        advanceTimeAndRunAndAssertPutCalls(firstStateOnMonday); // no put calls, as zero length

        ScheduledRunnable firstStateOnTuesday = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(5)); // next day (Tuesday), adjusted start

        ScheduledRunnable zeroLengthPowerOn = simulateLightOnEventExpectingSingleScheduledState(now);// power on but zero length state, no interaction
        advanceTimeAndRunAndAssertPutCalls(zeroLengthPowerOn);

        // second state on monday

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                // expectedPutCall(1).bri(DEFAULT_BRIGHTNESS),  -> no interpolation anymore since last turnedOn tracking change
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ScheduledRunnable secondStateOnTuesday = ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day (Tuesday)

        // [Tuesday] run next day, first state now has previous state and adjusts start

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnTuesday,
                // no interpolation, as "DEFAULT_BRIGHTNESS + 10" was already set before
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ScheduledRunnable firstStateOnWednesday = ensureRunnable(now.plusDays(1), now.plusDays(2).plusMinutes(5)); // next day (Wednesday), now full day, as there is no additional state
        // since there is no previous state for the first state on Thursday, the Wednesday state ends at 00:05 again, instead of 00:00

        // simulate light on event, which schedules the same state again -> but now with interpolations

        List<ScheduledRunnable> powerOnRunnables2 = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(now.plusMinutes(5))
        );

        // same state as firstStateOnTuesday
        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables2.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        // second state

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(secondStateOnTuesday,
                // no interpolation, as "DEFAULT_BRIGHTNESS" was already set
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(7).plusMinutes(5), initialNow.plusDays(8)); // next week (Monday)

        // [Wednesday] run next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnWednesday,
                // no interpolation as "DEFAULT_BRIGHTNESS + 10" was already set
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("5min"))
        );

        ensureRunnable(now.plusDays(1).plusMinutes(5), now.plusDays(2).plusMinutes(5)); // next day (Thursday)
    }

    @Test
    void parse_transitionTimeBefore_withDayOfWeek_backToBack_onlyOnOneDay_twoDaysInTheFuture_correctlyScheduled() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min", "days:WE"); // 00:05 -> never adjusted as no previous state, effectively zero length
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min", "days:MO,WE"); // 00:10 -> only on WE adjusted to 00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)), // second state today (Monday), no adjustment
                expectedRunnable(now.plusDays(2).plusMinutes(5), now.plusDays(2).plusMinutes(5)) // first state on Wednesday, zero length
        );
        ScheduledRunnable secondStateOnMonday = scheduledRunnables.get(0);
        ScheduledRunnable firstStateOnWednesday = scheduledRunnables.get(1);

        // second tr-before

        advanceTimeAndRunAndAssertPutCalls(secondStateOnMonday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no transition, as no previous state
        );

        ScheduledRunnable secondStateOnWednesday = ensureRunnable(initialNow.plusDays(2).plusMinutes(5), initialNow.plusDays(3)); // on Wednesday

        // move to Wednesday: first tr-before, zero length

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnWednesday); // no interaction as zero length

        ensureRunnable(now.plusDays(7), now.plusDays(7)); // next Wednesday, again zero length

        // second tr-before again -> adjusted transition time

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(secondStateOnWednesday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS), // interpolated call from zero length previous state
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(7).plusMinutes(10), initialNow.plusDays(8)); // next monday
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnMonday_todayIsMonday_crossesOverToPreviousDay_stillScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "days:SU");
        addState(1, "00:00", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:MO");

        startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(6), now.plusDays(7).minusMinutes(10))
        );
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnTuesday_todayIsMonday_crossesOverToPreviousDay_scheduledAtEndOfDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now, "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:TU");

        startScheduler(
                expectedRunnable(now, now.plusDays(1).minusMinutes(10)),
                expectedRunnable(now.plusDays(1).minusMinutes(10), now.plusDays(2))
        );
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_onlyOnSunday_todayIsMonday_crossesOverToPreviousDay_scheduledNextWeek() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "days:SA");
        addState(1, "00:00", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "days:SU");

        startScheduler(
                expectedRunnable(now.plusDays(5), now.plusDays(6).minusMinutes(10)),
                expectedRunnable(now.plusDays(6).minusMinutes(10), now.plusDays(7))
        );
    }

    @Test
    void parse_transitionTimeBefore_withDaysOfWeek_backToBack_onOneDayOfWeek_correctGapAdded() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "00:05", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:5min", "days:SA"); // 00:05 -> 00:00
        addState(1, "00:10", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min"); // 00:10 -> adjusted to 00:05 ONLY on SA

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // day cross over of second state
                expectedRunnable(now.plusDays(5), now.plusDays(5).plusMinutes(5)) // state scheduled next Saturday
        );
        ScheduledRunnable dayCrossOverState = scheduledRunnables.get(0);
        ScheduledRunnable firstStateOnSaturday = scheduledRunnables.get(1);

        // cross over state (second tr-before runnable from Sunday)

        advanceTimeAndRunAndAssertPutCalls(dayCrossOverState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ScheduledRunnable secondStateOnMonday = ensureRunnable(now.plusMinutes(10), now.plusDays(1).plusMinutes(10)); // rescheduled second state

        // no interpolated call, as state is not scheduled on Sunday (the day before)
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(secondStateOnMonday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day

        // state on saturday with correct transition and end

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + 10));
        advanceTimeAndRunAndAssertPutCalls(firstStateOnSaturday,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("3min")) // gap added
        );

        ensureRunnable(now.plusDays(7), now.plusDays(7).plusMinutes(5));
    }

    @Test
    void parse_transitionTimeBefore_crossesDayLine_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:" + DEFAULT_CT, "tr-before:20min"); // 00:00 -> 23:50
        addState(1, "12:00", "ct:" + (DEFAULT_CT + 20));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1).minusMinutes(20))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).ct(DEFAULT_CT) // no interpolation, as state is already reached
        );

        ScheduledRunnable firstStateNextDay = ensureRunnable(initialNow.plusDays(1).minusMinutes(20), initialNow.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).ct(DEFAULT_CT + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2).minusMinutes(20));

        advanceTimeAndRunAndAssertPutCalls(firstStateNextDay,
                expectedPutCall(1).ct(DEFAULT_CT).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(2).minusMinutes(20), initialNow.plusDays(2).plusHours(12));
    }

    @Test
    void parse_dayCrossOver_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(20), "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1).minusMinutes(20));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ScheduledRunnable runnable = ensureRunnable(now.plusDays(1).minusMinutes(20), now.plusDays(2).minusMinutes(20));

        setCurrentTimeTo(now.plusDays(1).plusHours(1));
        runAndAssertPutCalls(runnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(2).minusMinutes(20), initialNow.plusDays(3).minusMinutes(20));
    }

    @Test
    void parse_transitionTimeBefore_overNight_doesNotOverAdjustTransitionTime_returnsNullInstead() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(50));
        addState(1, "23:50", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "23:55", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:5min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1))
        );

        advanceCurrentTime(Duration.ofMinutes(10)); // here we cross over to tomorrow

        runAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10) // no transition time expected
        );

        ensureNextDayRunnable(initialNow);
    }

    @Test
    void parse_transitionTimeBefore_longDuration_dayCrossOver_correctlyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2));
        ZonedDateTime sunset = startTimeProvider.getStart("sunset", now); // 16:14:29
        ZonedDateTime nextDaySunset = startTimeProvider.getStart("sunset", now.plusDays(1));
        addState(1, "sunset", "bri:10"); // 16:14:29
        addState(1, "sunrise", "bri:254", "tr-before:8h"); // sunrise - 8h = 23:42:13 (previous day)

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, sunset), // sunrise started yesterday
                expectedRunnable(sunset, nextDaySunrise.minusHours(8)) // sunset from today
        );
        ScheduledRunnable crossOverState = scheduledRunnables.get(0);
        ScheduledRunnable sunsetState = scheduledRunnables.get(1);

        setCurrentTimeTo(crossOverState);

        ZonedDateTime trBeforeStart = sunrise.minusHours(8);
        Duration initialStartOffset = Duration.between(trBeforeStart, now);
        long adjustedSplitDuration = MAX_TRANSITION_TIME * 100 - initialStartOffset.toMillis();
        long adjustedNextStart = MAX_TRANSITION_TIME_MS - initialStartOffset.toMillis();

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).bri(19), // interpolated call
                expectedPutCall(1).bri(61).transitionTime((int) (adjustedSplitDuration / 100)) // first split call
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(now.plus(adjustedNextStart, ChronoUnit.MILLIS), sunset), // next split call
                expectedRunnable(nextDaySunrise.minusHours(8), nextDaySunset) // sunrise rescheduled for the next day
        );
        ScheduledRunnable firstSplitCall = followUpRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(firstSplitCall,
                expectedPutCall(1).bri(112).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable secondSplitCall = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        advanceTimeAndRunAndAssertPutCalls(secondSplitCall,
                expectedPutCall(1).bri(163).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable thirdSplitCall = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        advanceTimeAndRunAndAssertPutCalls(thirdSplitCall,
                expectedPutCall(1).bri(213).transitionTime(MAX_TRANSITION_TIME)
        );

        ScheduledRunnable fourthSplitCall = ensureRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), sunset);

        setCurrentTimeTo(fourthSplitCall);
        Duration finalSplitDuration = Duration.between(now, sunrise);

        advanceTimeAndRunAndAssertPutCalls(fourthSplitCall,
                expectedPutCall(1).bri(254).transitionTime((int) (finalSplitDuration.toMillis() / 100L))
        );

        // power on event

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(firstSplitCall.getStart()), // already ended
                expectedPowerOnEnd(secondSplitCall.getStart()), // already ended
                expectedPowerOnEnd(thirdSplitCall.getStart()), // already ended
                expectedPowerOnEnd(fourthSplitCall.getStart()), // already ended
                expectedPowerOnEnd(sunset)
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(4),
                expectedPutCall(1).bri(213), // interpolated call
                expectedPutCall(1).bri(254).transitionTime((int) (finalSplitDuration.toMillis() / 100L))
        );

        // sunset state

        advanceTimeAndRunAndAssertPutCalls(sunsetState, expectedPutCall(1).bri(10));

        ensureRunnable(nextDaySunset, nextNextDaySunrise.minusHours(8));
    }

    @Test
    void parse_transitionTimeBefore_backToBack_alreadyEnoughGapInBetween_noAdjustmentsMade() {
        minTrGap = 2;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10"); // zero length
        addState(1, "01:00", "bri:100", "tr-before:1h"); // gap not relevant
        addState(1, "01:30", "bri:254", "tr-before:28min"); // gap of 2 minutes added

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1).plusMinutes(minTrGap)),
                expectedRunnable(now.plusHours(1).plusMinutes(minTrGap), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(1);

        // first tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(firstTrRunnable,
                expectedPutCall(1).bri(10), // interpolated call
                expectedPutCall(1).bri(100).transitionTime(36000) // no additional buffer used
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1).plusMinutes(minTrGap)); // next day

        // second tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(secondTrRunnable,
                expectedPutCall(1).bri(254).transitionTime(16800) // no additional buffer used
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_withModificationTracking_backToBack_smallTrBefore_lessThanTwoMinutes_adjustedTrToEnsureGap() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10"); // zero length
        addState(1, "00:02", "bri:100", "tr-before:2min"); // 00:00-00:02
        addState(1, "00:04", "bri:254", "tr-before:2min"); // 00:02-00:04
        addState(1, "00:05", "bri:100", "tr-before:1min"); // 00:04-00:05

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(4)),
                expectedRunnable(now.plusMinutes(4), now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(1);
        ScheduledRunnable thirdTrRunnable = scheduledRunnables.get(2);

        // first

        advanceTimeAndRunAndAssertPutCalls(firstTrRunnable,
                expectedPutCall(1).bri(10),  // interpolated call
                expectedPutCall(1).bri(100) // removed tr to ensure gap, does not use zero
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(2));

        // second

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(secondTrRunnable,
                expectedPutCall(1).bri(254) // removed tr to ensure gap, does not use zero
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(2));

        // third

        setLightStateResponse(1, expectedState().brightness(254));
        advanceTimeAndRunAndAssertPutCalls(thirdTrRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("1min"))
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTime_withModificationTracking_backToBack_ensuresGap() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "tr:2min"); // to small gap
        addState(1, "00:05", "bri:150", "tr:6min"); // too long tr, which would cause overlap
        addState(1, "00:10", "bri:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10) // removes tr
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        setLightStateResponse(1, expectedState().brightness(10));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("1min")) // ensures min gap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(5));

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).bri(150).transitionTime(tr("3min")) // shortened tr and ensures gap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(10));
    }

    @Test
    void parse_transitionTime_withModificationTracking_backToBack_customGap_usesGap() {
        minTrGap = 5; // 5 minutes
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:7min");
        addState(1, "00:07", "bri:100");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(7)),
                expectedRunnable(now.plusMinutes(7), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")) // adjusts to custom gap
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(7));
    }

    @Test
    void parse_transitionTime_withUserModificationTracking_backToBack_stateHasForceProperty_doesNotAddGap() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "force:true", "tr:3min"); // would overlap
        addState(1, "00:04", "bri:100", "force:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(4)),
                expectedRunnable(now.plusMinutes(4), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")) // does not remove tr
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("2min")) // still adjusts tr to avoid overlap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(4));
    }

    @Test
    void parse_transitionTime_noUserModificationTracking_backToBack_doesNotAddGap_stillShortensIfOverlap() {
        minTrGap = 2;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:10", "tr:2min");
        addState(1, "00:02", "bri:100", "tr:4min"); // would overlap
        addState(1, "00:05", "bri:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(2), now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(10).transitionTime(tr("2min")) // does not remove tr
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(2));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(100).transitionTime(tr("3min")) // shortened tr to avoid overlap
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusMinutes(5));
    }

    @Test
    void parse_transitionTimeBefore_backToBack_noGapsInBetween_ignoresPreviousDay_adjustsTr() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100", "tr-before:1h"); // 23:00-00:00
        addState(1, "00:30", "bri:254", "tr-before:30min"); // 00:00-00:30

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)), // second state scheduled first as other already ended at 00:00
                expectedRunnable(now.plusDays(1).minusHours(1), now.plusDays(1))
        );
        ScheduledRunnable secondTrRunnable = scheduledRunnables.get(0);
        ScheduledRunnable firstTrRunnable = scheduledRunnables.get(1);

        // second tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(secondTrRunnable,
                expectedPutCall(1).bri(100),
                expectedPutCall(1).bri(254).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2).minusHours(1)); // next day

        // first tr-before runnable, 23:00

        setLightStateResponse(1, expectedState().brightness(254));
        advanceTimeAndRunAndAssertPutCalls(firstTrRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("58min")) // gap added
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_longDuration_backToBack_noGapsInBetween_considersPreviousDay_adjustsTrBefore() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100", "tr-before:23h"); // 01:00, starts yesterday
        addState(1, "01:00", "bri:254", "tr-before:30min"); // 01:00 -> 00:30

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(30)),
                expectedRunnable(now.plusMinutes(30), now.plusHours(1))
        );
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(0);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(1);

        // first tr-before runnable

        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                // no interpolation, as state already started
                expectedPutCall(1).bri(100)
        );

        ScheduledRunnable nextDayFirst = ensureRunnable(now.plusHours(1), initialNow.plusDays(1).plusMinutes(30));

        // second tr-before runnable

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                expectedPutCall(1).bri(254).transitionTime(tr("28min")) // gap added
        );

        ensureRunnable(now.plusDays(1), initialNow.plusDays(1).plusHours(1));

        // next day first

        setLightStateResponse(1, expectedState().brightness(254));
        advanceTimeAndRunAndAssertPutCalls(nextDayFirst,
                expectedPutCall(1).bri(243).transitionTime(MAX_TRANSITION_TIME - minTrGap * 600) // first split call
        );

        ensureScheduledStates(
                expectedRunnable(now.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1).plusMinutes(30)),
                expectedRunnable(initialNow.plusDays(1).plusHours(1), initialNow.plusDays(2).plusMinutes(30)) // next day
        );
    }

    @Test
    void parse_transitionTimeBefore_group_lightTurnedOnLater_stillBeforeStart_transitionTimeIsShortenedToRemainingTimeBefore() {
        mockGroupLightsForId(1, 5);
        addKnownLightIdsWithDefaultCapabilities(1);
        mockDefaultGroupCapabilities(1);
        addState("g1", "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceCurrentTime(Duration.ofMinutes(15));

        runAndAssertGroupPutCalls(scheduledRunnables.get(1),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 5), // interpolated call
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_lightTurnedAfterStart_trBeforeIgnored_normalTransitionTimeUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "00:20", "bri:" + (DEFAULT_BRIGHTNESS + 10), "tr-before:10min", "tr:3s");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceCurrentTime(Duration.ofMinutes(20));

        runAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).transitionTime(tr("3s"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_nullState_performsNoInterpolations_doesNotAdjustStart() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState(1, now.plusMinutes(2)); // null state, detected as previous state -> treated as no interpolation possible
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min"); // tr-before ignored as no previous state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(2)),
                expectedRunnable(now.plusMinutes(40), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(40), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_nullState_splitCall_performsNotInterpolation_doesNotAdjustStart() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + (DEFAULT_BRIGHTNESS - 10));
        addState(1, "01:00"); // null state, picked as previous
        addState(1, "12:00", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:10h"); // ignored, as no previous state

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS - 10)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2)); // next day

        // simulate power on -> no power on for tr-before state scheduled

        simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusHours(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_previousIsOnState_withBrightness_removedForInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "on:true", "bri:10");
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(10), // no "on" property
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_previousIsOnStateOnly_notInterpolationAtAll_noAdjustmentOfStart() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "on:true"); // just a single "on"
        addState(1, now.plusMinutes(40), "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(40)),
                expectedRunnable(now.plusMinutes(40), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(0),
                expectedPutCall(1).bri(50).on(true) // full picture on initial startup
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(40)); // next day

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                // no interpolation call
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // no transition, as start was not adjusted
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(40), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOnState_alsoAddsOnPropertyToInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:10"); // no explicit "on"
        addState(1, now.plusMinutes(40), "on:true", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(10).on(true), // added "on" property
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).on(true).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOffState_doesNotAddOffPropertyToInterpolatedCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:200"); // no explicit "off"
        addState(1, now.plusMinutes(40), "on:false", "ct:300", "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(200), // no "off" added
                expectedPutCall(1).ct(300).on(false).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_currentIsOffState_interpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10");
        addState(1, now.plusMinutes(10), "on:false", "interpolate:true", "force:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10),
                expectedPutCall(1).on(false).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // turn lights on after 5 minutes

        advanceCurrentTime(Duration.ofMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)) // only until defined start
        ).getFirst();

        powerOnRunnable.run();

        assertPutCalls(
                expectedPutCall(1).bri(5), // interpolated
                expectedPutCall(1).on(false).transitionTime(tr("5min"))
        );
    }

    @Test
    void parse_interpolate_offStateAsSource_treatedAsZeroBrightness_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false");
        addState(1, now.plusMinutes(10), "bri:10", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(1),
                expectedPutCall(1).bri(10).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2)); // next day

        // turn lights on after 5 minutes

        advanceCurrentTime(Duration.ofMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).getFirst();

        powerOnRunnable.run();

        assertPutCalls(
                expectedPutCall(1).bri(5), // interpolated
                expectedPutCall(1).bri(10).transitionTime(tr("5min"))
        );
    }

    @Test
    void parse_interpolate_longDuration_offStateAsTarget_treatedAsZeroBrightness_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "ct:200");
        addState(1, now.plusHours(2), "on:false", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).ct(200),
                expectedPutCall(1).bri(17).ct(200).transitionTime(MAX_TRANSITION_TIME) // repeats unchanged ct, no "on:false"
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).on(false).transitionTime(tr("20min"))
        );
    }

    @Test
    void parse_interpolate_offState_withAdditionalCTProperty_interpolates() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "ct:200");
        addState(1, now.plusHours(2), "on:false", "ct:500", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).ct(200),
                expectedPutCall(1).bri(17).ct(450).transitionTime(MAX_TRANSITION_TIME)
        );

        List<ScheduledRunnable> followUpRunnables = ensureScheduledStates(
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );

        advanceTimeAndRunAndAssertPutCalls(followUpRunnables.getFirst(),
                expectedPutCall(1).on(false).ct(500).transitionTime(tr("20min"))
        );
    }

    @Test
    void parse_offState_noTransition_noFullPictureUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false");
        addState(1, now.plusMinutes(10), "bri:100", "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).on(false)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    @Test
    void parse_offState_withTransition_stillNoFullPicture() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "tr:5s", "force:true");
        addState(1, now.plusMinutes(10), "bri:100", "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).on(false).transitionTime(tr("5s"))
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).on(false).transitionTime(tr("5s"))
        );
    }

    @Test
    void parse_offState_zeroTransition_noFullPictureUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "tr:0");
        addState(1, now.plusMinutes(10), "bri:100", "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).on(false).transitionTime(0)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day
    }

    // todo: what about on:false but with no previous property; can we still interpolate? Do we need to fetch the current state of the light? Or should we just ignore it?

    @Test
    void parse_interpolate_noCTAtTarget_stillIncludedInSplitCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:10", "ct:200");
        addState(1, now.plusHours(2), "bri:100", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(10).ct(200),
                expectedPutCall(1).bri(85).ct(200).transitionTime(MAX_TRANSITION_TIME) // still include unchanged; don't perform optimzations
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plus(MAX_TRANSITION_TIME_MS, ChronoUnit.MILLIS), initialNow.plusDays(1)), // next split
                expectedRunnable(initialNow.plusDays(1), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnExactlyAtTrBeforeStart_performsNoInterpolation_setsExactPreviousStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_noInterpolationWhenPreviousStatesSetCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");
        addState(1, now.plusMinutes(60), "bri:" + (DEFAULT_BRIGHTNESS + 30), "ct:" + (DEFAULT_CT + 30), "tr-before:10min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable firstTrBeforeRunnable = scheduledRunnables.get(1);
        ScheduledRunnable secondTrBeforeRunnable = scheduledRunnables.get(2);

        advanceTimeAndRunAndAssertPutCalls(firstTrBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).ct(DEFAULT_CT), // sets previous state again
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("20min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(1).plusMinutes(50)); // next day

        advanceTimeAndRunAndAssertPutCalls(secondTrBeforeRunnable,
                // does not set previous state again
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT + 30).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(50), initialNow.plusDays(2)); // next day

        // after power on -> perform interpolation again

        List<ScheduledRunnable> powerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(50)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 30).ct(DEFAULT_CT + 30).transitionTime(tr("10min"))
        );
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfter_performsCorrectInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:400");
        addState(1, now.plusHours(3), "ct:200", "tr-before:30min");

        setCurrentTimeTo(now.plusHours(2).plusMinutes(45)); // 15 minutes after start

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(300),  // correct interpolated ct value
                expectedPutCall(1).ct(200).transitionTime(tr("15min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(3).minusMinutes(30), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnExactlyAtDefinedStart_noAdditionalPutCall() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(40)); // at defined start

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfterStart_performsInterpolation_andMakesAdditionalPut() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 10),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_groupStates_lightTurnedOnAfterStart_performsInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        mockGroupLightsForId(5, 7, 8, 9);
        mockGroupCapabilities(5,
                LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.COLOR_TEMPERATURE)));
        addState("g5", now, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState("g5", now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30)); // 10 minutes after start

        runAndAssertGroupPutCalls(trBeforeRunnable,
                expectedGroupPutCall(5).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 10),
                expectedGroupPutCall(5).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_ct_lightTurnedOnAfterStart_correctRounding() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(32).plusSeconds(18));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 12).ct(DEFAULT_CT + 12),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(4620)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_noCtAtTarget_onlyInterpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_interpolate_rgb_xy() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "color:#558af3");
        addState(1, now.plusMinutes(40), "x:0.2108", "y:0.2496", "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(229).x(0.2062).y(0.2171),
                expectedPutCall(1).x(0.2108).y(0.2496).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_longDuration_multipleStates_multipleProperty_previousStateHasNoBrightness_noLongTransition_noStartAdjustment() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:166", "x:0.4", "y:0.5", "tr:1"); // 00:00-12:00
        addState(1, "12:00", "bri:" + DEFAULT_BRIGHTNESS, "tr-before:00:00"); // 12:00 -> no overlapping properties, not start adjustment

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(12)),
                expectedRunnable(now.plusHours(12), now.plusDays(1))
        );
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        advanceCurrentTime(Duration.ofMinutes(30));

        runAndAssertPutCalls(firstState,
                expectedPutCall(1).bri(50).ct(166).x(0.4).y(0.5).transitionTime(1) // full picture on intial startup
        );

        ScheduledRunnable firstStateNextDay = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(secondState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2));

        advanceTimeAndRunAndAssertPutCalls(firstStateNextDay,
                expectedPutCall(1).ct(166).x(0.4).y(0.5).transitionTime(1)
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(12));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_noCtAtPrevious_onlyInterpolatesBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(40), "bri:" + (DEFAULT_BRIGHTNESS + 20), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10).ct(DEFAULT_CT + 20), // full picture used
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 20).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_xAndY_performsInterpolation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "x:0.2", "y:0.2");
        addState(1, now.plusMinutes(40), "x:0.4", "y:0.4", "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).x(0.3).y(0.3),
                expectedPutCall(1).x(0.4).y(0.4).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_fromTheSameDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(35), "ct:" + (DEFAULT_CT - 10));
        addState(1, now, "ct:" + DEFAULT_CT); // should be picked as previous state
        addState(1, now.plusMinutes(40), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(3);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);
        assertScheduleStart(trBeforeRunnable, now.plusMinutes(20), now.plusDays(1).minusMinutes(35));

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(DEFAULT_CT + 10),
                expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(6000)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2).minusMinutes(35));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_fromTheDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(35), "ct:" + (DEFAULT_CT - 15));
        addState(1, now.minusMinutes(10), "ct:" + (DEFAULT_CT - 10), "days:DO");
        addState(1, now.minusMinutes(5), "ct:" + DEFAULT_CT); // this should be picked as previous state
        addState(1, now.plusMinutes(40), "ct:" + (DEFAULT_CT + 20), "tr-before:20min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(4);
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.get(1);
        assertScheduleStart(trBeforeRunnable, now.plusMinutes(20), now.plusDays(1).minusMinutes(35));

        setCurrentTimeTo(now.plusMinutes(30));

        runAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(DEFAULT_CT + 10),
                expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(6000)
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2).minusMinutes(35));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_correctlyDetectsPreviousState_currentStateStartedTheDayBefore() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.minusMinutes(50), "ct:" + (DEFAULT_CT - 15)); // 23:10
        addState(1, now.minusMinutes(40), "ct:" + DEFAULT_CT); // 23:20, should be picked as previous state
        addState(1, now, "ct:" + (DEFAULT_CT + 20), "tr-before:20min"); // 23:40
        setCurrentAndInitialTimeTo(now.minusMinutes(10)); // 23:50

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusMinutes(40)),
                expectedRunnable(now.plusDays(1).minusMinutes(40), now.plusDays(1).minusMinutes(30)),
                expectedRunnable(now.plusDays(1).minusMinutes(30), now.plusDays(1).minusMinutes(10))
        );
        ScheduledRunnable trBeforeRunnable = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(trBeforeRunnable,
                expectedPutCall(1).ct(DEFAULT_CT + 10),
                expectedPutCall(1).ct(DEFAULT_CT + 20).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).minusMinutes(10), initialNow.plusDays(2).minusMinutes(40));
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_usesFullPictureForInterpolation_alsoOnInitialStartup() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "ct:200");
        addState(1, now.plusMinutes(10), "bri:200", "tr-before:5min"); // tr-before ignored, since no overlapping properties
        addState(1, now.plusMinutes(30), "ct:250", "tr-before:10min"); // uses ct from first state for interpolation

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        // first state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(200).ct(200) // full picture on initial startup
        );

        ScheduledRunnable firstNextDay = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10)); // next day

        // power on: uses full picture

        List<ScheduledRunnable> firstPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(now.plusMinutes(10))
        );

        advanceTimeAndRunAndAssertPutCalls(firstPowerOnRunnables.getFirst(),
                expectedPutCall(1).bri(200).ct(200)
        );

        // second state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(200) // no full picture anymore
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(1).plusMinutes(20)); // next day

        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(10)),
                expectedPowerOnEnd(initialNow.plusMinutes(20))
        );

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(1),
                expectedPutCall(1).ct(200).bri(200)
        );

        // third state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(2),
                expectedPutCall(1).ct(250).transitionTime(tr("10min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(20), initialNow.plusDays(2)); // next day

        // power-on directly at start: uses previous state

        List<ScheduledRunnable> thirdPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(20)),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(thirdPowerOnRunnables.get(1),
                expectedPutCall(1).ct(200).bri(200),
                expectedPutCall(1).ct(250).transitionTime(tr("10min"))
        );

        // power-on five minutes after: performs interpolation

        advanceCurrentTime(Duration.ofMinutes(5));

        List<ScheduledRunnable> fourthPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(fourthPowerOnRunnables.getFirst(),
                expectedPutCall(1).ct(225).bri(200),
                expectedPutCall(1).ct(250).transitionTime(tr("5min"))
        );

        // first next day

        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).ct(200) // no full picture
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)); // next day
    }

    @Test
    void parse_transitionTimeBefore_multipleStates_fullPicture_gradient_doesSkipInterpolatedUpdateIfNotChanged() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "gradient:[xy(0.2 0.2),xy(0.4 0.4)]");
        addState(1, now.plusMinutes(10), "bri:200", "tr-before:5min");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        // first state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).gradient(Gradient.builder()
                                                             .points(List.of(
                                                                     Pair.of(0.2, 0.2),
                                                                     Pair.of(0.4, 0.4)
                                                             ))
                                                             .build())
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(5)); // next day

        // second state

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                // does not repeat interpolated call from previous state
                expectedPutCall(1).bri(200).transitionTime(tr("5min"))
        );

        ensureRunnable(initialNow.plusDays(1).plusMinutes(5), initialNow.plusDays(2)); // next day

        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent(
                expectedPowerOnEnd(initialNow.plusMinutes(5)),
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(1),
                expectedPutCall(1).bri(100).gradient(Gradient.builder()
                                                             .points(List.of(
                                                                     Pair.of(0.2, 0.2),
                                                                     Pair.of(0.4, 0.4)
                                                             ))
                                                             .build()),
                expectedPutCall(1).bri(200).transitionTime(tr("5min"))
        );
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnMonday_normallyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnMonday_startsLaterInDay_endsAtEndOfDay_noTemporaryState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Mo");

        startAndGetSingleRunnable(now.plusHours(12), now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnMondayAndTuesday_correctEndAtNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now.plusHours(12), now.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_multipleStates_twoOnMondayOnly_twoOnSundayAndMonday_usesCorrectOneFromSundayAsWraparound_correctEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "07:00", "ct:" + DEFAULT_CT, "days:Mo");
        addState(1, "09:00", "ct:" + DEFAULT_CT, "days:So,Mo");
        addState(1, "10:00", "ct:" + 200, "days:So,Mo"); // cross over state
        addState(1, "14:00", "ct:" + DEFAULT_CT, "days:Mo");
        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(7)),
                expectedRunnable(now.plusHours(7), now.plusHours(9)),
                expectedRunnable(now.plusHours(9), now.plusHours(10)),
                expectedRunnable(now.plusHours(14), now.plusDays(1)) // ends at end of day
        );
        ScheduledRunnable crossOverState = scheduledRunnables.getFirst();

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).ct(200)
        );

        ensureRunnable(now.plusHours(10), now.plusHours(14)); // rescheduled cross over state
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnMondayAndTuesday_anotherStateBeforeOnTuesday_mondayStateEndsAtStartOfTuesdayOnlyState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + 153, "days:Mo, Tu");
        addState(1, "11:00", "ct:" + 200, "days:Tu");

        startScheduler(
                expectedRunnable(now.plusHours(12), now.plusDays(1).plusHours(11)),
                expectedRunnable(now.plusDays(1).plusHours(11), now.plusDays(1).plusHours(12))
        );
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnTuesday_schedulesStateNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Tu");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(12), now.plusDays(2).with(LocalTime.MIDNIGHT));
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnlyOnMonday_schedulesStateInSixDays() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");

        startAndGetSingleRunnable(now.plusDays(6), now.plusDays(7).with(LocalTime.MIDNIGHT));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnTuesdayAndWednesday_startsDuringDay_scheduledInOneDay_correctEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "11:00", "ct:" + DEFAULT_CT, "days:Tu, We");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(11), now.plusDays(2).plusHours(11));
    }

    @Test
    void parse_weekdayScheduling_todayIsWednesday_stateOnTuesdayAndWednesday_scheduledNow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.WEDNESDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Tu, We");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsWednesday_stateOnTuesdayAndWednesday_startsDuringDay_wrapAroundFromTuesdayScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.WEDNESDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Tu, We");

        ScheduledRunnable crossOverState = startAndGetSingleRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(crossOverState,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(now.plusHours(12), now.plusDays(1)); // rescheduled first state
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnMondayAndWednesday_scheduledTomorrow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo, We");

        startAndGetSingleRunnable(now.plusDays(1), now.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnMondayAndWednesday_middleOfDay_scheduledTomorrow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Mo, We");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(12), now.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsSunday_stateOnThursdayAndFriday_scheduledNextThursday() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.SUNDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Th, Fr");

        startAndGetSingleRunnable(now.plusDays(4), now.plusDays(5));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayOnly_nextDaySchedulingIsOnWeekLater() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndTuesday_scheduledNormallyOnNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureNextDayRunnable();
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndWednesday_skipToTuesday_endDefinedAtDayEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,We");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceCurrentTime(Duration.ofDays(1));

        scheduledRunnable.run(); // already ended

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(3));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_multipleStates_firstOnMondayAndWednesday_secondOnWednesdayOnly_recalculatesEndCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,We");
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:We");
        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(2).plusHours(12), now.plusDays(3))
        );

        setCurrentTimeTo(initialNow.plusDays(1)); // Tuesday

        scheduledRunnables.getFirst().run(); // already ended, state is not scheduled on Tuesday

        ScheduledRunnable nextRunnable = ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(12));

        setCurrentTimeTo(initialNow.plusDays(2).plusHours(12)); // start of second state

        nextRunnable.run();  // already ended

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_multipleStates_nextStartIsCorrectlyCalculated() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo");
        addState(1, "12:00", "ct:" + DEFAULT_CT, "days:Tu, We");
        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1).plusHours(12), now.plusDays(2).plusHours(12))
        );

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(11)); // Tuesday, AM

        scheduledRunnables.getFirst().run(); // already ended, state is not scheduled on Tuesday; schedule next week

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_invalidDayParameter_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow(1, "ct:" + DEFAULT_CT, "days:INVALID"));
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_twoLetterEnglish() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Tu,We,Th,Fr,Sa,Su");
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_threeLetterEnglish() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mon,Tue,Wen,Wed,Thu,Fri,Sat,Sun");
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_twoLetterGerman() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + DEFAULT_CT, "days:Mo,Di,Mi,Do,Fr,Sa,So");
    }

    @Test
    void parse_canHandleBrightnessInPercent_minValue() {
        assertAppliedBrightness("1%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_trims() {
        assertAppliedBrightness("1 %", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_zeroPercentage_specialHandling_alsoReturnsMinValue() {
        assertAppliedBrightness("0%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_halfPercentage_specialHandling_alsoReturnsMinValue() {
        assertAppliedBrightness("0.5%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_negativePercentage_specialHandling_alsoReturnsMinValue() {
        assertAppliedBrightness("-0.5%", 1);
    }

    @Test
    void parse_canHandleBrightnessInPercent_tooHigh_specialHandling_returnsMaxValue() {
        assertAppliedBrightness("100.5%", 254);
    }

    @Test
    void parse_canHandleBrightnessInPercent_alsoDoubleValues() {
        assertAppliedBrightness("1.5%", 2);
    }

    @Test
    void parse_canHandleBrightnessInPercent_alsoDoubleValues2() {
        assertAppliedBrightness("10.89%", 26);
    }

    @Test
    void parse_canHandleBrightnessInPercent_maxValue() {
        assertAppliedBrightness("100%", 254);
    }

    private void assertAppliedBrightness(String input, int expected) {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "bri:" + input);

        ScheduledRunnable runnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).bri(expected)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_appliesGamutCorrection() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "x:0.8", "y:0.2");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).x(0.6915).y(0.3083)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_forGroups() {
        mockGroupLightsForId(1, ID);
        double x = 0.4;
        double y = 0.3;
        mockDefaultGroupCapabilities(1);
        addStateNow("g1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnable,
                expectedGroupPutCall(ID).x(x).y(y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color: #5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 125;
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(bri).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 100;
        addStateNow("1", "bri:" + customBrightness, "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color: 94, 186, 125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(125).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_okhcl_setsXYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color:oklch(0.7 0.1 250)");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(178).x(0.2323).y(0.2499)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_unknownColor_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "color:UNKNWON(94 186)"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid color value 'UNKNWON(94 186)'");
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 200;
        addStateNow("1", "bri:" + customBrightness, "color:94,186,125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).bri(customBrightness).x(DEFAULT_X).y(DEFAULT_Y)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_invalidFormat_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "gradient:rgb(94 186 125),rgb(94 186 125)"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid gradient")
                .hasMessageContaining("Expected: gradient:[");
    }

    @Test
    void parse_gradient_rgb_justOnePoint_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186 125)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid gradient")
                .hasMessageContaining("A gradient must contain at least two colors.");
    }

    @Test
    void parse_gradient_rgb_twoManyPoints_exception() {
        mockLightCapabilities(1, LightCapabilities.builder()
                                                  .maxGradientPoints(2)
                                                  .capabilities(EnumSet.of(Capability.GRADIENT)));

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186 125),rgb(94 186 125),rgb(94 186 125)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid gradient")
                .hasMessageContaining("The maximum number of gradient points for this light is 2.");
    }

    @Test
    void parse_gradient_rgb_twoPoints_missingRgbValue_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186),rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid RGB value 'rgb(94 186)'");
    }

    @Test
    void parse_gradient_twoPoints_missingClosingBraces_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186 125,rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid color value");
        assertThatThrownBy(() -> addStateNow("1", "gradient:[xy(0.1 0.2,rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid color value");
        assertThatThrownBy(() -> addStateNow("1", "gradient:[oklch(0.6988 0.1235 257.98,rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid color value");
    }

    @Test
    void parse_gradient_doesNotSupportGradient_exception() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.noneOf(Capability.class)));

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186 125),rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageContaining("does not support setting gradient");
    }

    @Test
    void parse_gradient_withAutoFill_doesNotSupportGradient_exception() {
        autoFillGradient = true;
        create();
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.noneOf(Capability.class)));

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186 125),rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageContaining("does not support setting gradient");
    }

    @Test
    void parse_gradient_withEffect_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "effect:candle", "gradient:[rgb(94 186 125),rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("When setting a gradient, no other color properties");
    }

    @Test
    void parse_gradient_withXY_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "x:0.4", "y:0.4", "gradient:[rgb(94 186 125),rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("When setting a gradient, no other color properties");
    }

    @Test
    void parse_gradient_withCT_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "ct:200", "gradient:[rgb(94 186 125),rgb(200 100 50)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("When setting a gradient, no other color properties");
    }

    @Test
    void parse_gradient_xy_missingY_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "gradient:[xy(0.1 0.2),xy(0.1)]"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Invalid xy value 'xy(0.1)'");
    }

    @Test
    void parse_gradient_rgb_twoPoints() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[rgb( 94 186 125 ), rgb(200 100 50)]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2862, 0.4311),
                                                             Pair.of(0.5148, 0.3845)
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgb_twoPoints_withMode_correctlyParsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[rgb(94 186 125),rgb(200 100 50)]@random_pixelated");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2862, 0.4311),
                                                             Pair.of(0.5148, 0.3845)
                                                     ))
                                                     .mode("random_pixelated")
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgb_unknownMode_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186 125),rgb(200 100 50)]@UNKNOWN"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Unsupported gradient mode: 'UNKNOWN'");
    }

    @Test
    void parse_gradient_rgb_hasNoAvailableModesDefined_exception() {
        mockLightCapabilities(1, LightCapabilities.builder()
                                                  .maxGradientPoints(5)
                                                  .gradientModes(null)
                                                  .capabilities(EnumSet.of(Capability.GRADIENT)));

        assertThatThrownBy(() -> addStateNow("1", "gradient:[rgb(94 186 125),rgb(200 100 50)]@random_pixelated"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageStartingWith("Unsupported gradient mode: 'random_pixelated'");
    }

    @Test
    void parse_gradient_rgb_twoPoints_autoFillTrue_extendsToFivePoints() {
        autoFillGradient = true;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[rgb(94 186 125),rgb(200 100 50)]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2862, 0.4311),
                                                             Pair.of(0.3581, 0.4808),
                                                             Pair.of(0.441, 0.4843),
                                                             Pair.of(0.5017, 0.4448),
                                                             Pair.of(0.5148, 0.3845)
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgb_threePoints_autoFill_doesNothing_keepsOriginalPoints() {
        autoFillGradient = true;
        create();
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[xy(0.2 0.2),xy(0.3 0.3),xy(0.4 0.4)]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2, 0.2),
                                                             Pair.of(0.3, 0.3),
                                                             Pair.of(0.4, 0.4)
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgb_twoPoints_maxPoints() {
        mockLightCapabilities(1, LightCapabilities.builder()
                                                  .maxGradientPoints(2)
                                                  .capabilities(EnumSet.of(Capability.GRADIENT)));
        addStateNow("1", "gradient:[xy(0.2 0.2),xy(0.3 0.3)]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2, 0.2),
                                                             Pair.of(0.3, 0.3)
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgbAndHex_twoPoints_canHandleSpaces() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[ #5eba7d, rgb(150 90 77) ]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2862, 0.4311),
                                                             Pair.of(0.4311, 0.3516)
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgbAndXY_parsedCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[rgb(94 186 125),xy( 0.5148 0.3845)]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2862, 0.4311),
                                                             Pair.of(0.5148, 0.3845)
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgbAndXY_appliesGamutCorrection() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[rgb(94 186 125),xy(0.8 0.2)]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2862, 0.4311),
                                                             Pair.of(0.6915, 0.3083) // gamut corrected
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_gradient_rgbAndOklch_twoPoints() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "gradient:[rgb(94 186 125),oklch(0.6988 0.1235 257.98)]");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.2862, 0.4311),
                                                             Pair.of(0.2232, 0.2264)
                                                     ))
                                                     .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_effect_unsupportedValue_exception_listsValidValues() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder()
                                                            .effects(List.of("effect1", "effect2"))
                                                            .build());

        assertThatThrownBy(() -> addStateNow("1", "effect:INVALID"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessage("Unsupported value for effect property: 'INVALID'. Supported effects: [effect1, effect2]");
    }

    @Test
    void parse_effect_lightDoesNotSupportEffects_exception() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder().build());

        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "effect:candle"));
    }

    @Test
    void parse_canHandleEffect_supportedEffect() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder()
                                                            .effects(List.of("colorloop"))
                                                            .build());
        addStateNow(1, "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).effect(Effect.builder().effect("colorloop").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_none_doesNotCreateParameters_ct() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "ct:350", "effect:none");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(350).effect(Effect.builder().effect("none").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_withParameters_ct() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "on:true", "ct:350", "effect:candle", "tr:10s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(true).effect(Effect.builder()
                                                         .effect("candle")
                                                         .ct(350)
                                                         .build())
                                  .transitionTime(tr("10s"))
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_withParameters_xy() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "x:0.3", "y:0.4", "effect:candle");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).effect(Effect.builder()
                                                .effect("candle")
                                                .x(0.3)
                                                .y(0.4)
                                                .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_withSpeed() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "effect:candle@0.5");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).effect(Effect.builder()
                                                .effect("candle")
                                                .speed(0.5)
                                                .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_withSpeed_tooBig() {
        mockDefaultLightCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "effect:candle@1.1"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessage("Effect speed must be between 0 and 1. Provided value: 1.1");
    }

    @Test
    void parse_canHandleEffect_withSpeed_tooLow() {
        mockDefaultLightCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "effect:candle@-0.5"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessage("Effect speed must be between 0 and 1. Provided value: -0.5");
    }

    @Test
    void parse_canHandleEffect_invalidSpeed() {
        mockDefaultLightCapabilities(1);

        assertThatThrownBy(() -> addStateNow("1", "effect:candle@"))
                .isInstanceOf(InvalidPropertyValue.class)
                .hasMessageContaining("Invalid effect value");
    }

    @Test
    void parse_canHandleEffect_buildsFullPicture_reusesPreviousColorAsParameter_xy() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "effect:candle");
        addState(1, now.plusMinutes(10), "bri:100", "ct:200");
        addState(1, now.plusMinutes(20), "x:0.3", "y:0.4");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).effect(Effect.builder()
                                                         .effect("candle")
                                                         .x(0.3)
                                                         .y(0.4)
                                                         .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_buildsFullPicture_reusesPreviousColorAsParameter_ct() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "effect:candle");
        addState(1, now.plusMinutes(10), "x:0.3", "y:0.4");
        addState(1, now.plusMinutes(20), "bri:100", "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).effect(Effect.builder()
                                                         .effect("candle")
                                                         .ct(200)
                                                         .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_noneAsEffect_doesNotBuildEffectParameters_ct() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "effect:none");
        addState(1, now.plusMinutes(10), "bri:100", "x:0.3", "y:0.4");
        addState(1, now.plusMinutes(20), "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).ct(200).effect(Effect.builder().effect("none").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_buildsFullPicture_effectAlreadyHasParameters_notOverridden() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "ct:250", "effect:candle");
        addState(1, now.plusMinutes(10), "x:0.3", "y:0.4");
        addState(1, now.plusMinutes(20), "bri:100", "ct:200");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).effect(Effect.builder()
                                                         .effect("candle")
                                                         .ct(250) // not overridden
                                                         .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_buildsFullPicture_previousStateHasEffect_noProperties_continuesBuilding() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "ct:200");
        addState(1, now.plusMinutes(20), "effect:candle");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).effect(Effect.builder()
                                                         .effect("candle")
                                                         .ct(200)
                                                         .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_buildsFullPicture_previousStateHasEffect_withProperties_stopsBuilding() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "ct:200");
        addState(1, now.plusMinutes(20), "x:0.3", "y:0.4", "effect:candle");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).effect(Effect.builder()
                                                         .effect("candle")
                                                         .x(0.3)
                                                         .y(0.4)
                                                         .build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_buildsFullPicture_previousStateHasEffect_noneEffect_reused() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "ct:200", "effect:candle");
        addState(1, now.plusMinutes(20), "x:0.3", "y:0.4", "effect:none");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100).x(0.3).y(0.4).effect(Effect.builder().effect("none").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_gradient_buildsFullPicture_ignoresEffect() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "gradient:[xy(0.2 0.3), xy(0.4 0.4)]");
        addState(1, now.plusMinutes(10), "bri:200");
        addState(1, now.plusMinutes(20), "effect:candle");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(200).gradient(Gradient.builder()
                                                             .points(List.of(
                                                                     Pair.of(0.2, 0.3),
                                                                     Pair.of(0.4, 0.4)
                                                             ))
                                                             .build()

                )
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleEffect_gradient_buildsFullPicture_alreadyHasEffect_ignoresGradient() {
        mockDefaultLightCapabilities(1);
        addState(1, now, "effect:candle");
        addState(1, now.plusMinutes(10), "bri:200");
        addState(1, now.plusMinutes(20), "gradient:[xy(0.2 0.3),xy(0.4 0.4)]");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusMinutes(20)),
                expectedRunnable(now.plusMinutes(20), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(200).effect(Effect.builder().effect("candle").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_effect_group_exception() {
        mockGroupLightsForId(1, 1);
        mockGroupCapabilities(1, LightCapabilities.builder()
                                                  .effects(List.of("colorloop"))
                                                  .build());
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("g1", "effect:colorloop"));
    }

    @Test
    void parse_canHandleEffect_none() {
        mockLightCapabilities("/lights/1", LightCapabilities.builder().effects(List.of("prism")).build());
        addStateNow(1, "effect:none");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).effect(Effect.builder().effect("none").build())
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_colorInput_x_y_butLightDoesNotSupportColor_exception() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS)));
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "color:#ffbaff"));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_maxValue_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 6500;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(153)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_minValue_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 2000;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(500)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_ct_butLightDoesNotSupportCt_exception() {
        mockLightCapabilities(1, LightCapabilities.builder());
        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("1", "ct:200"));
    }

    @Test
    void parse_detectsOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "on:" + true);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(true)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_sunset_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        ZonedDateTime sunset = getSunset(now);
        ZonedDateTime nextDaySunset = getSunset(now.plusDays(1));
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(sunset.minusHours(1));
        addDefaultState(1, now); // one hour before sunset
        addState(1, "sunset", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                defaultPutCall()
        );

        ensureRunnable(nextDaySunset, initialNow.plusDays(2));
    }

    @Test
    void parse_sunrise_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1)); // 07:42:11
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2)); // 07:42:05
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureRunnable(nextDaySunrise, nextNextDaySunrise);
    }

    @Test
    void parse_sunset_singleState_callsStartTimeProvider_calculatesInitialAndNextEndCorrectly() {
        ZonedDateTime sunset = getSunset(now);
        ZonedDateTime nextDaySunset = getSunset(now.plusDays(1));
        ZonedDateTime nextNextDaySunset = getSunset(now.plusDays(2));
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(sunset);
        addState(1, "sunset", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunset);

        setCurrentTimeTo(nextDaySunset.minusMinutes(5));

        runAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ScheduledRunnable nextDayState = ensureRunnable(nextDaySunset, nextNextDaySunset);

        setCurrentTimeTo(nextNextDaySunset.minusMinutes(5));

        runAndAssertPutCalls(nextDayState,
                defaultPutCall()
        );

        ensureRunnable(nextNextDaySunset, getSunset(initialNow.plusDays(3)));
    }

    private ZonedDateTime getSunset(ZonedDateTime time) {
        return startTimeProvider.getStart("sunset", time);
    }

    @Test
    void parse_sunrise_updatesStartTimeCorrectlyIfEndingNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1)); // 07:42:11
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2)); // 07:42:05
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        setCurrentTimeTo(nextDaySunrise.minusMinutes(5));

        runAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureRunnable(nextDaySunrise, nextNextDaySunrise);
    }

    @Test
    void parse_sunrise_updatesStartTimeCorrectlyIfEndingNextDay_timeIsAfterNextStart_rescheduledImmediately() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now); // 07:42:13
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1)); // 07:42:11
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2)); // 07:42:05
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + DEFAULT_BRIGHTNESS, "ct:" + DEFAULT_CT);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        setCurrentTimeTo(sunrise.plusDays(1)); // this is after the next day sunrise, should schedule immediately again

        scheduledRunnable.run();

        ensureRunnable(now, nextNextDaySunrise);
    }

    @Test
    void parse_nullState_treatedCorrectly_notAddedAsState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "sunrise", "ct:" + DEFAULT_CT);
        addState(1, "sunrise+10");
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_nullState_interpolateProperty_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:" + DEFAULT_CT);
        addState(1, "12:00", "interpolate:true");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(12));
    }

    @Test
    void parse_nullState_trBefore_ignored() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "ct:" + DEFAULT_CT);
        addState(1, "12:00", "tr-before:12h");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusHours(12));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).ct(DEFAULT_CT)
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(12));
    }

    @Test
    void parse_nullState_backToBack_transition_doesNotAddGap_stillCorrectsTooLongDuration() {
        minTrGap = 2;
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "tr:1h20min");
        addState(1, "01:00");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusHours(1));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("1h"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void parse_nullState_backToBack_transition_correctsTooLongDuration_alsoWhenUsingSeconds() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS, "tr:1min10s"); // 10 seconds too long
        addState(1, "00:01");
        startScheduler();

        ScheduledRunnable runnable = ensureRunnable(now, now.plusMinutes(1));

        advanceTimeAndRunAndAssertPutCalls(runnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS).transitionTime(tr("1min")) // shortened
        );

        ensureRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(1));
    }

    @Test
    void nullState_createsGap_resetsModificationTracking_stillAppliedDespiteModification() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "02:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "04:00");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now.plusHours(2), now.plusHours(4))
        );

        simulateLightOnEvent(); // no waiting states
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - 10)); // manual modification

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // still applied despite manual modification
        );

        ScheduledRunnable nextDay = ensureRunnable(initialNow.plusDays(1).plusHours(2), initialNow.plusDays(1).plusHours(4)); // next day

        // next day

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - 15)); // manual modification

        advanceTimeAndRunAndAssertPutCalls(nextDay,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // still applied despite manual modification
        );

        ensureRunnable(initialNow.plusDays(2).plusHours(2), initialNow.plusDays(2).plusHours(4)); // next day
    }

    @Test
    void parse_useLampNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "gKitchen Lamp";
        mockLightIdForName(name, 2);
        mockDefaultLightCapabilities(2);
        when(mockedHueApi.getGroupIdentifierByName(name)).thenThrow(new GroupNotFoundException("Group not found"));
        addStateNow(name, "ct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_unknownLampName_exception() {
        String unknownLightName = "Unknown Light";
        when(mockedHueApi.getGroupIdentifierByName(unknownLightName)).thenThrow(new GroupNotFoundException("Group not found"));
        when(mockedHueApi.getLightIdentifierByName(unknownLightName)).thenThrow(new LightNotFoundException("Light not found"));

        assertThrows(LightNotFoundException.class, () -> addStateNow(unknownLightName, "ct:" + DEFAULT_CT));
    }

    @Test
    void parse_useGroupNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "Kitchen";
        int id = 12345;
        mockGroupIdForName(name, id);
        mockGroupLightsForId(id, 1, 2);
        mockDefaultGroupCapabilities(id);
        addStateNow(name, "ct:" + DEFAULT_CT);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_invalidBrightnessValue_tooLow_usesMinValue() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "bri:0");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).bri(1)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_invalidBrightnessValue_tooHigh_usesMaxValue() {
        mockDefaultLightCapabilities(1);
        addStateNow(1, "bri:255");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).bri(254)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_light_invalidCtValue_ctOnlyLight_tooLow_usesMinValue() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500)
                                                  .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        addStateNow(1, "ct:152");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(153)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_invalidCtValue_ctOnlyLight_tooHigh_usesMaxValue() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        addStateNow(1, "ct:501");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(ID).ct(500)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_tooWarmCtValue_mired_lightAlsoSupportsColor_convertsToXY() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.COLOR)));
        addStateNow(1, "ct:501");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).x(0.5395).y(0.4098)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_tooWarmCtValue_kelvin_lightAlsoSupportsColor_convertsToXY() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.COLOR)));
        addStateNow(1, "ct:1000");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).x(0.608).y(0.3554)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_kelvin_convertsToMired_usesCTSinceInRange() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(153).ctMax(500).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.COLOR)));
        addStateNow(1, "ct:2000");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(500)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_group_invalidCtValue_tooLow_usesMinValue() {
        mockGroupCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:99");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnable,
                expectedGroupPutCall(1).ct(100)
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_lowerThanDefault_noException() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));

        addStateNow("1", "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_alsoForGroups_lowerThanDefault_noException() {
        mockGroupCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(200).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ct_group_noMinAndMax_noValidationPerformed() {
        mockGroupCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));
        mockGroupLightsForId(1, 2, 3);

        addState("g1", now, "ct:10");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_higherThanDefault_noException() {
        mockLightCapabilities(1, LightCapabilities.builder().ctMin(100).ctMax(1000).capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE)));

        addStateNow("1", "ct:1000");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_brightness_forOnOffLight_exception() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)));

        assertThrows(BrightnessNotSupported.class, () -> addStateNow(1, "bri:250"));
    }

    @Test
    void parse_brightness_andOff_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);

        assertThrows(InvalidPropertyValue.class, () -> addStateNow(1, "on:false", "bri:100"));
    }

    @Test
    void parse_on_forOnOffLight() {
        mockLightCapabilities(1, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)));

        addStateNow(1, "on:true");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_invalidOnValue_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "on:yes"));
    }

    @Test
    void parse_invalidXAndYValue_xTooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "x:1.1", "y:0.1"));
    }

    @Test
    void parse_invalidXAndYValue_yTooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "x:0", "y:0"));
    }

    @Test
    void parse_invalidXAndY_onlyX_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "x:0.1"));
    }

    @Test
    void parse_invalidXAndY_onlyY_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "y:0.1"));
    }

    @Test
    void parse_invalidTransitionTime_tooLow_invalidProperty_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "tr:" + -1));
    }

    @Test
    void parse_invalidTransitionTime_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addStateNow("1", "tr:" + MAX_TRANSITION_TIME + 1));
    }

    @Test
    void parse_invalidPropertyValue_ct_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "ct:INVALID"));
    }

    @Test
    void parse_invalidPropertyValue_tr_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "tr:INVALIDs"));
    }

    @Test
    void parse_invalidPropertyValue_x_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "x:INVALID"));
    }

    @Test
    void parse_invalidPropertyValue_rgb_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "color:12,13"));
    }

    @Test
    void run_execution_reachable_startsAgainNextDay_repeats() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(nextDayState,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addDefaultGroupState(1, now, 1, 2, 3);
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnable,
                defaultGroupPutCall()
        );

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_groupState_controlIndividuallyFlagSet_multipleSinglePutCalls() {
        controlGroupLightsIndividually = true;
        create();
        addDefaultGroupState(10, now, 1, 2, 3);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, true, null);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall().id("/lights/1"),
                defaultPutCall().id("/lights/2"),
                defaultPutCall().id("/lights/3")
        );

        ScheduledRunnable nextDay = ensureRunnable(now.plusDays(1));

        advanceTimeAndRunAndAssertPutCalls(nextDay,
                defaultPutCall().id("/lights/1"),
                defaultPutCall().id("/lights/2"),
                defaultPutCall().id("/lights/3")
        );

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(0));
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, now, DEFAULT_BRIGHTNESS, DEFAULT_CT);
        addState(1, nextMorning, DEFAULT_BRIGHTNESS + 100, DEFAULT_CT);
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusHours(8)),
                expectedRunnable(now.plusHours(8), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(8));

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable powerOnEvent = simulateLightOnEventExpectingSingleScheduledState();

        powerOnEvent.run(); // does not call any API, as its past its end

        ensureScheduledStates(0);
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addDefaultState(1, now);
        addDefaultState(1, now.minusHours(1));
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusDays(1).minusHours(1)),
                expectedRunnable(now.plusDays(1).minusHours(1), now.plusDays(1))
        );
        ScheduledRunnable nextDayState = initialStates.get(1);

        advanceTimeAndRunAndAssertPutCalls(nextDayState,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(2).minusHours(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addDefaultState(1, now);
        addDefaultState(1, now.plusHours(1));
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusHours(1)),
                expectedRunnable(now.plusHours(1), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));

        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(1));
    }

    @Test
    void run_execution_setsStateAgainAfterPowerOnEvent() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                defaultPutCall()
        );
    }

    @Test
    void run_execution_multipleStates_stopsPowerOnEventIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(ID, secondStateStart, brightness2, ct2);
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusSeconds(10)),
                expectedRunnable(now.plusSeconds(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusSeconds(10)); // next day runnable

        setCurrentTimeTo(secondStateStart);

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable.run();  /* this aborts without any api calls, as the current state already ended */

        /* run and assert second state: */

        advanceTimeAndRunAndAssertPutCalls(initialStates.get(1),
                expectedPutCall(ID).bri(brightness2).ct(ct2)
        );

        ensureRunnable(secondStateStart.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_allStatesInTheFuture_schedulesLastStateImmediately_setsEndToFirstStateOfDay() {
        addDefaultState(1, now.plusMinutes(5));
        addDefaultState(1, now.plusMinutes(10));
        List<ScheduledRunnable> states = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)), // cross over
                expectedRunnable(now.plusMinutes(5), now.plusMinutes(10)) // first state
        );

        // run cross over state
        advanceTimeAndRunAndAssertPutCalls(states.getFirst(),
                defaultPutCall()
        );

        ensureRunnable(now.plusMinutes(10), now.plusDays(1).plusMinutes(5)); // second state, schedule after crossover

        setCurrentTimeTo(now.plusMinutes(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        powerOnRunnable.run();

        // no next day runnable, as it was just a temporary copy
    }

    @Test
    void run_execution_multipleStates_reachable_stopsRescheduleIfNextIntervallStarts() {
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusMinutes(10);
        addDefaultState(ID, secondStateStart);
        List<ScheduledRunnable> initialStates = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(initialStates.getFirst(),
                defaultPutCall()
        );

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10));

        setCurrentTimeTo(secondStateStart);
        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable); // aborts and does not call any api calls

        advanceTimeAndRunAndAssertPutCalls(nextDayRunnable,
                defaultPutCall()
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10));
    }

    @Test
    void run_execution_powerOnRunnableScheduledAfterStateIsSet() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable(); // next day

        simulateLightOnEventExpectingSingleScheduledState();
    }

    @Test
    void run_execution_triesAgainAfterPowerOn() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        runAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                defaultPutCall()
        );
    }

    @Test
    void run_execution_putApiConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        mockPutStateThrowable(new BridgeConnectionFailure("Failed test connection"));
        setCurrentTimeToAndRun(scheduledRunnable); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putInvalidApiResponse_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        mockPutStateThrowable(new ApiFailure("Invalid response"));
        setCurrentTimeToAndRun(scheduledRunnable); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_getConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        mockPutStateThrowable(new BridgeConnectionFailure("Failed test connection"));
        // fails on GET, retries
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        resetMockedApi();
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putSuccessful_triesAgainAfterPowerOn() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                defaultPutCall()
        );

        ensureNextDayRunnable();

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                defaultPutCall()
        );
    }

    @Test
    void run_execution_nullState_notExecuted_justUsedToProvideEndToPreviousState() {
        addDefaultState();
        addNullState(now.plusMinutes(5));
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(5));

        scheduledRunnable.run(); // no API calls, as already ended

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(5));
    }

    @Test
    void run_execution_nullState_allInTheFuture_stillIgnored() {
        addDefaultState(1, now.plusMinutes(5));
        addNullState(now.plusMinutes(10));
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void run_execution_off_reachable_turnedOff_noConfirm() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(false)
        );

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_off() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(false)
        );

        ensureNextDayRunnable();
    }

    @Test
    void run_execution_offState_doesNotRetryAfterPowerOn() {
        addOffState();

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(false)
        );

        ensureNextDayRunnable();

        simulateLightOnEvent();
    }

    @Test
    void run_execution_offState_interpolate_doesRetry_butOnlyUntilStateIsReached() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:100");
        addState(1, "00:30", "on:false", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(100),
                expectedPutCall(1).on(false).transitionTime(tr("30min"))
        );

        ensureRunnable(now.plusDays(1), now.plusDays(2)); // next day

        advanceCurrentTime(Duration.ofMinutes(15));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent(expectedPowerOnEnd(initialNow.plusMinutes(30))).getFirst();
    }

    @Test
    void run_execution_offState_withInvalidAdditionalProperties_doesNotRetryAfterPowerOn() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:false", "ct:200");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).ct(200).on(false)
        );

        ensureNextDayRunnable();

        simulateLightOnEvent(); // not turned off again
    }

    @Test
    void run_execution_onStateOnly_currentlyOff_doesNotFireOnEventAgainWhenSelfCausedPowerOnEvenIsDetected() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:true");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, expectedState().on(false)); // light is currently off
        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(true)
        );

        ensureNextDayRunnable();

        simulateLightOnEvent(); // self caused power-on event
    }

    @Test
    void run_execution_onStateOnly_currentlyOn_doesNotFireOnEventAgainWhenSelfCausedPowerOnEvenIsDetected() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "on:true");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        setLightStateResponse(1, expectedState().on(true)); // light is currently off
        runAndAssertPutCalls(scheduledRunnable,
                expectedPutCall(1).on(true) // we currently don't prevent a second "on" call, even if the light is already on
        );

        ensureNextDayRunnable();

        simulateLightOnEvent(); // when turned off and on again
    }

    @Test
    void run_execution_offState_putReturnedFalse_signaledOff_offStateIsNotRescheduledWhenOn_skippedAllTogether() {
        addOffState();

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        mockIsLightOff(ID, true);
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnable); // no put call
        mockIsLightOff(ID, false);

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1)); // next day

        advanceTimeAndRunAndAssertPutCalls(nextDayRunnable,
                expectedPutCall(ID).on(false)
        );

        ensureRunnable(initialNow.plusDays(2)); // next day

        // no power-on events have been scheduled

        simulateLightOnEvent();
    }

    @Test
    void run_execution_lightOffCheck_connectionFailure_retries() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "00:00", "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, "01:00", "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(2);
        ScheduledRunnable firstState = scheduledRunnables.get(0);
        ScheduledRunnable secondState = scheduledRunnables.get(1);

        // simulate light off call to fail
        when(mockedHueApi.isLightOff("/lights/1")).thenThrow(new BridgeConnectionFailure("Connection error"));

        advanceTimeAndRunAndAssertPutCalls(firstState); // no put call

        // creates retry state
        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        // reset mock and retry -> now successful
        resetMockedApi();
        mockIsLightOff("/lights/1", false);

        advanceTimeAndRunAndAssertPutCalls(retryState,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(1)); // next day
    }

    @Test
    void sceneTurnedOn_apiDoesNotReturnASceneName_noException() {
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated, with no name -> no exception
        simulateSceneWithNameActivated("/groups/1", null, "/lights/1", "/lights/2");
    }

    @Test
    void sceneTurnedOn_containsLightThatHaveSchedules_insideSceneIgnoreWindow_doesNotApplySchedules() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(2);
        addState(2, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(2, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/2", "/lights/1", "/lights/2");
        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS - 10));

        // wait a bit, but still inside ignore window
        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds - 1));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable); // no put call, as inside the ignore window of the scene turn-on

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no put calls, as state is detected as overridden by scene

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        /* simulate another power-on: now the state is applied and rescheduled for next day */
        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent("/lights/2",
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(secondPowerOnRunnables.get(1),
                expectedPutCall(2).bri(DEFAULT_BRIGHTNESS + 10)
        );
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_insideIgnoreWindow_doesNotApplyStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate synced scene activated: automatically triggers light on event
        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2");

        // additional light on event; no additional runnable created
        simulateLightOnEvent("/groups/1");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusMinutes(10))).getFirst();

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds - 1)); // inside ignore window

        runAndAssertPutCalls(powerOnRunnable); // no additional update
    }

    @Test
    void sceneTurnedOn_syncedSceneAndNormalScene_noInterpolation_correctlyDetectsOverride_skipsFollowState() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        setLightStateResponse("/lights/1", expectedState().brightness(50));
        // simulate synced scene and normal scene activated
        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2");
        simulateSceneActivated("/groups/1", "/lights/1", "/lights/2");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusMinutes(10))).getFirst();

        runAndAssertPutCalls(powerOnRunnable); // detects as scene-turn-on

        // next state -> skipped as overridden

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1));

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_affectedLightCurrentlyOff_doesNotTriggerAutomaticLightOn() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate synced scene activated with light currently off -> does not trigger light on event
        simulateSceneWithNameActivated(sceneSyncName, new AffectedId("/lights/1", false),
                new AffectedId("/lights/2", true));

        ensureScheduledStates(); // no scheduled states
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_affectedLightCurrentlyOn_triggersAutomaticLightOn() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate synced scene activated with light currently on -> triggers light on event
        simulateSceneWithNameActivated(sceneSyncName, new AffectedId("/lights/1", true),
                new AffectedId("/lights/2", false));

        ensureScheduledStates(expectedPowerOnEnd(now.plusMinutes(10))); // light on event scheduled
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_outsideIgnoreWindow_doesApplyStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate synced scene activated
        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusMinutes(10))).getFirst();

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds)); // outside ignore window

        runAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(50)
        );
    }

    @Test
    void sceneTurnedOn_syncedScene_noInterpolation_doesNotApplyStateAgain_stillResetsManualOverride_nextDayAppliedNormally() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50)
        );

        ScheduledRunnable nextDayRunnable = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();

        // second state: detected as overridden

        setLightStateResponse(1, expectedState().brightness(100));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // detected as overridden

        ensureRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)); // next day

        // simulate synced scene activated: automatically triggers light on event and resets manual override

        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        runAndAssertPutCalls(powerOnRunnable); // no additional update

        // next day state: now applied again, as override has been reset
        setLightStateResponse(1, expectedState().brightness(60));
        advanceTimeAndRunAndAssertPutCalls(nextDayRunnable,
                expectedPutCall(1).bri(50)
        );

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10)); // next day
    }

    @Test
    void sceneTurnedOn_syncedScene_withInterpolation_appliesStateAgain() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50), // interpolated
                expectedPutCall(1).bri(60).transitionTime(tr("10min"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // simulate synced scene activated
        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2");

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusDays(1))).getFirst();

        runAndAssertPutCalls(powerOnRunnable,
                // no interpolated put call; as this was already set by the scene
                expectedPutCall(1).bri(60).transitionTime(tr("10min"))  // re-applies state due to ongoing interpolation
        );
    }

    @Test
    void sceneTurnedOn_normalSceneAndSyncedScene_withInterpolation_appliesStateAgain_clearsPreviousIgnoreWindow() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:50");
        addState(1, now.plusMinutes(10), "bri:60", "interpolate:true");

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusDays(1)),
                expectedRunnable(now.plusDays(1), now.plusDays(1)) // zero length
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(50), // interpolated
                expectedPutCall(1).bri(60).transitionTime(tr("10min"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(2)) // next day
        );

        // simulate normal and synced scene activated
        simulateSceneActivated("/groups/1", "/lights/1", "/lights/2");
        simulateSyncedSceneActivated("/groups/1", "/lights/1", "/lights/2"); // -> clears ignore window of normal scene again

        ScheduledRunnable powerOnRunnable = ensureScheduledStates(expectedPowerOnEnd(initialNow.plusDays(1))).getFirst();

        runAndAssertPutCalls(powerOnRunnable,
                // no interpolated put call; as this was already set by the scene
                expectedPutCall(1).bri(60).transitionTime(tr("10min"))  // re-applies state due to ongoing interpolation
        );
    }

    @Test
    void sceneTurnedOn_containsLightOfGroupThatHasSchedule_ignoresGroup() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(2);
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 2); // group contains /lights/2
        addState("g1", now, "bri:" + DEFAULT_BRIGHTNESS);
        addState("g1", now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.getFirst(),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/1", "/lights/2");
        setLightStateResponse(2, expectedState().brightness(DEFAULT_BRIGHTNESS - 10));

        // since its a non synced scene, no direct light on event
        ensureScheduledStates();

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(now.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(powerOnRunnable); // no put call, as inside the ignore window of the scene turn-on

        advanceTimeAndRunAndAssertGroupPutCalls(scheduledRunnables.get(1)); // no put calls, as state is detected as overridden by scene

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );

        /* simulate another power-on: now the state is applied */
        List<ScheduledRunnable> secondPowerOnRunnables = simulateLightOnEvent("/groups/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(secondPowerOnRunnables.get(1),
                expectedGroupPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );
    }

    @Test
    void sceneTurnOn_containsLightThatHaveSchedules_lightTurneOnAfterIgnoreWindow_stillApplied() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/1", "/lights/2");
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS - 10));

        // wait until ignore window passed
        advanceCurrentTime(Duration.ofSeconds(5));

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/1",
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        ); // still applied

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        ); // no modification detected

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneTurnedOn_containsLightThatHaveSchedules_forceProperty_stillAppliesSchedules() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(3);
        addState(3, now, "bri:" + DEFAULT_BRIGHTNESS, "force:true");
        addState(3, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(3, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(3).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/40", "/lights/3");

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/3",
                expectedPowerOnEnd(now.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(3).bri(DEFAULT_BRIGHTNESS) // put was forced
        );
    }

    @Test
    void sceneTurnedOn_lightWasAlreadyOn_notModifiedByScene_nextStateScheduledNormally() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        advanceCurrentTime(Duration.ofMinutes(10));
        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/40", "/lights/1");

        // no light on event, light was already on; light has not been modified by scene

        // next runnable: applied normally
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS + 10)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneTurnedOn_lightWasAlreadyOn_modifiedByScene_detectsModification() {
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS));
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        advanceCurrentTime(Duration.ofMinutes(10));
        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/40", "/lights/1");
        // modifies light state
        setLightStateResponse(1, expectedState().brightness(DEFAULT_BRIGHTNESS + BRIGHTNESS_OVERRIDE_THRESHOLD));

        // next runnable: detects modification
        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.get(1)); // no put

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void sceneTurnedOn_noUserModificationTracking_stillScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:" + DEFAULT_BRIGHTNESS);
        addState(1, now.plusMinutes(10), "bri:" + (DEFAULT_BRIGHTNESS + 10));

        List<ScheduledRunnable> scheduledRunnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(scheduledRunnables.getFirst(),
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS)
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate scene activated
        simulateSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable powerOnRunnable = simulateLightOnEventExpectingSingleScheduledState(now.plusMinutes(10));

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable,
                expectedPutCall(1).bri(DEFAULT_BRIGHTNESS) // not ignored
        );
    }

    @Test
    void scheduling_overlappingGroups_ofDifferentSize_automaticallyOffsetsStatesBasedOnTheNumberOfBiggerGroups() {
        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockDefaultGroupCapabilities(3);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockGroupLightsForId(3, 5, 6); // has no states, ignored in offset calculation
        mockAssignedGroups(5, 1, 2, 3);
        mockAssignedGroups(6, 1, 2, 3);
        mockAssignedGroups(7, 1);
        addKnownLightIdsWithDefaultCapabilities(6);
        addKnownLightIdsWithDefaultCapabilities(7);
        addState("g1", now, "bri:100");
        addState("g2", now, "bri:120");
        addState(6, now, "bri:130");
        addState(7, now, "bri:140");
        addState("g1", now.plusMinutes(10), "bri:200");
        addState("g2", now.plusMinutes(10), "bri:220");
        addState(6, now.plusMinutes(10), "bri:230");
        addState(7, now.plusMinutes(10), "bri:240");

        startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // g2
                expectedRunnable(now.plusSeconds(1), now.plusMinutes(10)), // 7
                expectedRunnable(now.plusSeconds(2), now.plusMinutes(10)), // 6
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(1), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10).plusSeconds(2), now.plusDays(1))
        );
    }

    @Test
    void scheduling_overlappingGroups_wouldUseOffset_apiCallFails_usesFallbackValueOfZero() {
        mockDefaultGroupCapabilities(1);
        mockDefaultGroupCapabilities(2);
        mockGroupLightsForId(1, 5, 6, 7);
        mockGroupLightsForId(2, 5, 6);
        mockAssignedGroups(5, 1, 2);
        mockAssignedGroups(6, 1, 2);
        mockAssignedGroups(7, 1);
        addKnownLightIdsWithDefaultCapabilities(6);
        addKnownLightIdsWithDefaultCapabilities(7);
        addState("g1", now, "bri:100");
        addState("g2", now, "bri:120");
        addState("g1", now.plusMinutes(10), "bri:200");
        addState("g2", now.plusMinutes(10), "bri:220");

        when(mockedHueApi.getAssignedGroups("/lights/6")).thenThrow(new ApiFailure("Failure"));

        startScheduler(
                expectedRunnable(now, now.plusMinutes(10)), // g1
                expectedRunnable(now, now.plusMinutes(10)), // g2
                expectedRunnable(now.plusMinutes(10), now.plusDays(1)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );
    }

    // todo: test with gaps or with day scheduling, meaning we want to test cases where state definitions exists, but not for the current time

    // todo: what about if the schedule contains on:true, should we then still not apply it?

    @Test
    void requireSceneActivation_ignoresLightOnEvents_exceptForSyncedSceneActivation() {
        requireSceneActivation();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now.plusMinutes(5), "bri:100", "tr-before:5min");
        addState(1, now.plusMinutes(10), "bri:110", "tr-before:5min");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(5)),
                expectedRunnable(now.plusMinutes(5), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst());

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)) // next day
        ).getFirst();

        // case 1: turned on normally -> ignored

        ScheduledRunnable powerOnRunnable = simulateLightOnEvent("/lights/1", expectedPowerOnEnd(now.plusMinutes(5))).getFirst();

        advanceTimeAndRunAndAssertPutCalls(powerOnRunnable); // no update performed

        // case 2: turned on through normal scene -> ignored

        simulateSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable normalSceneRunnable = simulateLightOnEvent("/lights/1", expectedPowerOnEnd(now.plusMinutes(5))).getFirst();

        advanceTimeAndRunAndAssertPutCalls(normalSceneRunnable); // no update performed

        // case 3: turned on through synced scene -> apply

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(expectedPowerOnEnd(now.plusMinutes(5))).getFirst();

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable,
                expectedPutCall(1).bri(100).transitionTime(tr("5min"))
        );

        // wait until after ignore window -> light turned off and on again. Is ignored again

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds + 1));

        simulateLightOffEvent("/lights/1"); // simulate light turned off

        ScheduledRunnable powerOnRunnable2 = simulateLightOnEvent("/lights/1", expectedPowerOnEnd(initialNow.plusMinutes(5))).getFirst();

        runAndAssertPutCalls(powerOnRunnable2); // ignored again

        // also ignores second state

        advanceTimeAndRunAndAssertPutCalls(runnables.get(1));

        ScheduledRunnable secondNextDay = ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(5), initialNow.plusDays(2)) // next day
        ).getFirst();

        // synced scene turned on again -> applies second state and allows first next day to follow

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(5)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1))
        ).get(1);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable2,
                expectedPutCall(1).bri(110).transitionTime(tr("5min"))
        );

        // first next day

        advanceTimeAndRunAndAssertPutCalls(firstNextDay,
                expectedPutCall(1).bri(100).transitionTime(tr("5min"))
        );

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(5)) // next day
        );
    }

    @Test
    void requireSceneActivation_withUserModificationTracking_correctlyDetectsOverrides_stopsApplying() {
        requireSceneActivation();
        enableUserModificationTracking();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst());

        ScheduledRunnable firstNextDay = ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        ).getFirst();

        // synced scene -> tracks first state apply via scene, ignoring any light state

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(expectedPowerOnEnd(now.plusMinutes(10))).getFirst();

        setLightStateResponse(1, expectedState().brightness(200)); // ignored
        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable); // no further update needed, scene already set the state; tracks last seen state

        // light has been modified before second state -> triggers override based on last seen state tracked via scene

        setLightStateResponse(1, expectedState().brightness(100 + BRIGHTNESS_OVERRIDE_THRESHOLD));
        advanceTimeAndRunAndAssertPutCalls(runnables.get(1));

        ScheduledRunnable secondNextDay = ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        ).getFirst();

        // first state next day still skipped because of override

        advanceTimeAndRunAndAssertPutCalls(firstNextDay); // still overridden

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // activate synced scene again -> applies firstNextDay state, resetting override

        simulateSyncedSceneActivated("/groups/1", "/lights/1");

        ScheduledRunnable syncedSceneRunnable2 = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1)), // already ended
                expectedPowerOnEnd(initialNow.plusDays(1).plusMinutes(10))
        ).get(2);

        advanceTimeAndRunAndAssertPutCalls(syncedSceneRunnable2); // no additional update needed, no interpolation

        // secondNextDay applied normally

        setLightStateResponse(1, expectedState().brightness(100)); // no manual override this time
        advanceTimeAndRunAndAssertPutCalls(secondNextDay,
                expectedPutCall(1).bri(110)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(2).plusMinutes(10), initialNow.plusDays(3)) // next day
        );
    }

    @Test
    void requireSceneActivation_withForceProperty_stillApplied() {
        requireSceneActivation();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "bri:100", "force:true");
        addState(1, now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst(), expectedPutCall(1).bri(100));

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.get(1)); // no update, since no force

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void requireSceneActivation_onPhysicalOn_afterSyncedSceneTurnOn_doesNotPreventFurtherStatesToBeScheduled() {
        requireSceneActivation();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6);
        when(mockedHueApi.getAffectedIdsByDevice("/device/789")).thenReturn(List.of("/groups/1", "/lights/6"));
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertPutCalls(runnables.getFirst());

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // synced scene -> applies first state, ignoring any light state

        simulateSyncedSceneActivated("/groups/1", "/lights/5", "/lights/6");

        ScheduledRunnable syncedSceneRunnable = ensureScheduledStates(
                expectedPowerOnEnd(now.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(syncedSceneRunnable); // no additional update needed, no interpolation

        // simulate group light to be turned on physically -> keeps synced scene turn on

        advanceCurrentTime(Duration.ofSeconds(sceneActivationIgnoreWindowInSeconds)); // after scene activation window

        scheduler.getHueEventListener().onPhysicalOn("/device/789");

        ScheduledRunnable physicalPowerOnRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(physicalPowerOnRunnable,
                expectedGroupPutCall(1).bri(100)
        );

        // still allows second state to be scheduled

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1),
                expectedGroupPutCall(1).bri(110)
        );

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

    @Test
    void requireSceneActivation_onPhysicalOn_noSyncedSceneTurnedOnBefore_notApplied() {
        requireSceneActivation();
        mockDefaultGroupCapabilities(1);
        mockGroupLightsForId(1, 5, 6);
        when(mockedHueApi.getAffectedIdsByDevice("/device/789")).thenReturn(List.of("/groups/1", "/lights/6"));
        addState("g1", now, "bri:100");
        addState("g1", now.plusMinutes(10), "bri:110");

        List<ScheduledRunnable> runnables = startScheduler(
                expectedRunnable(now, now.plusMinutes(10)),
                expectedRunnable(now.plusMinutes(10), now.plusDays(1))
        );

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.getFirst());

        ensureScheduledStates(
                expectedRunnable(now.plusDays(1), now.plusDays(1).plusMinutes(10)) // next day
        );

        // simulate group light to be turned on physically -> not applied

        scheduler.getHueEventListener().onPhysicalOn("/device/789");

        ScheduledRunnable physicalPowerOnRunnable = ensureScheduledStates(
                expectedPowerOnEnd(initialNow.plusMinutes(10))
        ).getFirst();

        advanceTimeAndRunAndAssertGroupPutCalls(physicalPowerOnRunnable); // ignored

        // second state also ignored

        advanceTimeAndRunAndAssertGroupPutCalls(runnables.get(1)); // ignored

        ensureScheduledStates(
                expectedRunnable(initialNow.plusDays(1).plusMinutes(10), initialNow.plusDays(2)) // next day
        );
    }

}
