package at.sv.hue;

import at.sv.hue.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

class HueSchedulerTest {

    private static final Logger LOG = LoggerFactory.getLogger(HueSchedulerTest.class);

    private TestStateScheduler stateScheduler;
    private HueScheduler scheduler;
    private ZonedDateTime now;
    private ZonedDateTime initialNow;
    private List<PutState> putStates;
    private Set<Integer> knownGroupIds;
    private Set<Integer> knownLightIds;
    private Map<String, Integer> lightIdsForName;
    private Map<String, Integer> groupIdsForName;
    private Map<Integer, LightState> lightStatesForId;
    private Map<Integer, List<Integer>> groupLightsForId;
    private Map<Integer, LightCapabilities> capabilitiesForId;
    private LightCapabilities defaultCapabilities;
    private int defaultCt;
    private int defaultBrightness;
    private int id;
    private LocalTime sunrise;
    private LocalTime nextDaySunrise;
    private LocalTime nextNextDaysSunrises;
    private boolean apiPutReturnValue;
    private Supplier<RuntimeException> apiPutThrowable;
    private Supplier<RuntimeException> apiGetThrowable;
    private int retryDelay;
    private int confirmDelay;
    private String nowTimeString;
    private double defaultX;
    private double defaultY;
    private int connectionFailureRetryDelay;

    private void setCurrentTimeTo(ZonedDateTime newTime) {
        if (now != null && newTime.isBefore(now)) {
            throw new IllegalArgumentException("New time is before now: " + newTime);
        }
        now = newTime;
        LOG.info("New time: {}", now);
    }

    private void setCurrentAndInitialTimeTo(ZonedDateTime dateTime) {
        initialNow = dateTime;
        setCurrentTimeTo(initialNow);
    }

    private void advanceCurrentTime(Duration duration) {
        setCurrentTimeTo(now.plus(duration));
    }

    private void create() {
        HueApi hueApi = new HueApi() {
            @Override
            public LightState getLightState(int id) {
                if (apiGetThrowable != null) {
                    throw apiGetThrowable.get();
                }
                LightState lightState = lightStatesForId.remove(id);
                assertNotNull(lightState, "No light state call expected with id " + id + "!");
                return lightState;
            }

            @Override
            public boolean putState(int id, Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat,
                                    String effect, Boolean on, Integer transitionTime, boolean groupState) {
                if (apiPutThrowable != null) {
                    throw apiPutThrowable.get();
                }
                putStates.add(new PutState(id, bri, ct, x, y, hue, sat, effect, on, transitionTime, groupState));
                return apiPutReturnValue;
            }

            @Override
            public List<Integer> getGroupLights(int groupId) {
                if (apiGetThrowable != null) {
                    throw apiGetThrowable.get();
                }
                List<Integer> lights = groupLightsForId.remove(groupId);
                if (lights == null)
                    throw new GroupNotFoundException("Not lights for group with id " + groupId + " found!");
                return lights;
            }

            @Override
            public int getGroupId(String name) {
                if (apiGetThrowable != null) {
                    throw apiGetThrowable.get();
                }
                Integer id = groupIdsForName.remove(name);
                if (id == null) throw new GroupNotFoundException("Group with name '" + name + "' not found!");
                return id;
            }

            @Override
            public String getGroupName(int groupId) {
                if (apiGetThrowable != null) {
                    throw apiGetThrowable.get();
                }
                if (!knownGroupIds.contains(groupId)) {
                    throw new GroupNotFoundException("Group with id " + groupId + " not known!");
                }
                return "Group";
            }

            @Override
            public int getLightId(String name) {
                if (apiGetThrowable != null) {
                    throw apiGetThrowable.get();
                }
                Integer id = lightIdsForName.remove(name);
                if (id == null) throw new LightNotFoundException("Light with name '" + name + "' not found!");
                return id;
            }

            @Override
            public String getLightName(int id) {
                if (apiGetThrowable != null) {
                    throw apiGetThrowable.get();
                }
                if (!knownLightIds.contains(id)) {
                    throw new LightNotFoundException("Light with id " + id + " not known!");
                }
                return "Name";
            }

            @Override
            public LightCapabilities getLightCapabilities(int id) {
                if (apiGetThrowable != null) {
                    throw apiGetThrowable.get();
                }
                LightCapabilities capabilities = capabilitiesForId.get(id);
                if (capabilities == null) throw new LightNotFoundException("Light with id '" + id + "' not found!");
                return capabilities;
            }

            @Override
            public void assertConnection() {
            }
        };
        scheduler = new HueScheduler(hueApi, stateScheduler, (input, dateTime) -> {
            if (input.equals("sunrise")) {
                long daysBetween = Duration.between(initialNow, dateTime).toDays();
                if (daysBetween == 0) {
                    return sunrise;
                } else if (daysBetween == 1) {
                    return nextDaySunrise;
                } else if (daysBetween >= 2) {
                    return nextNextDaysSunrises;
                }
                throw new IllegalStateException("No sunrise time for dateTime defined: " + dateTime);
            } else if (input.equals("sunrise+10")) {
                return sunrise.plusMinutes(10);
            } else {
                return LocalTime.parse(input);
            }
        }, () -> now, () -> retryDelay * 1000, confirmDelay, connectionFailureRetryDelay);
    }

