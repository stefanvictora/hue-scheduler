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
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class HueEnforcer {

    private static final Logger LOG = LoggerFactory.getLogger(HueEnforcer.class);

    private final HueApi hueApi;
    private final ScheduledExecutorService scheduledExecutorService;
    private final Supplier<LocalDateTime> currentTime;
    private final Map<Integer, List<EnforcedState>> lightStates;

    public HueEnforcer(HueApi hueApi, ScheduledExecutorService scheduledExecutorService, Supplier<LocalDateTime> currentTime) {
        this.hueApi = hueApi;
        this.scheduledExecutorService = scheduledExecutorService;
        this.currentTime = currentTime;
        lightStates = new HashMap<>();
    }

    public static void main(String[] args) throws IOException {
        HueApi hueApi = new HueApiImpl(new HttpResourceProviderImpl(), args[0], args[1]);
        HueEnforcer enforcer = new HueEnforcer(hueApi, Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors()), LocalDateTime::now);
        Files.lines(Paths.get(args[2]))
             .filter(s -> !s.isEmpty())
             .filter(s -> !s.startsWith("//"))
             .forEachOrdered(enforcer::addState);
        enforcer.start();
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
            LocalTime start = LocalTime.parse(parts[1]);
            Integer bri = null;
            Integer ct = null;
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
                }
            }
            if (groupState) {
                addGroupState(id, start, bri, ct);
            } else {
                addState(id, start, bri, ct);
            }
        }
    }

    public void addState(int lampId, LocalTime start, Integer brightness, Integer ct) {
        lightStates.computeIfAbsent(lampId, ArrayList::new)
                   .add(new EnforcedState(lampId, start, brightness, ct));
    }

    public void addGroupState(int groupId, LocalTime start, Integer brightness, Integer ct) {
        lightStates.computeIfAbsent(groupId, ArrayList::new)
                   .add(new EnforcedState(groupId, getGroupLights(groupId).get(0), start, brightness, ct, true));
    }

    private List<Integer> getGroupLights(int groupId) {
        return hueApi.getGroupLights(groupId);
    }

    public void start() {
        LocalDateTime now = currentTime.get();
        lightStates.forEach((id, states) -> {
            calculateAndSetEndTimes(now, states);
            scheduleStates(now, states);
        });
    }

    private void calculateAndSetEndTimes(LocalDateTime now, List<EnforcedState> states) {
        states.sort(Comparator.comparing(EnforcedState::getStart));
        for (int i = 1; i < states.size(); i++) {
            EnforcedState previous = states.get(i - 1);
            EnforcedState current = states.get(i);
            previous.setEnd(LocalDateTime.of(now.toLocalDate(), current.getStart().minusSeconds(1)));
        }
        EnforcedState lastState = states.get(states.size() - 1);
        lastState.setEnd(getStartOfFirstStateNextDay(now, states));
    }

    private LocalDateTime getStartOfFirstStateNextDay(LocalDateTime now, List<EnforcedState> states) {
        return LocalDateTime.of(now.toLocalDate().plusDays(1), states.get(0).getStart().minusSeconds(1));
    }

    private void scheduleStates(LocalDateTime now, List<EnforcedState> states) {
        sortByLastFirst(states);
        if (allInTheFuture(states, now)) {
            scheduleLastImmediately(states);
            states = skipLast(states);
        }
        for (int i = 0; i < states.size(); i++) {
            EnforcedState state = states.get(i);
            schedule(now, state);
            if (state.isInThePast(now) && hasMorePastStates(states, i)) {
                addRemainingStatesTheNextDay(getRemaining(states, i), now);
                break;
            }
        }
    }

    private void sortByLastFirst(List<EnforcedState> states) {
        states.sort(Comparator.comparing(EnforcedState::getStart, Comparator.reverseOrder()));
    }

    private boolean allInTheFuture(List<EnforcedState> states, LocalDateTime now) {
        return !states.get(states.size() - 1).isInThePast(now);
    }

    private void scheduleLastImmediately(List<EnforcedState> states) {
        schedule(states.get(0), 0);
    }

    private List<EnforcedState> skipLast(List<EnforcedState> states) {
        return states.subList(1, states.size());
    }

    private void schedule(LocalDateTime now, EnforcedState state) {
        schedule(state, state.getDelay(now));
    }

    private void schedule(EnforcedState state, long delayInSeconds) {
        if (delayInSeconds != 1) {
            LOG.debug("Schedule {} in {}", state, Duration.ofSeconds(delayInSeconds));
        }
        scheduledExecutorService.schedule(() -> {
            if (state.endsAfter(currentTime.get())) {
                LOG.debug("State {} already ended", state);
                scheduleNextDay(state);
                return;
            }
            hueApi.putState(state.getUpdateId(), state.getBrightness(), null, null, state.getCt(), state.isGroupState());
            if (!hueApi.getLightState(state.getStatusId()).isReachable()) {
                LOG.debug("Light {} not reachable, try again", state.getUpdateId());
                state.resetConfirmations();
                schedule(state, 1);
                return;
            }
            if (!state.isFullyConfirmed()) {
                state.addConfirmation();
                LOG.debug("Confirmed light {} ({})", state.getUpdateId(), state.getConfirmDebugString());
                schedule(state, 1);
            } else {
                LOG.debug("State {} fully confirmed", state);
                scheduleNextDay(state);
            }
        }, delayInSeconds, TimeUnit.SECONDS);
    }

    private boolean hasMorePastStates(List<EnforcedState> states, int i) {
        return states.size() > i + 1;
    }

    private List<EnforcedState> getRemaining(List<EnforcedState> states, int i) {
        return states.subList(i + 1, states.size());
    }

    private void addRemainingStatesTheNextDay(List<EnforcedState> remainingStates, LocalDateTime now) {
        remainingStates.forEach(state -> {
            state.shiftEndToNextDay();
            schedule(state, secondsUntilNextDayFromStart(state, now));
        });
    }

    private long secondsUntilNextDayFromStart(EnforcedState state, LocalDateTime now) {
        LocalDateTime startDateTime = LocalDateTime.of(now.toLocalDate(), state.getStart());
        Duration between = Duration.between(now, startDateTime);
        if (now.isAfter(startDateTime) || now.isEqual(startDateTime)) {
            return between.plusHours(24).getSeconds();
        }
        return between.getSeconds();
    }

    private void scheduleNextDay(EnforcedState state) {
        state.resetConfirmations();
        state.shiftEndToNextDay();
        schedule(state, secondsUntilNextDayFromStart(state, currentTime.get()));
    }
}
