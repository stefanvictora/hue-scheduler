package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.time.InvalidStartTimeExpression;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
    private Map<Integer, List<LightState>> lightStatesForId;
    private Map<Integer, List<Integer>> groupLightsForId;
    private Map<Integer, LightCapabilities> capabilitiesForId;
    private LightCapabilities defaultCapabilities;
    private int defaultCt;
    private int defaultBrightness;
    private int id;
    private boolean apiPutReturnValue;
    private Supplier<RuntimeException> apiPutThrowable;
    private Supplier<RuntimeException> apiGetThrowable;
    private int retryDelay;
    private boolean confirmAll;
    private int confirmCount;
    private int confirmDelay;
    private String nowTimeString;
    private double defaultX;
    private double defaultY;
    private int connectionFailureRetryDelay;
    private int multiColorAdjustmentDelay;
    private StartTimeProviderImpl startTimeProvider;
    private boolean controlGroupLightsIndividually;

    private void setCurrentTimeToAndRun(ScheduledRunnable scheduledRunnable) {
        setCurrentTimeTo(scheduledRunnable);
        scheduledRunnable.run();
    }

    private void setCurrentTimeTo(ScheduledRunnable scheduledRunnable) {
        setCurrentTimeTo(scheduledRunnable.getStart());
    }

    private void setCurrentTimeTo(ZonedDateTime newTime) {
        if (now != null && newTime.isBefore(now)) {
            throw new IllegalArgumentException("New time is before now: " + newTime);
        }
        now = newTime;
        LOG.info("New time: {}", now);
    }

    private void setCurrentAndInitialTimeTo(ZonedDateTime dateTime) {
        initialNow = now = dateTime;
        LOG.info("Initial time: {}", now);
    }

    private void setupTimeWithDayOfWeek(DayOfWeek dayOfWeek) {
        setupTimeWithDayOfWeek(dayOfWeek, LocalTime.MIDNIGHT);
    }

    private void setupTimeWithDayOfWeek(DayOfWeek dayOfWeek, LocalTime time) {
        setCurrentAndInitialTimeTo(now.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time));
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
                List<LightState> lightStates = lightStatesForId.get(id);
                assertNotNull(lightStates, "No light state call expected with id " + id + "!");
                LightState lightState = lightStates.remove(0);
                if (lightStates.isEmpty()) {
                    lightStatesForId.remove(id);
                }
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
                if (lights.isEmpty())
                    throw new EmptyGroupException("Group is empty!");
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
        scheduler = new HueScheduler(hueApi, stateScheduler, startTimeProvider,
                () -> now, 10.0, controlGroupLightsIndividually, () -> retryDelay * 1000, confirmAll, confirmCount, confirmDelay,
                connectionFailureRetryDelay, multiColorAdjustmentDelay);
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

    private void assertPutState(int id, Integer bri, Integer ct, Double x, Double y, Integer putHue, Integer putSat,
                                String putEffect, Boolean on, Integer transitionTime, boolean groupState) {
        assertTrue(putStates.size() > 0, "No PUT API calls happened");
        PutState putState = putStates.remove(0);
        assertThat("ID differs", putState.id, is(id));
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

    private void runAndAssertConfirmations(Integer putBrightness, Integer putCt, boolean groupState) {
        runAndAssertConfirmations(true, true, putBrightness, putCt, null, null, null,
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
        for (int i = 0; i < confirmCount; i++) {
            ScheduledRunnable confirmRunnable = ensureConfirmRunnable();
            LOG.info("Confirming state {} [{}/{}]", id, i + 1, confirmCount);
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
        setCurrentTimeTo(state);
        runAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, putX, putY, putHue, putSat, putEffect, putOn,
                putTransitionTime, groupState);
    }

    private void runAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState, Integer putBrightness,
                                      Integer putCt, Double putX, Double putY, Integer putHue, Integer putSat, String putEffect,
                                      Boolean putOn, Integer putTransitionTime, boolean groupState) {
        addLightStateResponse(id, reachable, onState, null);
        runAndAssertPutCall(state, putBrightness, putCt, putX, putY, putHue, putSat, putEffect, putOn, putTransitionTime, groupState);
    }

    private void addLightStateResponse(int id, boolean reachable, boolean on, String effect) {
        lightStatesForId.computeIfAbsent(id, i -> new ArrayList<>()).add(new LightState(defaultBrightness, defaultCt, null, null, effect, reachable, on));
    }

    private void runAndAssertPutCall(ScheduledRunnable state, Integer putBrightness, Integer putCt, Double putX, Double putY,
                                     Integer putHue, Integer putSat, String putEffect, Boolean putOn, Integer putTransitionTime, boolean groupState) {
        state.run();

        assertPutState(id, putBrightness, putCt, putX, putY, putHue, putSat, putEffect, putOn, putTransitionTime, groupState);
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
        setCurrentTimeTo(state);
        runAndAssertPutCall(state, brightness, ct, null, null, null, null, null, on, transitionTime, groupState);
    }

    private ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart, ZonedDateTime endExclusive) {
        ScheduledRunnable state = ensureRunnable(scheduleStart);
        assertEnd(state, endExclusive);
        return state;
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

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start, ZonedDateTime endExclusive) {
        assertScheduleStart(state, start);
        assertEnd(state, endExclusive);
    }

    private void assertEnd(ScheduledRunnable state, ZonedDateTime endExclusive) {
        Duration between = Duration.between(endExclusive, state.getEnd());
        assertThat("Schedule end differs. Difference: " + between + ". Start: " + state.getStart(), state.getEnd(), is(endExclusive.minusSeconds(1)));
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start) {
        Duration between = Duration.between(start, state.getStart());
        assertThat("Schedule start differs. Difference: " + between + ". End: " + state.getEnd(), state.getStart(), is(start));
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

    private void advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(ScheduledRunnable state, Integer putBrightness,
                                                                               Integer putCt, Double putX, Double putY,
                                                                               Integer putHue, Integer putSat, String putEffect,
                                                                               Boolean putOn, Integer putTransitionTime, boolean groupState) {
        advanceTimeAndRunAndAssertApiCallsWithConfirmations(state, putBrightness, putCt, putX, putY, putHue, putSat, putEffect, putOn,
                putTransitionTime, groupState);
        ensureRunnable(initialNow.plusDays(1));
    }

    private void advanceTimeAndRunAndAssertApiCallsWithConfirmations(ScheduledRunnable state, Integer putBrightness,
                                                                     Integer putCt, Double putX, Double putY,
                                                                     Integer putHue, Integer putSat, String putEffect,
                                                                     Boolean putOn, Integer putTransitionTime, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(state, true, true, putBrightness, putCt, putX, putY,
                putHue, putSat, putEffect, putOn, putTransitionTime, groupState);

        runAndAssertConfirmations(true, true, putBrightness, putCt, putX, putY, putHue, putSat, putEffect,
                putOn, putTransitionTime, groupState);
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

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));
    }

    private ScheduledRunnable startWithDefaultState() {
        addDefaultState();
        startScheduler();

        return ensureScheduledStates(1).get(0);
    }

    private ScheduledRunnable startAndGetSingleRunnable(ZonedDateTime scheduledStart, ZonedDateTime endExclusive) {
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(scheduledStart);
        assertEnd(scheduledRunnable, endExclusive);
        return scheduledRunnable;
    }

    private ScheduledRunnable startAndGetSingleRunnable(ZonedDateTime scheduledStart) {
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, scheduledStart);
        return scheduledRunnable;
    }

    private ScheduledRunnable startAndGetSingleRunnable() {
        startScheduler();

        return ensureScheduledStates(1).get(0);
    }

    private void disableConfirms() {
        confirmAll = false;
        create();
    }

    @BeforeEach
    void setUp() {
        apiPutReturnValue = true;
        apiPutThrowable = null;
        apiGetThrowable = null;
        setCurrentAndInitialTimeTo(ZonedDateTime.of(2021, 1, 1, 0, 0, 0,
                0, ZoneId.systemDefault()));
        startTimeProvider = new StartTimeProviderImpl(new SunTimesProviderImpl(48.20, 16.39, 165));
        stateScheduler = new TestStateScheduler();
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
        confirmAll = true;
        confirmCount = 20;
        confirmDelay = 2;
        connectionFailureRetryDelay = 5;
        defaultCt = 500;
        defaultBrightness = 50;
        id = 1;
        Double[][] gamut = {{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
        defaultCapabilities = new LightCapabilities(gamut, 153, 500);
        multiColorAdjustmentDelay = 4;
        defaultX = 0.2318731647393379;
        defaultY = 0.4675382426015799;
        controlGroupLightsIndividually = false;
        create();
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

        startAndGetSingleRunnable(now);
    }

    @Test
    void run_groupState_andLightState_sameId_treatedDifferently_endIsCalculatedIndependently() {
        addGroupState(1, now, 1);
        addState(1, now);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now, now.plusDays(1));

        // group state still calls api as the groups and lamps have different end states
        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true, true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void run_singleState_inOneHour_scheduledImmediately_asAdditionalCopy_becauseOfDayWrapAround() {
        addState(22, now.plusHours(1));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_allInTheFuture_runsTheOneOfTheNextDayImmediately_asAdditionalCopy_theNextWithCorrectDelay() {
        addState(1, now.plusHours(1));
        addState(1, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusHours(2));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2), now.plusDays(1).plusHours(1));
    }

    @Test
    void run_singleState_inThePast_singleRunnableScheduled_immediately() {
        setCurrentTimeTo(now.plusHours(2));
        addState(11, now.minusHours(1));

        startAndGetSingleRunnable(now, initialNow.plusDays(1).plusHours(1));
    }

    @Test
    void run_multipleStates_sameId_differentTimes_correctlyScheduled() {
        addState(22, now);
        addState(22, now.plusHours(1));
        addState(22, now.plusHours(2));

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusHours(2));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(2), now.plusDays(1));
    }

    @Test
    void run_multipleStates_sameId_oneInTheFuture_twoInThePast_onlyOnePastAddedImmediately_theOtherOneNextDay() {
        setCurrentTimeTo(now.plusHours(3)); // 03:00
        addState(13, initialNow.plusHours(4));  // 04:00 -> scheduled in one hour
        addState(13, initialNow.plusHours(2)); // 02:00 -> scheduled immediately
        addState(13, initialNow.plusHours(1)); // 01:00 -> scheduled next day

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), initialNow.plusDays(1).plusHours(1));
        assertScheduleStart(scheduledRunnables.get(2), initialNow.plusDays(1).plusHours(1));
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
    void parse_emptyGroup_exception() {
        addKnownGroupIds(1);
        addGroupLightsForId(1);

        assertThrows(EmptyGroupException.class, () -> addStateNow("g1"));
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
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(2), now, now.plusDays(1));

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
        assertThrows(InvalidStartTimeExpression.class, () -> {
            addState("1\tct:" + defaultCt);
            startScheduler();
        });
    }

    @Test
    void parse_setsTransitionTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness, "tr:" + 5);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, defaultBrightness, null,
                null, null, null, null, null, null, 5, false);
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness, "tr:5s");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, defaultBrightness, null,
                null, null, null, null, null, null, 50, false);
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits_minutes() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness, "tr:109min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, defaultBrightness, null,
                null, null, null, null, null, null, 65400, false);
    }

    @Test
    void parse_transitionTimeBefore_shiftsGivenStartByThatTime() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime actualStart = now;
        ZonedDateTime definedStart = actualStart.plusMinutes(10);
        addState(1, definedStart, "bri:" + defaultBrightness, "tr-before:10min");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();
        assertScheduleStart(scheduledRunnable, actualStart, actualStart.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, null,
                null, null, null, null, null, null, 6000, false);

        runAndAssertConfirmations(state -> {
            setCurrentTimeTo(state);
            runAndAssertApiCalls(state, true, true, defaultBrightness, null,
                    null, null, null, null, null, null, getAdjustedTransitionTime(definedStart), false);
        });

        ensureRunnable(actualStart.plusDays(1), actualStart.plusDays(2));
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
        assertScheduleStart(scheduledRunnable, actualStart, actualStart.plusDays(1));

        advanceCurrentTime(Duration.ofMinutes(10)); // here we cross over to tomorrow

        runAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, null,
                null, null, null, null, null, null, null, false);

        runAndAssertConfirmations(state -> {
            setCurrentTimeTo(state);
            runAndAssertApiCalls(state, true, true, defaultBrightness, null,
                    null, null, null, null, null, null, null, false);
        });

        ensureRunnable(actualStart.plusDays(1), actualStart.plusDays(2));
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
            setCurrentTimeTo(state);
            runAndAssertApiCalls(state, true, true, defaultBrightness, null,
                    null, null, null, null, null, null, getAdjustedTransitionTime(definedStart), true);
        });

        ensureRunnable(actualStart.plusDays(1), actualStart.plusDays(2));
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

        ensureRunnable(actualStart.plusDays(1), actualStart.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnMonday_normallyScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Mo");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnMonday_startsLaterInDay_endsAtEndOfDay_noTemporaryState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + defaultCt, "days:Mo");

        startAndGetSingleRunnable(now.plusHours(12), now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnMondayAndTuesday_correctEndAtNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + defaultCt, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now.plusHours(12), now.plusDays(1).plusHours(12));

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, defaultCt, null,
                null, null, null, null, null,
                null, false);

        ensureRunnable(initialNow.plusDays(1).plusHours(12), initialNow.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_multipleStates_twoOnMonday_oneOnSundayAndMonday_usesOneFromSundayAsWraparound_correctEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "07:00", "ct:" + defaultCt, "days:Mo");
        addState(1, "09:00", "ct:" + defaultCt, "days:So,Mo");
        addState(1, "10:00", "ct:" + 200, "days:So,Mo");
        addState(1, "14:00", "ct:" + defaultCt, "days:Mo");
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(5);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(7));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(7), now.plusHours(9));
        assertScheduleStart(scheduledRunnables.get(2), now.plusHours(9), now.plusHours(10));
        assertScheduleStart(scheduledRunnables.get(3), now.plusHours(10), now.plusHours(14));
        assertScheduleStart(scheduledRunnables.get(4), now.plusHours(14), now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnMondayAndTuesday_anotherStateBeforeOnTuesday_mondayStateEndsAtStartOfTuesdayOnlyState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + 153, "days:Mo, Tu");
        addState(1, "11:00", "ct:" + 200, "days:Tu");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now.plusHours(12), now.plusDays(1).plusHours(11));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1).plusHours(11), now.plusDays(1).plusHours(12));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnlyOnTuesday_schedulesStateNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "12:00", "ct:" + defaultCt, "days:Tu");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(12), now.plusDays(2).with(LocalTime.MIDNIGHT));
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnlyOnMonday_schedulesStateInSixDays() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Mo");

        startAndGetSingleRunnable(now.plusDays(6), now.plusDays(7).with(LocalTime.MIDNIGHT));
    }

    @Test
    void parse_weekdayScheduling_todayIsMonday_stateOnTuesdayAndWednesday_startsDuringDay_scheduledInOneDay_correctEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addState(1, "11:00", "ct:" + defaultCt, "days:Tu, We");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(11), now.plusDays(2).plusHours(11));
    }

    @Test
    void parse_weekdayScheduling_todayIsWednesday_stateOnTuesdayAndWednesday_scheduledNow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.WEDNESDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Tu, We");

        startAndGetSingleRunnable(now, now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsWednesday_stateOnTuesdayAndWednesday_startsDuringDay_wrapAroundFromTuesdayScheduled() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.WEDNESDAY);
        addState(1, "12:00", "ct:" + defaultCt, "days:Tu, We");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);

        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(12));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(12), now.plusDays(1));
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnMondayAndWednesday_scheduledTomorrow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Mo, We");

        startAndGetSingleRunnable(now.plusDays(1), now.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsTuesday_stateOnMondayAndWednesday_middleOfDay_scheduledTomorrow() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.TUESDAY);
        addState(1, "12:00", "ct:" + defaultCt, "days:Mo, We");

        startAndGetSingleRunnable(now.plusDays(1).plusHours(12), now.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_todayIsSunday_stateOnThursdayAndFriday_scheduledNextThursday() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.SUNDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Th, Fr");

        startAndGetSingleRunnable(now.plusDays(4), now.plusDays(5));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayOnly_nextDaySchedulingIsOnWeekLater() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Mo");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, defaultCt, null,
                null, null, null, null, null, null, false);

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndTuesday_scheduledNormallyOnNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Mo,Tu");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnable, null, defaultCt, null,
                null, null, null, null, null, null, false);

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_stateOnMondayAndWednesday_skipToTuesday_endDefinedAtDayEnd() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Mo,We");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, now.plusDays(1));

        advanceCurrentTime(Duration.ofDays(1));

        scheduledRunnable.run(); // already ended

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(3));
    }

    @Test
    void parse_weekdayScheduling_execution_todayIsMonday_multipleStates_firstOnMondayAndWednesday_secondOnWednesdayOnly_recalculatesEndCorrectly() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setupTimeWithDayOfWeek(DayOfWeek.MONDAY);
        addStateNow(1, "ct:" + defaultCt, "days:Mo,We");
        addState(1, "12:00", "ct:" + defaultCt, "days:We");
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusDays(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(2).plusHours(12), now.plusDays(3));

        setCurrentTimeTo(initialNow.plusDays(1)); // Tuesday

        scheduledRunnables.get(0).run(); // already ended, state is not scheduled on Tuesday

        ScheduledRunnable nextRunnable = ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(12));

        setCurrentTimeTo(initialNow.plusDays(2).plusHours(12)); // start of second state

        nextRunnable.run();  // already ended

        ensureRunnable(initialNow.plusDays(7), initialNow.plusDays(8));
    }

    @Test
    void parse_weekdayScheduling_invalidDayParameter_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow(1, "ct:" + defaultCt, "days:INVALID"));
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_twoLetterEnglish() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + defaultCt, "days:Mo,Tu,We,Th,Fr,Sa,Su");
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_threeLetterEnglish() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + defaultCt, "days:Mon,Tue,Wen,Thu,Fri,Sat,Sun");
    }

    @Test
    void parse_weekdayScheduling_canParseAllSupportedValues_twoLetterGerman() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "ct:" + defaultCt, "days:Mo,Di,Mi,Do,Fr,Sa,So");
    }

    @Test
    void parse_canHandleColorInput_viaXAndY() {
        addKnownLightIdsWithDefaultCapabilities(1);
        double x = 0.6075;
        double y = 0.3525;
        addStateNow("1", "x:" + x, "y:" + y);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, null,
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

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, null, x, y,
                null, null, null, null, null, true);
    }

    @Test
    void parse_canHandleColorInput_viaHueAndSaturation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int hue = 65535;
        int saturation = 254;
        addStateNow("1", "hue:" + hue, "sat:" + saturation);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, null, null,
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
        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, bri, null, x, y, null,
                null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 100;
        addStateNow("1", "bri:" + customBrightness, "color:#5eba7d");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, customBrightness, null,
                defaultX, defaultY, null, null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "color: 94, 186, 125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        int bri = 94;
        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, bri, null, defaultX, defaultY,
                null, null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaDirectRGB_brightnessCanBeOverridden() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int customBrightness = 200;
        addStateNow("1", "bri:" + customBrightness, "color:94,186,125");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, customBrightness, null,
                defaultX, defaultY, null, null, null, null, null, false);
    }

    @Test
    void parse_canHandleEffect_colorLoop() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, null, null,
                null, null, null, "colorloop", null, null, false);
    }

    @Test
    void parse_canHandleEffect_colorLoop_group() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, 1);
        addStateNow("g1", "effect:colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, null, null,
                null, null, null, "colorloop", null, null, true);
    }

    @Test
    void parse_multiColorLoopEffect_group_withMultipleLights() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, 1, 2, 3, 4, 5, 6);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        addLightStateResponse(1, true, true, null);
        addLightStateResponse(2, true, true, "colorloop");
        addLightStateResponse(3, true, false, "colorloop"); // ignored, because off
        addLightStateResponse(4, true, true, null); // ignored because no support for colorloop
        addLightStateResponse(5, true, true, "colorloop");
        addLightStateResponse(6, false, false, "colorloop"); // ignored, because unreachable and off
        setCurrentTimeTo(scheduledRunnable);
        runAndAssertPutCall(scheduledRunnable, null, null, null, null, null, null, "colorloop",
                null, null, true);

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now.plusSeconds(confirmDelay));
        assertScheduleStart(scheduledRunnables.get(1), now.plusSeconds(multiColorAdjustmentDelay)); // first adjustment

        setCurrentTimeToAndRun(scheduledRunnables.get(1)); // turns off light 2

        assertPutState(2, null, null, null, null, null, null, null, false, null, false);
        List<ScheduledRunnable> round2 = ensureScheduledStates(2);
        assertScheduleStart(round2.get(0), now.plus(300, ChronoUnit.MILLIS)); // turn on again
        assertScheduleStart(round2.get(1), now.plusSeconds(multiColorAdjustmentDelay)); // next adjustment

        setCurrentTimeToAndRun(round2.get(0)); // turns on light 2

        assertPutState(2, null, null, null, null, null, null, "colorloop", true, null, false);

        setCurrentTimeToAndRun(round2.get(1)); // turns off light 5

        assertPutState(5, null, null, null, null, null, null, null, false, null, false);
        List<ScheduledRunnable> round3 = ensureScheduledStates(2);
        assertScheduleStart(round3.get(0), now.plus(300, ChronoUnit.MILLIS)); // turn on again
        assertScheduleStart(round3.get(1), now.plusSeconds(multiColorAdjustmentDelay)); // next adjustment

        setCurrentTimeToAndRun(round3.get(0)); // turns on light 5

        assertPutState(5, null, null, null, null, null, null, "colorloop", true, null, false);

        setCurrentTimeToAndRun(round3.get(1)); // next adjustment, no action needed
    }

    @Test
    void parse_multiColorLoopEffect_group_withMultipleLights_confirmDisabled_stillAdjustment() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, 1, 2);
        addStateNow("g1", "effect:multi_colorloop", "confirm:false");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        addLightStateResponse(1, true, true, null);
        addLightStateResponse(2, true, true, "colorloop");
        setCurrentTimeTo(scheduledRunnable);
        runAndAssertPutCall(scheduledRunnable, null, null, null, null, null, null, "colorloop",
                null, null, true);

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now.plusSeconds(multiColorAdjustmentDelay)); // first adjustment
        assertScheduleStart(scheduledRunnables.get(1), now.plusDays(1)); // next day

        setCurrentTimeToAndRun(scheduledRunnables.get(0)); // turns off light 2

        assertPutState(2, null, null, null, null, null, null, null, false, null, false);
        List<ScheduledRunnable> round2 = ensureScheduledStates(1);
        assertScheduleStart(round2.get(0), now.plus(300, ChronoUnit.MILLIS)); // turn on again

        setCurrentTimeToAndRun(round2.get(0)); // turns on light 2

        assertPutState(2, null, null, null, null, null, null, "colorloop", true, null, false);
    }

    @Test
    void parse_multiColorLoopEffect_justOneLightInGroup_skipsAdjustment() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, 1);
        addStateNow("g1", "effect:multi_colorloop");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, null, null,
                null, null, null, "colorloop", null, null, true);
    }

    @Test
    void parse_multiColorLoopEffect_noGroup_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidPropertyValue.class, () -> addStateNow(1, "effect:multi_colorloop"));
    }

    @Test
    void parse_canHandleEffect_none() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow(1, "effect:none");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, null, null,
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

        advanceTimeAndRunAndAssertApiCallsWithConfirmationsAndNextDay(scheduledRunnable, null, 153, null,
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
    void parse_sunset_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        ZonedDateTime sunset = getSunset(now);
        ZonedDateTime nextDaySunset = getSunset(now.plusDays(1));
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(sunset.minusHours(1));
        addState(1, now); // one hour before sunset
        addState(1, "sunset", "bri:" + defaultBrightness, "ct:" + defaultCt);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(2);
        assertScheduleStart(scheduledRunnables.get(0), now, now.plusHours(1));
        assertScheduleStart(scheduledRunnables.get(1), now.plusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true); // sunset state
        runAndAssertConfirmations();

        ensureRunnable(nextDaySunset, initialNow.plusDays(2));
    }

    @Test
    void parse_sunrise_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2));
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + defaultBrightness, "ct:" + defaultCt);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true);
        runAndAssertConfirmations();

        ensureRunnable(nextDaySunrise, nextNextDaySunrise);
    }

    @Test
    void parse_sunset_singleState_callsStartTimeProvider_calculatesInitialAndNextEndCorrectly() {
        ZonedDateTime sunset = getSunset(now);
        ZonedDateTime nextDaySunset = getSunset(now.plusDays(1));
        ZonedDateTime nextNextDaySunset = getSunset(now.plusDays(2));
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(sunset);
        addState(1, "sunset", "bri:" + defaultBrightness, "ct:" + defaultCt);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunset);

        setCurrentTimeTo(nextDaySunset.minusMinutes(5));

        runAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, defaultCt,
                null, null, null, null, null, null, null, false);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(nextNextDaySunset, getSunset(initialNow.plusDays(3)));

        setCurrentTimeTo(nextNextDaySunset.minusMinutes(5));

        runAndAssertApiCalls(nextDayState, true, true, defaultBrightness, defaultCt, null,
                null, null, null, null, null, null, false);
        runAndAssertConfirmations();

        ensureRunnable(getSunset(initialNow.plusDays(3)), getSunset(initialNow.plusDays(4)));
    }

    private ZonedDateTime getSunset(ZonedDateTime time) {
        return startTimeProvider.getStart("sunset", time);
    }

    @Test
    void parse_sunrise_updatesStartTimeCorrectlyIfEndingNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        ZonedDateTime sunrise = startTimeProvider.getStart("sunrise", now);
        ZonedDateTime nextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(1));
        ZonedDateTime nextNextDaySunrise = startTimeProvider.getStart("sunrise", now.plusDays(2));
        setCurrentAndInitialTimeTo(sunrise);
        addState(1, "sunrise", "bri:" + defaultBrightness, "ct:" + defaultCt);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable(now, nextDaySunrise);

        setCurrentTimeTo(nextDaySunrise.minusMinutes(5));

        runAndAssertApiCalls(scheduledRunnable, true, true, defaultBrightness, defaultCt,
                null, null, null, null, null, null, null, false);
        runAndAssertConfirmations();

        ensureRunnable(nextNextDaySunrise, startTimeProvider.getStart("sunrise", initialNow.plusDays(3)));
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
    void run_execution_confirmAllDisabled_noConfirmation() {
        disableConfirms();
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_confirmAllDisabled_withExplicitConfirmForState_runsConfirmations() {
        disableConfirms();
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "ct:" + defaultCt, "bri:" + defaultBrightness, "confirm:true");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_confirmAllEnabled_butDisabledForState_noConfirmation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState(1, now, "ct:" + defaultCt, "bri:" + defaultBrightness, "confirm:false");

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addGroupState(1, now, 1, 2, 3);
        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnable, true, true);
        runAndAssertConfirmations(true);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_groupState_controlIndividuallyFlagSet_multipleSinglePutCalls() {
        confirmAll = false;
        controlGroupLightsIndividually = true;
        create();
        addGroupState(10, now, 1, 2, 3);

        ScheduledRunnable scheduledRunnable = startAndGetSingleRunnable();

        addLightStateResponse(1, true, true, null);
        scheduledRunnable.run();
        assertPutState(1, defaultBrightness, defaultCt, null, null, null, null, null, null, null, false);
        assertPutState(2, defaultBrightness, defaultCt, null, null, null, null, null, null, null, false);
        assertPutState(3, defaultBrightness, defaultCt, null, null, null, null, null, null, null, false);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_twoStates_overNight_detectsEndCorrectlyAndDoesNotExecuteConfirmRunnable() {
        setCurrentAndInitialTimeTo(now.withHour(23).withMinute(0));
        ZonedDateTime nextMorning = now.plusHours(8);
        addState(1, now, defaultBrightness, defaultCt);
        addState(1, nextMorning, defaultBrightness + 100, defaultCt);
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);

        assertScheduleStart(initialStates.get(0), now, now.plusHours(8));
        assertScheduleStart(initialStates.get(1), now.plusHours(8), now.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), true);

        setCurrentTimeTo(nextMorning);

        ScheduledRunnable confirmRunnable = ensureScheduledStates(1).get(0);

        confirmRunnable.run(); // does not call any API, as its past its end

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusHours(8));
    }

    @Test
    void run_execution_twoStates_oneAlreadyPassed_runTheOneAlreadyPassedTheNextDay_correctExecution_asEndWasAdjustedCorrectlyInitially() {
        addState(1, now);
        addState(1, now.minusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        ScheduledRunnable nextDayState = initialStates.get(1);
        assertScheduleStart(initialStates.get(0), now, now.plusDays(1).minusHours(1));
        assertScheduleStart(nextDayState, now.plusDays(1).minusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(nextDayState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2).minusHours(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_twoStates_multipleRuns_updatesEndsCorrectly() {
        addState(1, now);
        addState(1, now.plusHours(1));
        startScheduler();
        List<ScheduledRunnable> initialStates = ensureScheduledStates(2);
        assertScheduleStart(initialStates.get(0), now, now.plusHours(1));
        assertScheduleStart(initialStates.get(1), now.plusHours(1), now.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(0), true);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1));

        setCurrentTimeTo(initialNow.plusDays(1).plusHours(1));

        nextDayRunnable.run(); // already past end, no api calls

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusHours(1));
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

        ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusSeconds(10));

        /* run and assert second state: */

        advanceTimeAndRunAndAssertApiCalls(initialStates.get(1), true, true, brightness2, ct2, false);

        runAndAssertConfirmations(brightness2, ct2, false);

        ensureRunnable(secondStateStart.plusDays(1), initialNow.plusDays(2));
    }

    @Test
    void run_execution_allStatesInTheFuture_schedulesLastStateImmediately_asCopy_butAbortsIfFirstStateOfDayStarts() {
        ZonedDateTime firstStart = now.plusMinutes(5);
        ZonedDateTime secondStart = now.plusMinutes(10);
        addState(1, firstStart);
        addState(1, secondStart);
        startScheduler();

        List<ScheduledRunnable> states = ensureScheduledStates(3);
        assertScheduleStart(states.get(0), now, now.plusMinutes(5));
        assertScheduleStart(states.get(1), firstStart, now.plusMinutes(10));
        assertScheduleStart(states.get(2), secondStart, now.plusDays(1).plusMinutes(5));

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

        ScheduledRunnable nextDayRunnable = ensureRunnable(initialNow.plusDays(1), initialNow.plusDays(1).plusMinutes(10));

        advanceTimeAndRunAndAssertApiCalls(nextDayRunnable, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2), initialNow.plusDays(2).plusMinutes(10));
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
        setCurrentTimeToAndRun(scheduledRunnable); // fails but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        apiPutThrowable = null;
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_putInvalidApiResponse_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        apiPutThrowable = () -> new HueApiFailure("Invalid response");
        setCurrentTimeToAndRun(scheduledRunnable); // failes but retries

        ScheduledRunnable retryState = ensureConnectionFailureRetryState();

        apiPutThrowable = null;
        runAndAssertNextDay(retryState);
    }

    @Test
    void run_execution_getConnectionFailure_retries() {
        ScheduledRunnable scheduledRunnable = startWithDefaultState();

        apiGetThrowable = () -> new BridgeConnectionFailure("Failed test connection");
        setCurrentTimeTo(scheduledRunnable);
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
