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
    private boolean apiPutSuccessful;
    private int retryDelay;
    private int confirmDelay;
    private String nowTimeString;

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
                LightState lightState = lightStatesForId.remove(id);
                assertNotNull(lightState, "No light state call expected with id " + id + "!");
                return lightState;
            }

            @Override
            public boolean putState(int id, Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat,
                                    Boolean on, Integer transitionTime, boolean groupState) {
                putStates.add(new PutState(id, bri, ct, x, y, hue, sat, on, transitionTime, groupState));
                return apiPutSuccessful;
            }

            @Override
            public List<Integer> getGroupLights(int groupId) {
                List<Integer> lights = groupLightsForId.remove(groupId);
                if (lights == null)
                    throw new GroupNotFoundException("Not lights for group with id " + groupId + " found!");
                return lights;
            }

            @Override
            public int getGroupId(String name) {
                Integer id = groupIdsForName.remove(name);
                if (id == null) throw new GroupNotFoundException("Group with name '" + name + "' not found!");
                return id;
            }

            @Override
            public String getGroupName(int groupId) {
                if (!knownGroupIds.contains(groupId)) {
                    throw new GroupNotFoundException("Group with id " + groupId + " not known!");
                }
                return "Group";
            }

            @Override
            public int getLightId(String name) {
                Integer id = lightIdsForName.remove(name);
                if (id == null) throw new LightNotFoundException("Light with name '" + name + "' not found!");
                return id;
            }

            @Override
            public String getLightName(int id) {
                if (!knownLightIds.contains(id)) {
                    throw new LightNotFoundException("Light with id " + id + " not known!");
                }
                return "Name";
            }

            @Override
            public LightCapabilities getLightCapabilities(int id) {
                LightCapabilities capabilities = capabilitiesForId.get(id);
                if (capabilities == null) throw new LightNotFoundException("Light with id '" + id + "' not found!");
                return capabilities;
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
        }, () -> now, () -> retryDelay * 1000, confirmDelay);
    }

    private void addState(int id, ZonedDateTime startTime) {
        addState(id, startTime, defaultBrightness, defaultCt);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct) {
        addState(id, startTime, brightness, ct, null);
    }

    private void addState(int id, ZonedDateTime startTime, Integer brightness, Integer ct, Boolean on) {
        scheduler.addState("Name", id, startTime.toLocalTime().toString(), brightness, ct, null, null,
                null, null, on, null, defaultCapabilities);
    }

    private void addState(String input) {
        scheduler.addState(input);
    }

    private void addGroupState(int groupId, ZonedDateTime start, Integer... lights) {
        addGroupLightsForId(groupId, lights);
        scheduler.addGroupState("Name", groupId, start.toLocalTime().toString(), defaultBrightness, defaultCt,
                null, null, null, null, null, null);
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
                                Boolean on, Integer transitionTime, boolean groupState) {
        assertTrue(putStates.size() > 0, "No PUT API calls happened");
        PutState putState = putStates.remove(0);
        assertThat("Brightness differs", putState.bri, is(bri));
        assertThat("CT differs", putState.ct, is(ct));
        assertThat("X differs", putState.x, is(x));
        assertThat("Y differs", putState.y, is(y));
        assertThat("Hue differs", putState.hue, is(putHue));
        assertThat("Sat differs", putState.sat, is(putSat));
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
                null, null, null, groupState);
    }

    private void runAndAssertConfirmations(boolean reachable, boolean onState, Integer putBrightness, Integer putCt,
                                           Double putX, Double putY, Integer putHue, Integer putSat, Boolean putOn,
                                           Integer putTransitionTime, boolean groupState) {
        runAndAssertConfirmations(state ->
                advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, putX, putY, putHue,
                        putSat, putOn, putTransitionTime, groupState));
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
                null, null, null, groupState);
    }

    private void advanceTimeAndRunAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState, Integer putBrightness,
                                                    Integer putCt, Double putX, Double putY, Integer putHue, Integer putSat,
                                                    Boolean putOn, Integer putTransitionTime, boolean groupState) {
        setCurrentTimeTo(state.getStart());
        runAndAssertApiCalls(state, reachable, onState, putBrightness, putCt, putX, putY, putHue, putSat, putOn,
                putTransitionTime, groupState);
    }

    private void runAndAssertApiCalls(ScheduledRunnable state, boolean reachable, boolean onState, Integer putBrightness,
                                      Integer putCt, Double putX, Double putY, Integer putHue, Integer putSat, Boolean putOn,
                                      Integer putTransitionTime, boolean groupState) {
        addLightStateResponse(id, reachable, onState);
        runAndAssertPutCall(state, putBrightness, putCt, putX, putY, putHue, putSat, putOn, putTransitionTime, groupState);
    }

    private void addLightStateResponse(int id, boolean reachable, boolean on) {
        lightStatesForId.put(id, new LightState(defaultBrightness, defaultCt, null, null, reachable, on));
    }

    private void runAndAssertPutCall(ScheduledRunnable state, Integer putBrightness, Integer putCt, Double putX, Double putY,
                                     Integer putHue, Integer putSat, Boolean putOn, Integer putTransitionTime, boolean groupState) {
        state.run();

        assertPutState(id, putBrightness, putX, putY, putCt, putHue, putSat, putOn, putTransitionTime, groupState);
    }

    private void advanceTimeAndRunAndAssertTurnOffApiCall(boolean reachable, ScheduledRunnable state, boolean onState) {
        advanceTimeAndRunAndAssertApiCalls(state, reachable, onState, null, null, null, null,
                null, null, false, null, false);
    }

    private void advanceTimeAndRunAndAssertTurnOnApiCall(ScheduledRunnable state) {
        advanceTimeAndRunAndAssertApiCalls(state, true, true, null, null, null,
                null, null, null, true, null, false);
    }

    private void advanceTimeAndRunAndAssertPutCall(ScheduledRunnable state, Integer brightness, Integer ct,
                                                   boolean groupState, Boolean on, Integer transitionTime) {
        setCurrentTimeTo(state.getStart());
        runAndAssertPutCall(state, brightness, ct, null, null, null, null, on, transitionTime, groupState);
    }

    private ScheduledRunnable ensureRunnable(ZonedDateTime scheduleStart) {
        ScheduledRunnable state = ensureScheduledStates(1).get(0);
        assertScheduleStart(state, scheduleStart);
        return state;
    }

    private ScheduledRunnable ensureRetryState() {
        return ensureRunnable(now.plusSeconds(retryDelay));
    }

    private void ensureAndRunSingleConfirmation(boolean reachable) {
        ScheduledRunnable runnable = ensureConfirmRunnable();

        advanceTimeAndRunAndAssertApiCalls(runnable, reachable);
    }

    private ScheduledRunnable ensureConfirmRunnable() {
        return ensureRunnable(now.plusSeconds(confirmDelay));
    }

    private void assertScheduleStart(ScheduledRunnable state, ZonedDateTime start) {
        assertThat("Schedule start differs", state.getStart(), is(start));
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
                                                                     Integer putHue, Integer putSat,
                                                                     Boolean putOn, Integer putTransitionTime, boolean groupState) {
        advanceTimeAndRunAndAssertApiCalls(state, true, true, putBrightness, putCt, putX, putY,
                putHue, putSat, putOn, putTransitionTime, groupState);

        runAndAssertConfirmations(true, true, putBrightness, putCt, putX, putY, putHue, putSat,
                putOn, putTransitionTime, groupState);

        ensureRunnable(initialNow.plusDays(1));
    }

    private void addStateNow(String name, String state) {
        addState(name + "\t" + nowTimeString + "\t" + state);
    }

    private void setCapabilities(int id, LightCapabilities capabilities) {
        capabilitiesForId.put(id, capabilities);
    }

    private void setDefaultCapabilities(int id) {
        setCapabilities(id, defaultCapabilities);
    }

    @BeforeEach
    void setUp() {
        apiPutSuccessful = true;
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
        defaultCt = 500;
        defaultBrightness = 50;
        id = 1;
        Double[][] gamut = {{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
        defaultCapabilities = new LightCapabilities(gamut, 153, 500);
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);
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
        assertThrows(LightNotFoundException.class, () -> addState("1\t12:00\ton:true"));
    }

    @Test
    void parse_unknownGroupId_exception() {
        assertThrows(GroupNotFoundException.class, () -> addState("g1\t12:00\ton:true"));
    }

    @Test
    void parse_parsesInputLine_createsMultipleStates_canHandleGroups() {
        int groupId = 9;
        addGroupLightsForId(groupId, 77);
        addKnownLightIdsWithDefaultCapabilities(1, 2);
        addKnownGroupIds(groupId);
        addState("1,2,g" + groupId + "\t" + nowTimeString + "\tbri:" + defaultBrightness + "\tct:" + defaultCt);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(3);
        assertScheduleStart(scheduledRunnables.get(0), now);
        assertScheduleStart(scheduledRunnables.get(1), now);
        assertScheduleStart(scheduledRunnables.get(2), now);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(1), true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
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
        assertThrows(UnknownStateProperty.class, () -> addState("1\t10:00\tUNKNOWN:1"));
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
        addStateNow("1", "bri:" + defaultBrightness + "\ttr:" + 5);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), defaultBrightness, null,
                null, null, null, null, null, 5, false);
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness + "\ttr:5s");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), defaultBrightness, null,
                null, null, null, null, null, 50, false);
    }

    @Test
    void parse_canParseTransitionTimeWithTimeUnits_minutes() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addStateNow("1", "bri:" + defaultBrightness + "\ttr:109min");

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), defaultBrightness, null,
                null, null, null, null, null, 65400, false);
    }

    @Test
    void parse_canHandleColorInput_viaXAndY() {
        addKnownLightIdsWithDefaultCapabilities(1);
        double x = 0.6075;
        double y = 0.3525;
        addStateNow("1", "x:" + x + "\ty:" + y);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), null, null,
                x, y, null, null, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaXAndY_forGroups() {
        addKnownGroupIds(1);
        addGroupLightsForId(1, id);
        double x = 0.5043;
        double y = 0.6079;
        addStateNow("g1", "x:" + x + "\ty:" + y);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), null, null, x, y,
                null, null, null, null, true);
    }

    @Test
    void parse_canHandleColorInput_viaHueAndSaturation() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int hue = 65535;
        int saturation = 254;
        addStateNow("1", "hue:" + hue + "\tsat:" + saturation);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), null, null, null,
                null, hue, saturation, null, null, false);
    }

    @Test
    void parse_canHandleColorInput_viaHexRGB_setsXAndYAndBrightness() {
        addKnownLightIdsWithDefaultCapabilities(1);
        String hex = "#5eba7d";
        addStateNow("1", "hex:" + hex);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        int bri = 94;
        double x = 0.2318731647393379;
        double y = 0.4675382426015799;
        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), bri, null, x, y, null,
                null, null, null, false);
    }

    @Test
    void parse_colorInput_x_y_butLightDoesNotSupportColor_exception() {
        addKnownLightIds(1);
        setCapabilities(1, LightCapabilities.NO_CAPABILITIES);
        assertThrows(ColorNotSupported.class, () -> addStateNow("1", "hex:#ffbaff"));
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
    void parse_canHandleColorTemperatureInKelvin_correctlyTranslatedToMired() {
        addKnownLightIdsWithDefaultCapabilities(1);
        int kelvin = 6500;
        addStateNow("1", "k:" + kelvin);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCallsWithConfirmations(scheduledRunnables.get(0), null, 153, null,
                null, null, null, null, null, false);
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

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOnApiCall(scheduledRunnables.get(0));
        runAndAssertConfirmations(this::advanceTimeAndRunAndAssertTurnOnApiCall);

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void parse_sunrise_callsStartTimeProvider_usesUpdatedSunriseTimeNextDay() {
        addKnownLightIdsWithDefaultCapabilities(1);
        setCurrentAndInitialTimeTo(now.with(sunrise).minusHours(1));
        addState(1, now); // one hour before sunrise
        addState("1\tsunrise\tbri:" + defaultBrightness + "\tct:" + defaultCt);

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
        addState("1\tsunrise\tbri:" + defaultBrightness + "\tct:" + defaultCt);

        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);
        assertScheduleStart(scheduledRunnables.get(0), now);

        setCurrentTimeTo(initialNow.plusDays(1).with(nextDaySunrise).minusMinutes(5));

        runAndAssertApiCalls(scheduledRunnables.get(0), true, true, defaultBrightness, defaultCt,
                null, null, null, null, null, null, false);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(2).with(nextNextDaysSunrises));

        setCurrentTimeTo(initialNow.plusDays(2).with(nextNextDaysSunrises).minusMinutes(5));

        runAndAssertApiCalls(nextDayState, true, true, defaultBrightness, defaultCt, null,
                null, null, null, null, null, false);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(3).with(nextNextDaysSunrises));
    }

    @Test
    void parse_nullState_treatedCorrectly_notAddedAsState() {
        addKnownLightIdsWithDefaultCapabilities(1);
        addState("1\tsunrise\tct:" + defaultCt);
        addState("1\tsunrise+10");
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
        assertThrows(LightNotFoundException.class, () -> addState("Unknown Light\t12:00\tct:" + defaultCt));
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
    void parse_invalidCtValue_tooHigh_exception() {
        assertThrows(InvalidColorTemperatureValue.class, () -> addState(1, now, null, 501));
    }

    @Test
    void parse_invalidHueValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidHueValue.class, () -> addState("1\t" + nowTimeString + "\thue:" + 65536));
    }

    @Test
    void parse_invalidHueValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidHueValue.class, () -> addState("1\t" + nowTimeString + "\thue:" + -1));
    }

    @Test
    void parse_invalidSaturationValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidSaturationValue.class, () -> addState("1\t" + nowTimeString + "\tsat:" + 255));
    }

    @Test
    void parse_invalidSaturationValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidSaturationValue.class, () -> addState("1\t" + nowTimeString + "\tsat:" + -1));
    }

    @Test
    void parse_invalidXAndYValue_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addState("1\t" + nowTimeString + "\tx:" + 1.1));
    }

    @Test
    void parse_invalidXAndYValue_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidXAndYValue.class, () -> addState("1\t" + nowTimeString + "\ty:" + -0.1));
    }

    @Test
    void parse_invalidTransitionTime_tooLow_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addState("1\t" + nowTimeString + "\ttr:" + -1));
    }

    @Test
    void parse_invalidTransitionTime_tooHigh_exception() {
        addKnownLightIdsWithDefaultCapabilities(1);
        assertThrows(InvalidTransitionTime.class, () -> addState("1\t" + nowTimeString + "\ttr:" + 65536));
    }

    @Test
    void run_execution_reachable_runsConfirmations_startsAgainNextDay_repeats() {
        addDefaultState();
        startScheduler();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true);
        runAndAssertConfirmations();

        ScheduledRunnable nextDayState = ensureRunnable(initialNow.plusDays(1));

        advanceTimeAndRunAndAssertApiCalls(nextDayState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(2));
    }

    @Test
    void run_execution_groupState_correctPutCall() {
        addGroupState(10, now, 1, 2, 3);
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true, true);
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
        List<ScheduledRunnable> initalStates = ensureScheduledStates(2);

        advanceTimeAndRunAndAssertApiCalls(initalStates.get(0), true);

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
        addDefaultState();
        startScheduler();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), false);

        ScheduledRunnable retryState = ensureRetryState();

        advanceTimeAndRunAndAssertApiCalls(retryState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
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
        addDefaultState();
        startScheduler();
        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true);
        ensureAndRunSingleConfirmation(true);
        ensureAndRunSingleConfirmation(false);

        ScheduledRunnable retryRunnable = ensureRetryState();

        advanceTimeAndRunAndAssertApiCalls(retryRunnable, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_putNotSuccessful_butReachable_whichHappensWhenLightIsSoftwareBasedOff_triesAgain_withoutCallingGetStatus() {
        addDefaultState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        apiPutSuccessful = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), defaultBrightness, defaultCt, false, null, null);

        ScheduledRunnable retryState = ensureRetryState();
        apiPutSuccessful = true;
        advanceTimeAndRunAndAssertApiCalls(retryState, true);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_putSuccessful_reachable_butOff_triesAgain() {
        addDefaultState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertApiCalls(scheduledRunnables.get(0), true, false, defaultBrightness, defaultCt, false);

        ScheduledRunnable retryState = ensureRetryState();
        advanceTimeAndRunAndAssertApiCalls(retryState, true, true, defaultBrightness, defaultCt, false);
        runAndAssertConfirmations();

        ensureRunnable(initialNow.plusDays(1));
    }

    @Test
    void run_execution_nullState_notExecuted_justUsedToProvideEndToPreviousState() {
        addDefaultState();
        addNullState(now.plusMinutes(5));
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceCurrentTime(Duration.ofMinutes(5));

        scheduledRunnables.get(0).run(); // no API calls, as already ended

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
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOffApiCall(true, scheduledRunnables.get(0), false);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_unreachable_treatedAsSuccess_noConfirm() {
        addOffState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        advanceTimeAndRunAndAssertTurnOffApiCall(false, scheduledRunnables.get(0), true);

        ensureRunnable(now.plusDays(1));
    }

    @Test
    void run_execution_off_putFailed_retries_secondTimeSuccess_noConfirms() {
        addOffState();
        startScheduler();

        List<ScheduledRunnable> scheduledRunnables = ensureScheduledStates(1);

        apiPutSuccessful = false;
        advanceTimeAndRunAndAssertPutCall(scheduledRunnables.get(0), null, null, false, false, null);

        ScheduledRunnable retryState = ensureRetryState();
        apiPutSuccessful = true;
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
        Integer transitionTime;
        boolean groupState;

        public PutState(int id, Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat, Boolean on, Integer transitionTime, boolean groupState) {
            this.id = id;
            this.bri = bri;
            this.x = x;
            this.y = y;
            this.hue = hue;
            this.sat = sat;
            this.ct = ct;
            this.on = on;
            this.transitionTime = transitionTime;
            this.groupState = groupState;
        }
    }
}
