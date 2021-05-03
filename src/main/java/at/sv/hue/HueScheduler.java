package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Command(name = "HueScheduler", version = "1.0-SNAPSHOT", mixinStandardHelpOptions = true, sortOptions = false)
public final class HueScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final Map<Integer, List<ScheduledState>> lightStates;
    @Parameters(description = "The IP address of your Philips Hue Bridge.")
    String ip;
    @Parameters(description = "The Philips Hue Bridge username created for Hue Scheduler during setup.")
    String username;
    @Parameters(paramLabel = "FILE", description = "The configuration file containing your schedules.")
    Path inputFile;
    @Option(names = "--lat", required = true, description = "The latitude of your location.")
    double latitude;
    @Option(names = "--long", required = true, description = "The longitude of your location.")
    double longitude;
    @Option(names = "--elevation", description = "The optional elevation (in meters) of your location, used to provide more accurate sunrise and sunset times.",
            defaultValue = "0.0")
    double elevation;
    @Option(names = "--retry-delay", paramLabel = "<delay>",
            description = "The maximum amount of seconds Hue Scheduler should wait before retrying to control a light that was not reachable." +
                    " Default: ${DEFAULT-VALUE} seconds.",
            defaultValue = "5")
    int maxRetryDelayInSeconds;
    @Option(names = "--max-requests-per-second", description = "The maximum number of PUT API requests to perform per second." +
            " Default and recommended: ${DEFAULT-VALUE} requests per second", defaultValue = "10.0")
    double requestsPerSecond;
    @Option(names = "--confirm-delay", hidden = true, defaultValue = "6")
    int confirmDelayInSeconds;
    @Option(names = "--bridge-failure-delay", hidden = true, defaultValue = "10")
    int bridgeFailureRetryDelayInSeconds;
    @Option(names = "--multi-color-adjustment-delay", paramLabel = "<delay>", defaultValue = "4",
            description = "The adjustment delay in seconds for each light in a group when using the multi_color effect." +
                    " Adjust to change the hue values of 'neighboring' lights. Default: ${DEFAULT-VALUE} seconds")
    int multiColorAdjustmentDelay;
    private HueApi hueApi;
    private StateScheduler stateScheduler;
    private StartTimeProvider startTimeProvider;
    private Supplier<ZonedDateTime> currentTime;
    private Supplier<Integer> retryDelay;

    public HueScheduler() {
        lightStates = new HashMap<>();
    }

    public HueScheduler(HueApi hueApi, StateScheduler stateScheduler, StartTimeProvider startTimeProvider,
                        Supplier<ZonedDateTime> currentTime, double requestsPerSecond, Supplier<Integer> retryDelayInMs,
                        int confirmDelayInSeconds, int bridgeFailureRetryDelayInSeconds, int multiColorAdjustmentDelay) {
        this();
        this.hueApi = hueApi;
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        this.requestsPerSecond = requestsPerSecond;
        this.retryDelay = retryDelayInMs;
        this.confirmDelayInSeconds = confirmDelayInSeconds;
        this.bridgeFailureRetryDelayInSeconds = bridgeFailureRetryDelayInSeconds;
        this.multiColorAdjustmentDelay = multiColorAdjustmentDelay;
    }

    public static void main(String[] args) {
        int execute = new CommandLine(new HueScheduler()).execute(args);
        if (execute != 0) {
            System.exit(execute);
        }
    }

    @Override
    public void run() {
        hueApi = new HueApiImpl(new HttpResourceProviderImpl(), ip, username, RateLimiter.create(requestsPerSecond));
        startTimeProvider = createStartTimeProvider(latitude, longitude, elevation);
        stateScheduler = createStateScheduler();
        currentTime = ZonedDateTime::now;
        retryDelay = this::getRandomRetryDelayMs;
        assertInputIsReadable();
        assertConnectionAndStart();
    }

    private void assertConnectionAndStart() {
        if (!assertConnection()) {
            stateScheduler.schedule(this::assertConnectionAndStart, currentTime.get().plusSeconds(5), null);
        } else {
            parseInput();
            start();
        }
    }

    private boolean assertConnection() {
        try {
            hueApi.assertConnection();
            LOG.info("Connected to bridge at {}.", ip);
        } catch (BridgeConnectionFailure e) {
            LOG.warn("Bridge not reachable: '" + e.getCause().getLocalizedMessage() + "'. Retrying in 5s.");
            return false;
        } catch (BridgeAuthenticationFailure e) {
            System.err.println("Bridge connection rejected: 'Unauthorized user'. Please make sure you use the correct username from the setup process," +
                    " or try to generate a new one.");
            System.exit(3);
        }
        return true;
    }

    private StartTimeProviderImpl createStartTimeProvider(double latitude, double longitude, double elevation) {
        return new StartTimeProviderImpl(new SunTimesProviderImpl(latitude, longitude, elevation));
    }

    private StateSchedulerImpl createStateScheduler() {
        return new StateSchedulerImpl(Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()), ZonedDateTime::now);
    }

    private void assertInputIsReadable() {
        if (!Files.isReadable(inputFile)) {
            System.err.println("Given input file '" + inputFile.toAbsolutePath() + "' does not exist or is not readable!");
            System.exit(1);
        }
    }

    private void parseInput() {
        try {
            Files.lines(inputFile)
                 .filter(s -> !s.isEmpty())
                 .filter(s -> !s.startsWith("//") && !s.startsWith("#"))
                 .forEachOrdered(input -> {
                     try {
                         addState(input);
                     } catch (Exception e) {
                         System.err.println("Failed to parse configuration line '" + input + "':\n" + e.getClass().getSimpleName() + ": " + e.getLocalizedMessage());
                         System.exit(2);
                     }
                 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int getRandomRetryDelayMs() {
        return ThreadLocalRandom.current().nextInt(1000, maxRetryDelayInSeconds * 1000 + 1);
    }

    public void addState(String input) {
        new InputConfigurationParser(startTimeProvider, hueApi).parse(input).forEach(state -> {
            int updateId;
            if (state.isGroupState()) {
                updateId = getGroupId(state.getUpdateId());
            } else {
                updateId = state.getUpdateId();
            }
            lightStates.computeIfAbsent(updateId, ArrayList::new).add(state);
        });
    }

    private int getGroupId(int id) {
        return id + 1000;
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        lightStates.forEach((id, states) -> {
            calculateAndSetNextEndTimes(now, states);
            scheduleStates(now, states);
        });
        scheduleSunDataInfoLog();
    }

    private void calculateAndSetNextEndTimes(ZonedDateTime now, List<ScheduledState> states) {
        HashSet<ScheduledState> alreadyUpdatedStates = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            ZonedDateTime day = now.plusDays(i);
            List<ScheduledState> unprocessedStates = getStatesOnDay(states, alreadyUpdatedStates, day, DayOfWeek.from(day));
            if (unprocessedStates.isEmpty()) continue;
            unprocessedStates.forEach(state -> calculateAndSetEndTime(state, states, state.getStart(now)));
            alreadyUpdatedStates.addAll(unprocessedStates);
        }
    }

    private List<ScheduledState> getStatesOnDay(List<ScheduledState> states, Set<ScheduledState> alreadyProcessedStates,
                                                ZonedDateTime now, DayOfWeek... days) {
        return states.stream()
                     .filter(state -> state.isScheduledOn(days))
                     .filter(state -> !alreadyProcessedStates.contains(state))
                     .sorted(Comparator.comparing(scheduledState -> scheduledState.getStart(now)))
                     .collect(Collectors.toList());
    }

    private void calculateAndSetEndTime(ScheduledState state, List<ScheduledState> states, ZonedDateTime start) {
        List<ScheduledState> statesOnDay = getStatesOnDay(states, start);
        int index = statesOnDay.indexOf(state);
        if (isLastState(statesOnDay, index)) {
            if (isAlsoScheduledTheDayAfter(state, start)) {
                setEndToStartOfFirstStateTheDayAfter(state, states, start);
            } else {
                setEndToEndOfDay(state, start);
            }
        } else {
            setEndToStartOfFollowingState(state, statesOnDay.get(index + 1), start);
        }
    }

    private List<ScheduledState> getStatesOnDay(List<ScheduledState> states, ZonedDateTime now) {
        return getStatesOnDay(states, now, DayOfWeek.from(now));
    }

    private List<ScheduledState> getStatesOnDay(List<ScheduledState> states, ZonedDateTime now, DayOfWeek... days) {
        return getStatesOnDay(states, Collections.emptySet(), now, days);
    }

    private boolean isLastState(List<ScheduledState> states, int index) {
        return index + 1 >= states.size();
    }

    private boolean isAlsoScheduledTheDayAfter(ScheduledState state, ZonedDateTime start) {
        return state.isScheduledOn(start.plusDays(1));
    }

    private void setEndToStartOfFirstStateTheDayAfter(ScheduledState state, List<ScheduledState> states, ZonedDateTime start) {
        List<ScheduledState> statesTheDayAfter = getStatesOnDay(states, start.plusDays(1));
        state.setEnd(statesTheDayAfter.get(0).getStart(start.plusDays(1)).minusSeconds(1));
    }

    private void setEndToEndOfDay(ScheduledState state, ZonedDateTime day) {
        state.setEnd(day.with(LocalTime.MIDNIGHT.minusSeconds(1)));
    }

    private void setEndToStartOfFollowingState(ScheduledState state, ScheduledState followingState, ZonedDateTime day) {
        state.setEnd(followingState.getStart(day).minusSeconds(1));
    }

    private void scheduleStates(ZonedDateTime now, List<ScheduledState> states) {
        List<ScheduledState> todaysStates = sortByLastFirst(getStatesOnDay(states, now), now);
        if (!todaysStates.isEmpty()) {
            if (allInTheFuture(todaysStates, now)) {
                ZonedDateTime startOfFirst = getRightBeforeStartOfState(todaysStates.get(todaysStates.size() - 1), now);
                scheduleTemporaryCopyOfPreviousStateImmediately(states, now, startOfFirst);
            }
            for (int i = 0; i < todaysStates.size(); i++) {
                ScheduledState state = todaysStates.get(i);
                schedule(state, now);
                if (state.isInThePast(now) && hasMorePastStates(todaysStates, i)) {
                    addRemainingStatesTheNextDay(getRemaining(todaysStates, i), now);
                    break;
                }
            }
        }
        scheduleRemainingFutureStates(states, now);
    }

    private List<ScheduledState> sortByLastFirst(List<ScheduledState> states, ZonedDateTime now) {
        states.sort(Comparator.comparing(scheduledState -> scheduledState.getStart(now), Comparator.reverseOrder()));
        return states;
    }

    private boolean allInTheFuture(List<ScheduledState> states, ZonedDateTime now) {
        return !states.get(states.size() - 1).isInThePast(now);
    }

    private void scheduleTemporaryCopyOfPreviousStateImmediately(List<ScheduledState> states, ZonedDateTime now,
                                                                 ZonedDateTime startOfFirst) {
        List<ScheduledState> statesBothTodayAndYesterday = sortByLastFirst(getStatesScheduledBothTodayAndYesterday(states, now), now);
        if (statesBothTodayAndYesterday.isEmpty()) return;
        schedule(ScheduledState.createTemporaryCopy(statesBothTodayAndYesterday.get(0), now, startOfFirst), 0);
    }

    private List<ScheduledState> getStatesScheduledBothTodayAndYesterday(List<ScheduledState> states, ZonedDateTime now) {
        DayOfWeek today = DayOfWeek.from(now);
        return getStatesOnDay(states, now, today, today.minus(1));
    }

    private ZonedDateTime getRightBeforeStartOfState(ScheduledState state, ZonedDateTime dateTime) {
        return state.getStart(dateTime).minusSeconds(1);
    }

    private void scheduleRemainingFutureStates(List<ScheduledState> states, ZonedDateTime now) {
        states.stream()
              .filter(state -> doesNotStartToday(state, now))
              .sorted(Comparator.comparing(state -> state.getStart(now)))
              .forEach(state -> schedule(state, now));
    }

    private boolean doesNotStartToday(ScheduledState state, ZonedDateTime now) {
        return !DayOfWeek.from(state.getStart(now)).equals(DayOfWeek.from(now));
    }

    private void schedule(ScheduledState state, ZonedDateTime now) {
        state.updateLastStart(now);
        schedule(state, getMs(state.getDelayInSeconds(now)));
    }

    private void schedule(ScheduledState state, long delayInMs) {
        if (state.isNullState()) return;
        if (delayInMs == 0 || delayInMs > Math.max(5000, getMs(confirmDelayInSeconds)) && delayInMs != getMs(bridgeFailureRetryDelayInSeconds)) {
            LOG.debug("Schedule {} in {}", state, Duration.ofMillis(delayInMs));
        }
        stateScheduler.schedule(() -> {
            if (state.endsAfter(currentTime.get())) {
                LOG.debug("{} already ended", state);
                scheduleNextDay(state);
                return;
            }
            boolean success;
            LightState lightState = null;
            try {
                success = putState(state);
                if (success) {
                    lightState = hueApi.getLightState(state.getStatusId());
                }
            } catch (BridgeConnectionFailure e) {
                LOG.warn("Bridge not reachable, retrying in {}s.", bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            } catch (HueApiFailure e) {
                LOG.error("Hue api call failed: '{}'. Retrying in {}s.", e.getLocalizedMessage(), bridgeFailureRetryDelayInSeconds);
                retry(state, getMs(bridgeFailureRetryDelayInSeconds));
                return;
            }
            if (success && state.isOff() && lightState.isUnreachableOrOff()) {
                LOG.info("Turned off {}, or was already off", state.getFormattedName());
                scheduleNextDay(state);
                return;
            }
            if (!success || lightState.isUnreachableOrOff()) {
                Integer delay = retryDelay.get();
                LOG.trace("{} not reachable or off, retrying in {}", state.getFormattedName(), Duration.ofMillis(delay));
                retry(state, delay);
                return;
            }
            if (!state.isFullyConfirmed()) {
                state.addConfirmation();
                if (state.getConfirmCounter() == 1) {
                    LOG.info("Set {}", state);
                } else if (state.getConfirmCounter() % 5 == 0) {
                    LOG.debug("Confirmed {} ({})", state.getFormattedName(), state.getConfirmDebugString());
                }
                schedule(state, getMs(confirmDelayInSeconds));
                if (shouldAdjustMultiColorLoopOffset(state)) {
                    scheduleMultiColorLoopOffsetAdjustments(state.getGroupLights(), 1);
                }
            } else {
                LOG.debug("Fully confirmed {}", state.getFormattedName());
                scheduleNextDay(state);
            }
        }, currentTime.get().plus(delayInMs, ChronoUnit.MILLIS), state.getEnd());
    }

    private long getMs(long seconds) {
        return seconds * 1000L;
    }

    private boolean putState(ScheduledState state) {
        return hueApi.putState(state.getUpdateId(), state.getBrightness(), state.getCt(), state.getX(), state.getY(),
                state.getHue(), state.getSat(), state.getEffect(), state.getOn(), state.getTransitionTime(currentTime.get()),
                state.isGroupState());
    }

    private void retry(ScheduledState state, long delayInMs) {
        state.resetConfirmations();
        schedule(state, delayInMs);
    }

    private boolean shouldAdjustMultiColorLoopOffset(ScheduledState state) {
        return state.getConfirmCounter() == 1 && state.isMultiColorLoop() && state.getGroupLights().size() > 1;
    }

    private void scheduleMultiColorLoopOffsetAdjustments(List<Integer> groupLights, int i) {
        stateScheduler.schedule(() -> offsetMultiColorLoopForLight(groupLights, i), currentTime.get().plusSeconds(multiColorAdjustmentDelay), null);
    }

    private void offsetMultiColorLoopForLight(List<Integer> groupLights, int i) {
        Integer light = groupLights.get(i);
        LightState lightState = hueApi.getLightState(light);
        boolean delayNext = false;
        if (lightState.isOn() && lightState.isColorLoopEffect()) {
            putOnState(light, false, null);
            stateScheduler.schedule(() -> putOnState(light, true, "colorloop"), currentTime.get().plus(300, ChronoUnit.MILLIS), null);
            delayNext = true;
        }
        if (i + 1 >= groupLights.size()) return;
        if (delayNext) {
            scheduleMultiColorLoopOffsetAdjustments(groupLights, i + 1);
        } else {
            offsetMultiColorLoopForLight(groupLights, i + 1);
        }
    }

    private void putOnState(int light, boolean on, String effect) {
        hueApi.putState(light, null, null, null, null, null, null, effect, on, null, false);
    }

    private boolean hasMorePastStates(List<ScheduledState> states, int i) {
        return states.size() > i + 1;
    }

    private List<ScheduledState> getRemaining(List<ScheduledState> states, int i) {
        return states.subList(i + 1, states.size());
    }

    private void addRemainingStatesTheNextDay(List<ScheduledState> remainingStates, ZonedDateTime now) {
        remainingStates.forEach(state -> scheduleNextDay(state, now));
    }

    private void scheduleNextDay(ScheduledState state) {
        scheduleNextDay(state, currentTime.get());
    }

    private void scheduleNextDay(ScheduledState state, ZonedDateTime now) {
        if (state.isTemporary()) return;
        state.resetConfirmations();
        recalculateNextEnd(state, now);
        long delay = state.secondsUntilNextDayFromStart(now);
        state.updateLastStart(now);
        schedule(state, getMs(delay));
    }

    private void recalculateNextEnd(ScheduledState state, ZonedDateTime now) {
        calculateAndSetEndTime(state, getLightStatesForId(state), state.getNextStart(now));
    }

    private List<ScheduledState> getLightStatesForId(ScheduledState state) {
        return lightStates.get(getLightId(state));
    }

    private int getLightId(ScheduledState state) {
        if (state.isGroupState()) {
            return getGroupId(state.getUpdateId());
        } else {
            return state.getUpdateId();
        }
    }

    private void scheduleSunDataInfoLog() {
        logSunDataInfo();
        ZonedDateTime now = currentTime.get();
        ZonedDateTime midnight = ZonedDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT, now.getZone());
        long delay = Duration.between(now, midnight).toMinutes();
        stateScheduler.scheduleAtFixedRate(this::logSunDataInfo, delay + 1, 60 * 24, TimeUnit.MINUTES);
    }

    private void logSunDataInfo() {
        LOG.info("Current sun times:\n{}", startTimeProvider.toDebugString(currentTime.get()));
    }
}