    private void addState(int id, ZonedDateTime startTime) {
        addState(id, startTime, defaultBrightness, defaultCt);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct) {
        addState(id, startTime, brightness, ct, null);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct, Boolean on) {
        addKnownLightIdsWithDefaultCapabilities(id);
        addState(id, startTime, "bri:" + brightness, "ct:" + ct, "on:" + on);
    }

    private void addGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        addKnownGroupIds(groupId);
        addGroupLightsForId(groupId, lights);
        addState("g" + groupId, start, "bri:" + defaultBrightness, "ct:" + defaultCt);
    }

    private void addStateNow(Object id, String... properties) {
        addState(id, now, properties);
    }

    private void addState(Object id, ZonedDateTime startTime, String... properties) {
        addState(id, startTime.toLocalTime().toString(), properties);
    }

    private void addState(Object id, String startTimeString, String... properties) {
        String nonNullProperties = Arrays.stream(properties)
                                         .filter(p -> !p.contains("null"))
                                         .collect(Collectors.joining("\t"));
        addState(id + "\t" + startTimeString + "\t" + nonNullProperties);
    }

    private void addState(String input) {
        scheduler.addState(input);
    }

    private void startScheduler() {
        scheduler.start();
    }

    private List<ScheduledRunnable> ensureScheduledStates(int expectedSize) {
        List<ScheduledRunnable> scheduledRunnables = stateScheduler.getScheduledStates();
        assertThat("ScheduledStates size differs", scheduledRunnables.size(), is(expectedSize));
        stateScheduler.clear();
        return scheduledRunnables;
    }

    private void assertPutState(int id, Integer bri, Double x, Double y, Integer ct, Integer putHue, Integer putSat,
                                String putEffect, Boolean on, Integer transitionTime, boolean groupState) {
        assertTrue(putStates.size() > 0, "No PUT API calls happened");
        PutState putState = putStates.remove(0);
        assertThat("Brightness differs", putState.bri, is(bri));
        assertThat("CT differs", putState.ct, is(ct));
        assertThat("X differs", putState.x, is(x));
        assertThat("Y differs", putState.y, is(y));
        assertThat("Hue differs", putState.hue, is(putHue));
        assertThat("Sat differs", putState.sat, is(putSat));
        assertThat("Effect differs", putState.effect, is(putEffect));
        assertThat("On differs", putState.on, is(on));
        assertThat("TransitionTime differs", putState.transitionTime, is(transitionTime));
        assertThat("isGroupState differs", putState.groupState, is(groupState));
    }

    private void addLightIdForName(String name, int id) {
        lightIdsForName.put(name, id);
    }

    private void addGroupLightsForId(int groupId, Integer... lights) {
        groupLightsForId.put(groupId, Arrays.asList(lights));
    }

    private void addGroupIdForName(String name, int id) {
        groupIdsForName.put(name, id);
    }

    private void runAndAssertConfirmations() {
        runAndAssertConfirmations(false);
    }

    private void runAndAssertConfirmations(boolean groupState) {
        runAndAssertConfirmations(defaultBrightness, defaultCt, groupState);
    }

    private void runAndAssertConfirmations(int brightness, int ct, boolean groupState) {
        runAndAssertConfirmations(true, true, brightness, ct, null, null, null,
                null, null, null, null, groupState);
    }

    private void runAndAssertConfirmations(boolean reachable, boolean onState, Integer putBrightness, Integer putCt,
                                           Double putX, Double putY, Integer putHue, Integer putSat, String putEffect, Boolean putOn,
                                           Integer putTransitionTime, boolean groupState) {
        runAndAssertConfirmations(state ->
                advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, putX, putY, putHue,
                        putSat, putEffect, putOn, putTransitionTime, groupState));
    }

    private void runAndAssertConfirmations(Consumer<ScheduledRunnable> repeatedState) {
        for (int i = 0; i < ScheduledState.CONFIRM_AMOUNT; i++) {
            ScheduledRunnable confirmRunnable = ensureConfirmRunnable();
            LOG.info("Confirming state {} [{}/{}]", id, i + 1, ScheduledState.CONFIRM_AMOUNT);
            repeatedState.accept(confirmRunnable);
        }
    }

    private void addDefaultState() {
        addState(id, now, defaultBrightness, defaultCt);
    }

    private void addNullState(ZonedDateTime start) {
        addState(id, start, null, null);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, false);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, true, defaultBrightness, defaultCt, groupState);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState,
                                                    Integer putBrightness, Integer putCt, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, null, null, null,
                null, null, null, null, groupState);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState, Integer putBrightness,
                                                    Integer putCt, Double putX, Double putY, Integer putHue, Integer putSat,
                                                    String putEffect, Boolean putOn, Integer putTransitionTime, boolean groupState) {
        setCurrentTimeTo(state.getStart());
        runAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, putX, putY, putHue, putSat, putEffect, putOn,
                putTransitionTime, groupState);
    }

    private void runAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState, Integer putBrightness,
                                      Integer putCt, Double putX, Double putY, Integer putHue, Integer putSat, String putEffect,
                                      Boolean putOn, Integer putTransitionTime, boolean groupState) {
        addLightStateResponse(id, reachable, onState);
        runAndAssertPutCall(state, putBrightness, putCt, putX, putY, putHue, putSat, putEffect, putOn, putTransitionTime, groupState);
    }

    private void addLightStateResponse(int id, boolean reachable, boolean on) {
        lightStatesForId.put(id, new LightState(defaultBrightness, defaultCt, null, null, reachable, on));
    }

    private void runAndAssertPutCall(ScheduledRunnable state, Integer putBrightness, Integer putCt, Double putX, Double putY,
                                     Integer putHue, Integer putSat, String putEffect, Boolean putOn, Integer putTransitionTime, boolean groupState) {
        state.run();

        assertPutState(id, putBrightness, putX, putY, putCt, putHue, putSat, putEffect, putOn, putTransitionTime, groupState);
    }

    private void advanceTimeAndRunAndAssertTurnOffApiCall(boolean reachable, ScheduledRunnable state, boolean onState) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, null, null, null, null,
                null, null, null, false, null, false);
    }

    private void advanceTimeAndRunAndAssertTurnOnApiCall(ScheduledRunnable state) {
        advanceTimeAndRunAndAssertApiCalls(state, true, true, null, null, null,
                null, null, null, null, true, null, false);
    }

    private void advanceTimeAndRunAndAssertPutCall(ScheduledRunnable state, Integer brightness, Integer ct,
                                                   boolean groupState, Boolean on, Integer transitionTime) {
        setCurrentTimeTo(state.getStart());
        runAndAssertPutCall(state, brightness, ct, null, null, null, null, null, on, transitionTime, groupState);
    }

    private ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart) {
        ScheduledRunnable state = ensureScheduledStates(1).get(0);
        assertScheduleStart(state, scheduleStart);
        return state;
    }

    private ScheduledRunnable ensureRetryState() {
        return ensureRunnable(now.plusSeconds(retryDelay));
    }

    private ScheduledRunnable ensureConnectionFailureRetryState() {
        return ensureRunnable(now.plusSeconds(connectionFailureRetryDelay));
    }

    private void ensureAndRunSingleConfirmation(boolean reachable) {
        ScheduledRunnable runnable = ensureConfirmRunnable();

        advanceTimeAndRunAndAssertApiCalls(runnable, reachable);
    }

    private ScheduledRunnable ensureConfirmRunnable() {
        return ensureRunnable(now.plusSeconds(confirmDelay));
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start) {
        Duration between = Duration.between(start, state.getStart());
        assertThat("Schedule start differs. Difference: " + between, state.getStart(), is(start));
    }

    private void addOffState() {
        addState(1, now, null, null, false);
    }

    private void addKnownLightIds(Integer... ids) {
        knownLightIds.addAll(Arrays.asList(ids));
    }

    private void addKnownLightIdsWithDefaultCapabilities(Integer... ids) {
        addKnownLightIds(ids);
        Arrays.stream(ids).forEach(this::setDefaultCapabilities);
    }

    private void addKnownGroupIds(Integer... ids) {
        knownGroupIds.addAll(Arrays.asList(ids));
    }

    private void advanceTimeAndRunAndAssertApiCallsWithConfirmations(ScheduledRunnable state, Integer putBrightness,
                                                                     Integer putCt, Double putX, Double putY,
                                                                     Integer putHue, Integer putSat, String putEffect,
                                                                     Boolean putOn, Integer putTransitionTime, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(state, true, true, putBrightness, putCt, putX, putY,
                putHue, putSat, putEffect, putOn, putTransitionTime, groupState);

        runAndAssertConfirmations(true, true, putBrightness, putCt, putX, putY, putHue, putSat, putEffect,
                putOn, putTransitionTime, groupState);
        ensureRunnable(initialNow.plusDays(1));
    }

    private void setCapabilities(int id, LightCapabilities capabilities) {
        capabilitiesForId.put(id, capabilities);
    }

    private void setDefaultCapabilities(int id) {
        setCapabilities(id, defaultCapabilities);
    }

    private void runAndAssertNextDay(ScheduledRunnable state) {
        advanceTimeAndRunAndAssertApiCalls(state, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    private ScheduledRunnable startWithDefaultState() {
        addDefaultState();
        startScheduler();

        return ensureScheduledStates(1).get(0);
    }

    private ScheduledRunnable startAndGetSingleRunnable() {
        startScheduler();

        return ensureScheduledStates(1).get(0);
    }

    @BeforeEach
    void setUp() {
        apiPutReturnValue = true;
        apiPutThrowable = null;
        apiGetThrowable = null;
        sunrise = LocalTime.of(6, 0);
        nextDaySunrise = LocalTime.of(7, 0);
        nextNextDaysSunrises = LocalTime.of(7, 15);
        stateScheduler = new TestStateScheduler();
        setCurrentAndInitialTimeTo(ZonedDateTime.of(2021, 1, 1, 0, 0, 0,
                0, ZoneId.systemDefault()));
        nowTimeString = now.toLocalTime().toString();
        putStates = new ArrayList<>();
        lightStatesForId = new HashMap<>();
        lightIdsForName = new HashMap<>();
        groupIdsForName = new HashMap<>();
        groupLightsForId = new HashMap<>();
        capabilitiesForId = new HashMap<>();
        knownGroupIds = new HashSet<>();
        knownLightIds = new HashSet<>();
        retryDelay = 1;
        confirmDelay = 2;
        connectionFailureRetryDelay = 5;
        defaultCt = 500;
        defaultBrightness = 50;
        id = 1;
        Double[][] gamut = {{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
        defaultCapabilities = new LightCapabilities(gamut, 153, 500);
        create();
        defaultX = 0.2318731647393379;
        defaultY = 0.4675382426015799;
    }

    @AfterEach
    void tearDown() {
        assertEquals(0, putStates.size(), "Not all putState calls asserted");
        assertEquals(0, lightIdsForName.size(), "Not all lightIdsForName looked up");
        assertEquals(0, groupIdsForName.size(), "Not all groupIdsForName looked up");
        assertEquals(0, lightStatesForId.size(), "Not all lightStatesForId looked up");
        assertEquals(0, groupLightsForId.size(), "Not all groupLightsForId looked up");
        ensureScheduledStates(0);
    }

    @Test
    void run_groupState_looksUpContainingLights_addsState() {
        addGroupState(9, now, 1, 2, 3);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, now);
    }

    @Test
    void run_groupState_andLightState_sameId_treatedDifferently_endIsCalculatedIndependently() {
        addGroupState(1, now, 1);
        addState(1, now);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now);

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true, true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_asAdditionalCopy_becauseOfDayWrapAround() {
        addState(22, now.plusHours(1));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_asAdditionalCopy_theNextWithCorrectDelay() {
        addState(1, now.plusHours(1));
        addState(1, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        setCurrentTimeTo(now.plusHours(2));
        addState(11, now.minusHours(1));

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, now);
    }

    @Test
    void run_multipleStates_sameId_differentTimes_correctlyScheduled() {
        addState(22, now);
        addState(22, now.plusHours(1));
        addState(22, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2));
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        setCurrentTimeTo(now.plusHours(3));
        addState(13, now.plusHours(1));
        addState(13, now.minusHours(1));
        addState(13, now.minusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(22));
    }

    @Test
    void parse_unknownLightId_exception() {
        assertThrows(LightNotFoundException.class, () -> addStateNow("1"));
    }

    @Test
    void parse_unknownGroupId_exception() {
        assertThrows(GroupNotFoundException.class, () -> addStateNow("g1"));
    }

    @Test
    void parse_parsesInputLine_createsMultipleStates_canHandleGroups() {
        int groupId = 9;
        addGroupLightsForId(groupId, 77);
        addKnownLightIdsWithDefaultCapabilities(1, 2);
        addKnownGroupIds(groupId);
        addStateNow("1, 2,g" + groupId, "bri:" + defaultBrightness, "ct:" + defaultCt);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now);
        assertScheduleStart(scheduledRunnables.get(2), now);

        runAndAssertNextDay(scheduledRunnables.get(1));
    }

    @Test
    void parse_alsoSupportsFourSpacesInsteadOfTabs() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1    " + nowTimeString + "    bri:" + defaultBrightness + "    ct:" + defaultCt);

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
        assertThrows(DateTimeParseException.class, () -> {
            addState("1\tct:" + defaultCt);
            startScheduler();
        });
    }

    @Test
    void parse_setsTransitionTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness, "tr:" + 5);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, defaultBrightness, null,
                null, null, null, null, null, null, 5, false);
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness, "tr:5s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, defaultBrightness, null,
                null, null, null, null, null, null, 50, false);
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits_minutes() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness, "tr:109min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, defaultBrightness, null,
                null, null, null, null, null, null, 65400, false);
    }

    @Test
    void parse_transitionTimeBefore_shiftsGivenStartByThatTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(10);
        addState(1, definedStart, "bri:" + defaultBrightness, "tr-before:10min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, actualStart);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, null,
                null, null, null, null, null, null, 6000, false);

        runAndAssertConfirmations(state -> {
            setCurrentTimeTo(state.getStart());
            runAndAssertApiCalls(state, true, true, defaultBrightness, null,
                    null, null, null, null, null, null, getAdjustedTransitionTime(definedStart), false);
        });

        ensureRunnable(actualStart.plusDays(1));
    }

    private Integer getAdjustedTransitionTime(ZonedDateTime start) {
        return (int) Duration.between(now, start).toMillis() / 100;
    }

    @Test
    void parse_transitionTimeBefore_overNight_doesNotOverAdjustTransitionTime_returnsNullInstead() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentTimeTo(now.withHour(23).withMinute(50));
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(5);
        addState(1, definedStart, "bri:" + defaultBrightness, "tr-before:5min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, actualStart);

        advanceCurrentTime(Duration.ofMinutes(10)); // here we cross over to tomorrow

        runAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, null,
                null, null, null, null, null, null, null, false);

        runAndAssertConfirmations(state -> {
            setCurrentTimeTo(state.getStart());
            runAndAssertApiCalls(state, true, true, defaultBrightness, null,
                    null, null, null, null, null, null, null, false);
        });

        ensureRunnable(actualStart.plusDays(1));
    }

    @Test
    void parse_transitionTimeBefore_group_lightTurnedOnLater_stillBeforeStart_transitionTimeIsShortenedToRemainingTimeBefore() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, 1);
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(10);
        addState("g1", definedStart, "bri:" + defaultBrightness, "tr-before:10min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(5));

        runAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, null,
                null, null, null, null, null, null, 3000, true);

        runAndAssertConfirmations(state -> {
            setCurrentTimeTo(state.getStart());
            runAndAssertApiCalls(state, true, true, defaultBrightness, null,
                    null, null, null, null, null, null, getAdjustedTransitionTime(definedStart), true);
        });

        ensureRunnable(actualStart.plusDays(1));
    }

    @Test
    void parse_transitionTimeBefore_lightTurnedAfterStart_beforeTransitionTimeIgnored_normalTransitionTimeUsed() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(10);
        addState(1, definedStart, "bri:" + defaultBrightness, "tr-before:10min", "tr:3s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(10));

        runAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, null,
                null, null, null, null, null, null, 30, false);

        runAndAssertConfirmations(true, true, defaultBrightness, null,
                null, null, null, null, null, null, 30, false);

        ensureRunnable(actualStart.plusDays(1));
    }

    @Test
    void parse_canHandleColorInput_viaXAndY() {
        addKnownLightIdsWithDefaultCapabilities(1);
        double x = 0.6075;
        double y = 0.3525;
        addStateNow("1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, null,
                x, y, null, null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_forGroups() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, id);
        double x = 0.5043;
        double y = 0.6079;
        addStateNow("g1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, null, x, y,
                null, null, null, null, null, true);
    }

    @Test
    void parse_canHandleColorInput_viaHueAndSaturation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int hue = 65535;
        int saturation = 254;
        addStateNow("1", "hue:" + hue, "sat:" + saturation);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, null, null,
                null, hue, saturation, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 94;
        double x = 0.2318731647393379;
        double y = 0.4675382426015799;
        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, bri, null, x, y, null,
                null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 100;
        addStateNow("1", "bri:" + customBrightness, "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, customBrightness, null,
                defaultX, defaultY, null, null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color: 94, 186, 125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 94;
        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, bri, null, defaultX, defaultY,
                null, null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 200;
        addStateNow("1", "bri:" + customBrightness, "color:94,186,125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, customBrightness, null,
                defaultX, defaultY, null, null, null, null, null, false);
    }

    @Test
    void parse_canHandleEffect_colorLoop() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, null, null,
                null, null, null, "colorloop", null, null, false);
    }

    @Test
    void parse_canHandleEffect_colorLoop_group() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, 1);
        addStateNow("g1", "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, null, null,
                null, null, null, "colorloop", null, null, true);
    }

    @Test
    void parse_canHandleEffect_none() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "effect:none");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, null, null,
                null, null, null, "none", null, null, false);
    }

    @Test
    void parse_colorInput_x_y_butLightDoesNotSupportColor_exception() {
        addKnownLightIds(1);
        setCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "color:#ffbaff"));
    }

    @Test
    void parse_colorInput_hue_butLightDoesNotSupportColor_exception() {
        addKnownLightIds(1);
        setCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "hue:200"));
    }

    @Test
    void parse_colorInput_sat_butLightDoesNotSupportColor_exception() {
        addKnownLightIds(1);
        setCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "sat:200"));
    }

    @Test
    void parse_colorInput_effect_butLightDoesNotSupportColor_exception() {
        addKnownLightIds(1);
        setCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "effect:colorloop"));
    }

    @Test
    void parse_canHandleColorTemperatureInKelvin_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 6500;
        addStateNow("1", "ct:" + kelvin);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, 153, null,
                null, null, null, null, null, null, false);
    }

    @Test
    void parse_ct_butLightDoesNotSupportCt_exception() {
        addKnownLightIds(1);
        setCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorTemperatureNotSupported.class, () -> addStateNow("1", "ct:200"));
    }

    @Test
    void parse_detectsOnProperty() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "on:" + true);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertTurnOnApiCall(scheduledRunnable);
        runAndAssertConfirmations(this::advanceTimeAndRunAndAssertTurnOnApiCall);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_sunrise_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(now.with(sunrise).minusHours(1));
        addState(1, now); // one hour before sunrise
        addState(1, "sunrise", "bri:" + defaultBrightness, "ct:" + defaultCt);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1));

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true); // sunrise state
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1).with(nextDaySunrise));
    }

    @Test
    void parse_sunrise_singleState_callsStartTimeProvider_calculatesInitialAndNextEndCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(now.with(sunrise));
        addState(1, "sunrise", "bri:" + defaultBrightness, "ct:" + defaultCt);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, now);

        setCurrentTimeTo(initialNow.plusDays(1).with(nextDaySunrise).minusMinutes(5));

        runAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, defaultCt,
                null, null, null, null, null, null, null, false);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(2).with(nextNextDaysSunrises));

        setCurrentTimeTo(initialNow.plusDays(2).with(nextNextDaysSunrises).minusMinutes(5));

        runAndAssertApiCalls(nextDayState, true, true, defaultBrightness, defaultCt, null,
                null, null, null, null, null, null, false);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(3).with(nextNextDaysSunrises));
    }

    @Test
    void parse_nullState_treatedCorrectly_notAddedAsState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, "sunrise", "ct:" + defaultCt);
        addState(1, "sunrise+10");
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_useLampNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "gKitchen Lamp";
        addLightIdForName(name, 2);
        setDefaultCapabilities(2);
        addStateNow(name, "ct:" + defaultCt);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_unknownLampName_exception() {
        assertThrows(LightNotFoundException.class, () -> addStateNow("Unknown Light", "ct:" + defaultCt));
    }

    @Test
    void parse_useGroupNameInsteadOfId_nameIsCorrectlyResolved() {
        String name = "Kitchen";
        int id = 12345;
        addGroupIdForName(name, id);
        addGroupLightsForId(id, 1, 2);
        addStateNow(name, "ct:" + defaultCt);

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_invalidBrightnessValue_tooLow_exception() {
        assertThrows(InvalidBrightnessValue.class, () -> addState(1, now, 0, null));
    }

    @Test
    void parse_invalidBrightnessValue_tooHigh_exception() {
        assertThrows(InvalidBrightnessValue.class, () -> addState(1, now, 255, null));
    }

    @Test
    void parse_invalidCtValue_tooLow_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 152));
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_lowerThanDefault_noException() {
        addKnownLightIds(1);
        setCapabilities(1, new LightCapabilities(null, 100, 200));

        addStateNow("1", "ct:100");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_ctValueValidationUsesCapabilities_higherThanDefault_noException() {
        addKnownLightIds(1);
        setCapabilities(1, new LightCapabilities(null, 100, 1000));

        addStateNow("1", "ct:1000");

        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void parse_invalidCtValue_tooHigh_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 501));
    }

    @Test
    void parse_invalidHueValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidHueValue.class, () -> addStateNow("1", "hue:" + 65536));
    }

    @Test
    void parse_invalidHueValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidHueValue.class, () -> addStateNow("1", "hue:" + -1));
    }

    @Test
    void parse_invalidSaturationValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidSaturationValue.class, () -> addStateNow("1", "sat:" + 255));
    }

    @Test
    void parse_invalidSaturationValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidSaturationValue.class, () -> addStateNow("1", "sat:" + -1));
    }

    @Test
    void parse_invalidXAndYValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "x:" + 1.1));
    }

    @Test
    void parse_invalidXAndYValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addStateNow("1", "y:" + -0.1));
    }

    @Test
    void parse_invalidTransitionTime_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addStateNow("1", "tr:" + -1));
    }

    @Test
    void parse_invalidTransitionTime_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addStateNow("1", "tr:" + 65536));
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
    void parse_invalidPropertyValue_invalidValue_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow("1", "effect:INVALID"));
    }

    @Test
    void run_execution_reachable_runsConfirmations_startsAgainNextDay_repeats() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(nextDayState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addGroupState(10, now, 1, 2, 3);
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true, true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(0));
        addState(1, now, defaultBrightness, defaultCt);
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, nextMorning, defaultBrightness + 100, defaultCt);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), true);

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable confirmRunnable = ensureScheduledStates(1).get(0);

        confirmRunnable.run(); // does not call any API, as its past its end

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addState(1, now);
        addState(1, now.minusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        ScheduledRunnable nextDayState = initialStates.get(1);
        assertScheduleStart(nextDayState, now.plusHours(23));

        advanceTimeAndRunAndAssertApiCalls(nextDayState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2).minusHours(1));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addState(1, now);
        addState(1, now.plusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        assertScheduleStart(initialStates.get(0), now);
        assertScheduleStart(initialStates.get(1), now.plusHours(1));

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), true);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));

        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_firstUnreachable_triesAgainOneSecondLater_secondTimeReachable_success() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, false);

        ScheduledRunnable retryState = ensureRetryState();

        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_multipleStates_unreachable_stopsRetryingIfNextIntervallStarts() {
        int ct2 = 400;
        int brightness2 = 254;
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusSeconds(10);
        addState(id, secondStateStart, brightness2, ct2);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), false);

        ScheduledRunnable retryState = ensureRetryState();
        setCurrentTimeTo(secondStateStart);

        retryState.run();  /* this aborts without any api calls, as the current state already ended */

        ensureRunnable(initialNow.plusDays(1));

        /* run and assert second state: */

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(1), true, true, brightness2, ct2, false);

        runAndAssertConfirmations(brightness2, ct2, false);

        ensureRunnable(secondStateStart.plusDays(1));
    }

    @Test
    void run_execution_allStatesInTheFuture_schedulesLastStateImmediately_asCopy_butAbortsIfFirstStateOfDayStarts() {
        ZonedDateTime firstStart = now.plusMinutes(5);
        ZonedDateTime secondStart = now.plusMinutes(10);
        addState(1, firstStart);
        addState(1, secondStart);
        startScheduler();

        List<ScheduledRunnable> states = ensureScheduledStates(3);
        assertScheduleStart(states.get(0), now);
        assertScheduleStart(states.get(1), firstStart);
        assertScheduleStart(states.get(2), secondStart);

        advanceTimeAndRunAndAssertApiCalls(states.get(0), true);

        ScheduledRunnable confirmRunnable = ensureConfirmRunnable();

        setCurrentTimeTo(firstStart);

        confirmRunnable.run(); // should abort, as now the first state already starts

        // no next day runnable, as it was just a temporary copy
    }

    @Test
    void run_execution_multipleStates_reachable_stopsConfirmationIfNextIntervallStarts_resetsConfirms() {
        addDefaultState();
        ZonedDateTime secondStateStart = now.plusMinutes(10);
        addState(id, secondStateStart);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), true);
        ensureAndRunSingleConfirmation(true);

        setCurrentTimeTo(secondStateStart);
        ScheduledRunnable furtherConfirmRunnable = ensureScheduledStates(1).get(0);

        furtherConfirmRunnable.run(); // aborts and does not call any api calls

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(nextDayRunnable, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_firstReachable_butDuringConfirmationUnreachableAgain_resetsConfirms() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true);
        ensureAndRunSingleConfirmation(true);
        ensureAndRunSingleConfirmation(false);

        ScheduledRunnable retryRunnable = ensureRetryState();

        runAndAssertNextDay(retryRunnable);
    }

    @Test
    void run_execution_putReturnsFalse_toSignalLightOff_butReachable_triesAgain_withoutCallingGetStatus() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        apiPutReturnValue = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, defaultBrightness, defaultCt, false, null, null);

        ScheduledRunnable retryState = ensureRetryState();

        apiPutReturnValue = true;
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putApiConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        apiPutThrowable = () -> new BridgeConnectionFailure("Failed test connection");
        setCurrentTimeTo(scheduledRunnable.getStart());
        scheduledRunnable.run(); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        apiPutThrowable = null;
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putInvalidApiResponse_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        apiPutThrowable = () -> new HueApiFailure("Invalid response");
        setCurrentTimeTo(scheduledRunnable.getStart());
        scheduledRunnable.run(); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        apiPutThrowable = null;
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_getConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        apiGetThrowable = () -> new BridgeConnectionFailure("Failed test connection");
        setCurrentTimeTo(scheduledRunnable.getStart());
        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, defaultBrightness, defaultCt, false, null, null); // fails on GET, retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        apiGetThrowable = null;
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putSuccessful_reachable_butOff_triesAgain() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true, false, defaultBrightness, defaultCt, false);

        ScheduledRunnable retryState = ensureRetryState();
        advanceTimeAndRunAndAssertApiCalls(retryState, true, true, defaultBrightness, defaultCt, false);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_notExecuted_justUsedToProvideEndToPreviousState() {
        addDefaultState();
        addNullState(now.plusMinutes(5));
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceCurrentTime(Duration.ofMinutes(5));

        scheduledRunnable.run(); // no API calls, as already ended

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_allInTheFuture_stillIgnored() {
        addState(1, now.plusMinutes(5));
        addNullState(now.plusMinutes(10));
        startScheduler();

        ensureScheduledStates(1);
    }

    @Test
    void run_execution_off_reachable_turnedOff_noConfirm() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertTurnOffApiCall(true, scheduledRunnable, false);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_unreachable_treatedAsSuccess_noConfirm() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertTurnOffApiCall(false, scheduledRunnable, true);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_putReturnsFalse_retries_secondTimeSuccess_noConfirms() {
        addOffState();
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        apiPutReturnValue = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnable, null, null, false, false, null);

        ScheduledRunnable retryState = ensureRetryState();
        apiPutReturnValue = true;
        advanceTimeAndRunAndAssertTurnOffApiCall(false, retryState, true);

        ensureRunnable(initialNow.plusDays(1));
    }

    private static final class PutState {
        int id;
        Integer bri;
        Integer ct;
        Double x;
        Double y;
        Integer hue;
        Integer sat;
        Boolean on;
        String effect;
        Integer transitionTime;
        boolean groupState;

        public PutState(int id, Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat, String effect,
                        Boolean on, Integer transitionTime, boolean groupState) {
            this.id = id;
            this.bri = bri;
            this.x = x;
            this.y = y;
            this.hue = hue;
            this.sat = sat;
            this.ct = ct;
            this.effect = effect;
            this.on = on;
            this.transitionTime = transitionTime;
            this.groupState = groupState;
        }
    }
}
