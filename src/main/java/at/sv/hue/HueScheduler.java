package at.sv.hue;

import at.sv.hue.api.*;
import at.sv.hue.time.StartTimeProvider;
import at.sv.hue.time.StartTimeProviderImpl;
import at.sv.hue.time.SunTimesProviderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class HueScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);
    private static final String VERSION = "1.0-SNAPSHOT";
    private static final int DEFAULT_CONFIRM_DELAY = 6;

    private final HueApi hueApi;
    private final StateScheduler stateScheduler;
    private final StartTimeProvider startTimeProvider;
    private final Supplier<ZonedDateTime> currentTime;
    private final Map<Integer, List<ScheduledState>> lightStates;
    private final Supplier<Integer> retryDelay;
    private final int confirmDelay;

    public HueScheduler(HueApi hueApi, StateScheduler stateScheduler, StartTimeProvider startTimeProvider,
                        Supplier<ZonedDateTime> currentTime, Supplier<Integer> retryDelayInMs, int confirmDelayInSeconds) {
        this.hueApi = hueApi;
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        lightStates = new HashMap<>();
        this.retryDelay = retryDelayInMs;
        this.confirmDelay = confirmDelayInSeconds * 1000;
    }

    public static void main(String[] args) throws IOException {
        System.out.println("HueScheduler "+ VERSION);
        if (args.length == 2) {
            System.out.println(createStartTimeProvider(args[0], args[1]).toDebugString(ZonedDateTime.now()));
            System.exit(0);
        }
        if (args.length != 6) {
            System.out.println("Usage: hue-scheduler bridgeIp bridgeUsername latitude longitude maxRetryDelayInSeconds inputFilePath");
            System.exit(1);
        }
        HueApi hueApi = new HueApiImpl(new HttpResourceProviderImpl(), args[0], args[1]);
        int maxRetryDelayInSeconds = parseInt(args[4], "Failed to parse retryDelay");
        LOG.info("Max retry delay: {} s", maxRetryDelayInSeconds);
        HueScheduler scheduler = new HueScheduler(hueApi, createStateScheduler(), createStartTimeProvider(args[2], args[3]), ZonedDateTime::now,
                () -> getRandomRetryDelayMs(maxRetryDelayInSeconds), DEFAULT_CONFIRM_DELAY);
        Path inputPath = getPathAndAssertReadable(args[5]);
        Files.lines(inputPath)
             .filter(s -> !s.isEmpty())
             .filter(s -> !s.startsWith("//") && !s.startsWith("#"))
             .forEachOrdered(scheduler::addState);
        scheduler.start();
    }

    private static StateSchedulerImpl createStateScheduler() {
        return new StateSchedulerImpl(Executors.newSingleThreadScheduledExecutor(), ZonedDateTime::now);
    }

    private static StartTimeProviderImpl createStartTimeProvider(String latitude, String longitude) {
        double lat = parseDouble(latitude, "Failed to parse latitude");
        double lng = parseDouble(longitude, "Failed to parse longitude");
        LOG.info("Lat: {}, Long: {}", lat, lng);
        return new StartTimeProviderImpl(new SunTimesProviderImpl(lat, lng));
    }

    private static double parseDouble(String arg, String errorMessage) {
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException e) {
            System.err.println(errorMessage + ": " + e.getLocalizedMessage());
            System.exit(1);
            return -1;
        }
    }

    private static int parseInt(String arg, String errorMessage) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.err.println(errorMessage + ": " + e.getLocalizedMessage());
            System.exit(1);
            return -1;
        }
    }

    private static int getRandomRetryDelayMs(int maxRetryDelaySeconds) {
        return ThreadLocalRandom.current().nextInt(1000, maxRetryDelaySeconds * 1000 + 1);
    }

    private static Path getPathAndAssertReadable(String arg) {
        Path path = Paths.get(arg);
        if (!Files.isReadable(path)) {
            System.err.println("Given input file '" + path.toAbsolutePath() + "' does not exist or is not readable!");
            System.exit(1);
        }
        LOG.info("Input file: {}", path.toAbsolutePath());
        return path;
    }

    public void addState(String input) {
        String[] parts = input.split("\\t|\\s{4}");
        if (parts.length < 2)
            throw new InvalidConfigurationLine("Invalid configuration line '" + input + "': at least id and start time have to be set!");
        for (String idPart : parts[0].split(",")) {
            int id;
            boolean groupState;
            String name = "";
            if (idPart.matches("g\\d+")) {
                id = Integer.parseInt(idPart.substring(1));
                name = hueApi.getGroupName(id);
                groupState = true;
            } else if (idPart.matches("\\d+")) {
                id = Integer.parseInt(idPart);
                name = hueApi.getLightName(id);
                groupState = false;
            } else {
                name = idPart;
                try {
                    id = hueApi.getGroupId(idPart);
                    groupState = true;
                } catch (GroupNotFoundException e) {
                    id = hueApi.getLightId(idPart);
                    groupState = false;
                }
            }
            Integer bri = null;
            Integer ct = null;
            Boolean on = null;
            Double x = null;
            Double y = null;
            Integer hue = null;
            Integer sat = null;
            Integer transitionTime = null;
            for (int i = 2; i < parts.length; i++) {
                String part = parts[i];
                String[] typeAndValue = part.split(":");
                switch (typeAndValue[0]) {
                    case "bri":
                        bri = Integer.valueOf(typeAndValue[1]);
                        break;
                    case "ct":
                        ct = Integer.valueOf(typeAndValue[1]);
                        break;
                    case "k":
                        ct = convertToMiredCt(Integer.valueOf(typeAndValue[1]));
                        break;
                    case "on":
                        on = Boolean.valueOf(typeAndValue[1]);
                        break;
                    case "tr":
                        transitionTime = Integer.valueOf(typeAndValue[1]);
                        break;
                    case "x":
                        x = Double.parseDouble(typeAndValue[1]);
                        break;
                    case "y":
                        y = Double.parseDouble(typeAndValue[1]);
                        break;
                    case "hue":
                        hue = Integer.valueOf(typeAndValue[1]);
                        break;
                    case "sat":
                        sat = Integer.valueOf(typeAndValue[1]);
                        break;
                    case "hex":
                        RBGToXYConverter.XYColor xyColor = RBGToXYConverter.convert(typeAndValue[1]);
                        x = xyColor.getX();
                        y = xyColor.getY();
                        bri = xyColor.getBrightness();
                        break;
                    default:
                        throw new UnknownStateProperty("Unknown state property '" + typeAndValue[0] + "' with value '" + typeAndValue[1] + "'");
                }
            }
            String start = parts[1];
            if (groupState) {
                addGroupState(name, id, start, bri, ct, x, y, hue, sat, on, transitionTime);
            } else {
                addState(name, id, start, bri, ct, x, y, hue, sat, on, transitionTime);
            }
        }
    }

    private Integer convertToMiredCt(Integer kelvin) {
        return 1_000_000 / kelvin;
    }

    public void addState(String name, int lampId, String start, Integer brightness, Integer ct, Double x, Double y,
                         Integer hue, Integer sat, Boolean on, Integer transitionTime) {
        lightStates.computeIfAbsent(lampId, ArrayList::new)
                   .add(new ScheduledState(name, lampId, start, brightness, ct, x, y, hue, sat, on, transitionTime, startTimeProvider));
    }

    public void addGroupState(String name, int groupId, String start, Integer brightness, Integer ct, Double x, Double y,
                              Integer hue, Integer sat, Boolean on, Integer transitionTime) {
        lightStates.computeIfAbsent(getGroupId(groupId), ArrayList::new)
                   .add(new ScheduledState(name, groupId, getGroupLights(groupId).get(0), start, brightness, ct, x, y,
                           hue, sat, on, transitionTime, startTimeProvider, true));
    }

    private int getGroupId(int id) {
        return id + 1000;
    }

    private List<Integer> getGroupLights(int groupId) {
        return hueApi.getGroupLights(groupId);
    }

    public void start() {
        ZonedDateTime now = currentTime.get();
        lightStates.forEach((id, states) -> {
            calculateAndSetEndTimes(now, states);
            scheduleStates(now, states);
        });
        scheduleSunDataInfoLog();
    }

    private void calculateAndSetEndTimes(ZonedDateTime now, List<ScheduledState> states) {
        sortByTimeAscending(states, now);
        for (int i = 1; i < states.size(); i++) {
            ScheduledState previous = states.get(i - 1);
            ScheduledState current = states.get(i);
            previous.setEnd(getRightBeforeStartOfState(current, now));
        }
        ScheduledState lastState = states.get(states.size() - 1);
        lastState.setEnd(getRightBeforeStartOfStateNextDay(states.get(0), now));
    }

    private void sortByTimeAscending(List<ScheduledState> states, ZonedDateTime now) {
        states.sort(Comparator.comparing(scheduledState -> scheduledState.getStart(now)));
    }

    private ZonedDateTime getRightBeforeStartOfState(ScheduledState state, ZonedDateTime dateTime) {
        return ZonedDateTime.of(LocalDateTime.of(dateTime.toLocalDate(), state.getStart(dateTime).minusSeconds(1)), dateTime.getZone());
    }

    private ZonedDateTime getRightBeforeStartOfStateNextDay(ScheduledState state, ZonedDateTime now) {
        return getRightBeforeStartOfState(state, now.plusDays(1));
    }

    private void scheduleStates(ZonedDateTime now, List<ScheduledState> states) {
        sortByLastFirst(states, now);
        if (allInTheFuture(states, now)) {
            scheduleTemporaryCopyOfLastImmediately(states, now);
        }
        for (int i = 0; i < states.size(); i++) {
            ScheduledState state = states.get(i);
            schedule(state, now);
            if (state.isInThePast(now) && hasMorePastStates(states, i)) {
                addRemainingStatesTheNextDay(getRemaining(states, i), now);
                break;
            }
        }
    }

    private void sortByLastFirst(List<ScheduledState> states, ZonedDateTime now) {
        states.sort(Comparator.comparing(scheduledState -> scheduledState.getStart(now), Comparator.reverseOrder()));
    }

    private boolean allInTheFuture(List<ScheduledState> states, ZonedDateTime now) {
        return !states.get(states.size() - 1).isInThePast(now);
    }

    private void scheduleTemporaryCopyOfLastImmediately(List<ScheduledState> states, ZonedDateTime now) {
        ZonedDateTime newEnd = getRightBeforeStartOfState(states.get(states.size() - 1), now);
        ScheduledState temporaryCopy = ScheduledState.createTemporaryCopy(states.get(0), now, newEnd);
        schedule(temporaryCopy, 0);
    }

    private void schedule(ScheduledState state, ZonedDateTime now) {
        state.updateLastStart(now);
        schedule(state, state.getDelayInSeconds(now) * 1000L);
    }

    private void schedule(ScheduledState state, long delayInMs) {
        if (state.isNullState()) return;
        if (delayInMs == 0 || delayInMs > Math.max(5000, confirmDelay)) {
            LOG.debug("Schedule {} in {}", state, Duration.ofMillis(delayInMs));
        }
        stateScheduler.schedule(() -> {
            if (state.endsAfter(currentTime.get())) {
                LOG.debug("{} already ended", state);
                scheduleNextDay(state);
                return;
            }
            boolean success = hueApi.putState(state.getUpdateId(), state.getBrightness(), state.getCt(), state.getX(), state.getY(),
                    state.getHue(), state.getSat(), state.getOn(), state.getTransitionTime(), state.isGroupState());
            LightState lightState = null;
            if (success) {
                lightState = hueApi.getLightState(state.getStatusId());
            }
            if (success && state.isOff() && lightState.isUnreachableOrOff()) {
                LOG.debug("{} turned off or already off", state);
                scheduleNextDay(state);
                return;
            }
            if (!success || lightState.isUnreachableOrOff()) {
                LOG.trace("'{}' not reachable or off, try again", state.getName());
                state.resetConfirmations();
                schedule(state, retryDelay.get());
                return;
            }
            if (!state.isFullyConfirmed()) {
                state.addConfirmation();
                if (state.getConfirmCounter() == 1) {
                    LOG.debug("Set {}", state);
                } else if (state.getConfirmCounter() % 5 == 0) {
                    LOG.debug("Confirmed {} ({})", state, state.getConfirmDebugString());
                }
                schedule(state, confirmDelay);
            } else {
                LOG.debug("{} fully confirmed", state);
                scheduleNextDay(state);
            }
        }, currentTime.get().plus(delayInMs, ChronoUnit.MILLIS));
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
        recalculateEnd(state, now);
        schedule(state, state.secondsUntilNextDayFromStart(now) * 1000L);
        state.updateLastStart(now);
    }

    private void recalculateEnd(ScheduledState state, ZonedDateTime now) {
        List<ScheduledState> states = new ArrayList<>(getLightStatesForId(state));
        sortByTimeAscending(states, now);
        int index = states.indexOf(state);
        if (index + 1 < states.size()) {
            ScheduledState next = states.get(index + 1);
            state.setEnd(getRightBeforeStartOfStateNextDay(next, state.getEnd()));
        } else {
            state.setEnd(getRightBeforeStartOfStateNextDay(states.get(0), state.getEnd()));
        }
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
