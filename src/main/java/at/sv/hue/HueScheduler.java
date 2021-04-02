package at.sv.hue;

import at.sv.hue.api.HttpResourceProviderImpl;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.HueApiImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class HueScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(HueScheduler.class);

    private final HueApi hueApi;
    private final StateScheduler stateScheduler;
    private final StartTimeProvider startTimeProvider;
    private final Supplier<ZonedDateTime> currentTime;
    private final Map<Integer, List<EnforcedState>> lightStates;
    private final Supplier<Integer> retryDelay;
    private final int confirmDelay;

    public HueScheduler(HueApi hueApi, StateScheduler stateScheduler, StartTimeProvider startTimeProvider,
                        Supplier<ZonedDateTime> currentTime, Supplier<Integer> retryDelay, int confirmDelay) {
        this.hueApi = hueApi;
        this.stateScheduler = stateScheduler;
        this.startTimeProvider = startTimeProvider;
        this.currentTime = currentTime;
        lightStates = new HashMap<>();
        this.retryDelay = retryDelay;
        this.confirmDelay = confirmDelay;
    }

    public static void main(String[] args) throws IOException {
        HueApi hueApi = new HueApiImpl(new HttpResourceProviderImpl(), args[0], args[1]);
        double lat = Double.parseDouble(args[2]);
        double lng = Double.parseDouble(args[3]);
        StartTimeProviderImpl startTimeProvider = new StartTimeProviderImpl(new SunDataProviderImpl(lat, lng));
        StateScheduler stateScheduler = new StateSchedulerImpl(Executors.newSingleThreadScheduledExecutor(), ZonedDateTime::now);
        int retryMaxValue = Integer.parseInt(args[4]);
        int confirmDelay = Integer.parseInt(args[5]);
        HueScheduler enforcer = new HueScheduler(hueApi, stateScheduler, startTimeProvider, ZonedDateTime::now,
                () -> getRandomRetryDelay(retryMaxValue), confirmDelay);
        Files.lines(Paths.get(args[6]))
             .filter(s -> !s.isEmpty())
             .filter(s -> !s.startsWith("//"))
             .forEachOrdered(enforcer::addState);
        LOG.info("Retry delay: {} s, Confirm delay: {} s", retryMaxValue, confirmDelay);
        LOG.info("Lat: {}, Long: {}", lat, lng);
        enforcer.start();
    }

    private static int getRandomRetryDelay(int retryMaxValue) {
        return ThreadLocalRandom.current().nextInt(1, retryMaxValue + 1);
    }

    public void addState(String input) {
        String[] parts = input.split("\t");
        for (String idPart : parts[0].split(",")) {
            int id;
            boolean groupState;
            if (idPart.startsWith("g")) {
                id = Integer.parseInt(idPart.substring(1));
                groupState = true;
            } else {
                groupState = false;
                id = Integer.parseInt(idPart);
            }
            Integer bri = null;
            Integer ct = null;
            Boolean on = null;
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
                    case "on":
                        on = Boolean.valueOf(typeAndValue[1]);
                        break;
                }
            }
            String start = parts[1];
            if (groupState) {
                addGroupState(id, start, bri, ct, on);
            } else {
                addState(id, start, bri, ct, on);
            }
        }
    }

    public void addState(int lampId, String start, Integer brightness, Integer ct, Boolean on) {
        lightStates.computeIfAbsent(lampId, ArrayList::new)
                   .add(new EnforcedState(lampId, start, brightness, ct, on, startTimeProvider));
    }

    public void addGroupState(int groupId, String start, Integer brightness, Integer ct, Boolean on) {
        lightStates.computeIfAbsent(getGroupId(groupId), ArrayList::new)
                   .add(new EnforcedState(groupId, getGroupLights(groupId).get(0), start, brightness, ct, on,
                           startTimeProvider, true));
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

    private void calculateAndSetEndTimes(ZonedDateTime now, List<EnforcedState> states) {
        sortByTimeAscending(states, now);
        for (int i = 1; i < states.size(); i++) {
            EnforcedState previous = states.get(i - 1);
            EnforcedState current = states.get(i);
            previous.setEnd(getRightBeforeStartOfState(current, now));
        }
        EnforcedState lastState = states.get(states.size() - 1);
        lastState.setEnd(getRightBeforeStartOfStateNextDay(states.get(0), now));
    }

    private void sortByTimeAscending(List<EnforcedState> states, ZonedDateTime now) {
        states.sort(Comparator.comparing(enforcedState -> enforcedState.getStart(now)));
    }

    private ZonedDateTime getRightBeforeStartOfState(EnforcedState state, ZonedDateTime dateTime) {
        return ZonedDateTime.of(LocalDateTime.of(dateTime.toLocalDate(), state.getStart(dateTime).minusSeconds(1)), dateTime.getZone());
    }

    private ZonedDateTime getRightBeforeStartOfStateNextDay(EnforcedState state, ZonedDateTime now) {
        return getRightBeforeStartOfState(state, now.plusDays(1));
    }

    private void scheduleStates(ZonedDateTime now, List<EnforcedState> states) {
        sortByLastFirst(states, now);
        if (allInTheFuture(states, now)) {
            scheduleLastImmediately(states);
            states = skipLast(states);
        }
        for (int i = 0; i < states.size(); i++) {
            EnforcedState state = states.get(i);
            schedule(state, now);
            if (state.isInThePast(now) && hasMorePastStates(states, i)) {
                addRemainingStatesTheNextDay(getRemaining(states, i), now);
                break;
            }
        }
    }

    private void sortByLastFirst(List<EnforcedState> states, ZonedDateTime now) {
        states.sort(Comparator.comparing(enforcedState -> enforcedState.getStart(now), Comparator.reverseOrder()));
    }

    private boolean allInTheFuture(List<EnforcedState> states, ZonedDateTime now) {
        return !states.get(states.size() - 1).isInThePast(now);
    }

    private void scheduleLastImmediately(List<EnforcedState> states) {
        schedule(states.get(0), 0);
    }

    private List<EnforcedState> skipLast(List<EnforcedState> states) {
        return states.subList(1, states.size());
    }

    private void schedule(EnforcedState state, ZonedDateTime now) {
        state.updateLastStart(now);
        schedule(state, state.getDelay(now));
    }

    private void schedule(EnforcedState state, long delayInSeconds) {
        if (state.isNullState()) return;
        if (delayInSeconds > 5) {
            LOG.debug("Schedule {} in {}", state, Duration.ofSeconds(delayInSeconds));
        }
        stateScheduler.schedule(() -> {
            if (state.endsAfter(currentTime.get())) {
                LOG.debug("State {} already ended", state);
                scheduleNextDay(state);
                return;
            }
            boolean success = hueApi.putState(state.getUpdateId(), state.getBrightness(), null, null, state.getCt(), state.getOn(), state.isGroupState());
            LightState lightState = null;
            if (success) {
                lightState = hueApi.getLightState(state.getStatusId());
            }
            if (success && state.isOff() && lightState.isUnreachableOrOff()) {
                LOG.trace("Light {} turned off or already off", state.getUpdateId());
                scheduleNextDay(state);
                return;
            }
            if (!success || lightState.isUnreachableOrOff()) {
                LOG.trace("Light {} not reachable or off, try again", state.getUpdateId());
                state.resetConfirmations();
                schedule(state, retryDelay.get());
                return;
            }
            if (!state.isFullyConfirmed()) {
                if (state.getConfirmCounter() % 5 == 0) {
                    LOG.debug("Confirmed light {} ({})", state.getUpdateId(), state.getConfirmDebugString());
                }
                state.addConfirmation();
                schedule(state, confirmDelay);
            } else {
                LOG.debug("State {} fully confirmed", state);
                scheduleNextDay(state);
            }

        }, currentTime.get().plusSeconds(delayInSeconds));
    }

    private boolean hasMorePastStates(List<EnforcedState> states, int i) {
        return states.size() > i + 1;
    }

    private List<EnforcedState> getRemaining(List<EnforcedState> states, int i) {
        return states.subList(i + 1, states.size());
    }

    private void addRemainingStatesTheNextDay(List<EnforcedState> remainingStates, ZonedDateTime now) {
        remainingStates.forEach(state -> {
            scheduleNextDay(state, now);
        });
    }

    private void scheduleNextDay(EnforcedState state) {
        scheduleNextDay(state, currentTime.get());
    }

    private void scheduleNextDay(EnforcedState state, ZonedDateTime now) {
        state.resetConfirmations();
        recalculateEnd(state, now);
        schedule(state, state.secondsUntilNextDayFromStart(now));
        state.updateLastStart(now);
    }

    private void recalculateEnd(EnforcedState state, ZonedDateTime now) {
        List<EnforcedState> states = new ArrayList<>(getLightStatesForId(state));
        sortByTimeAscending(states, now);
        int index = states.indexOf(state);
        if (index + 1 < states.size()) {
            EnforcedState next = states.get(index + 1);
            state.setEnd(getRightBeforeStartOfStateNextDay(next, state.getEnd()));
        } else {
            state.setEnd(getRightBeforeStartOfStateNextDay(states.get(0), state.getEnd()));
        }
    }

    private List<EnforcedState> getLightStatesForId(EnforcedState state) {
        return lightStates.get(getLightId(state));
    }

    private int getLightId(EnforcedState state) {
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
        LOG.info("{}", startTimeProvider.toDebugString(currentTime.get()));
    }
}
