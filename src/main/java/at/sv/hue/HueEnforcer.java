package at.sv.hue;

import at.sv.hue.api.HttpResourceProviderImpl;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.HueApiImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public static void main(String[] args) {
        HueApi hueApi = new HueApiImpl(new HttpResourceProviderImpl(), args[0], args[1]);
        HueEnforcer enforcer = new HueEnforcer(hueApi, Executors.newSingleThreadScheduledExecutor(), LocalDateTime::now);

        enforcer.addState(22, LocalTime.now(), 200, 100);  // Schreibtisch
        enforcer.addState(22, LocalTime.now().plusSeconds(10), 100, 500);  // Schreibtisch
        enforcer.addState(22, LocalTime.now().plusSeconds(15), 254, 500);  // Schreibtisch
//        enforcer.addState(23, LocalTime.now(), 65, 500);  // Bad
//        enforcer.addState(25, LocalTime.now(), 254, 213); // Klo

        enforcer.start();
    }

    public void addState(int lampId, LocalTime start, int brightness, int ct) {
        lightStates.computeIfAbsent(lampId, ArrayList::new)
                   .add(new EnforcedState(lampId, start, brightness, ct));
    }

    public void start() {
        LocalDateTime now = currentTime.get();
        lightStates.forEach((id, states) -> {
            calculateAndSetEndTimes(states);
            scheduleStates(now, states);
        });
    }

    private void calculateAndSetEndTimes(List<EnforcedState> states) {
        states.sort(Comparator.comparing(EnforcedState::getStart));
        for (int i = 1; i < states.size(); i++) {
            EnforcedState previous = states.get(i - 1);
            EnforcedState current = states.get(i);
            previous.setEnd(current.getStart().minusSeconds(1));
        }
        EnforcedState lastState = states.get(states.size() - 1);
        lastState.setEnd(getStartOfFirstStateNextDay(states));
        lastState.setEndsNextDay();
    }

    private LocalTime getStartOfFirstStateNextDay(List<EnforcedState> states) {
        return states.get(0).getStart().plusHours(24).minusSeconds(1);
    }

    private void scheduleStates(LocalDateTime now, List<EnforcedState> states) {
        states.sort(Comparator.comparing(EnforcedState::getStart, Comparator.reverseOrder()));
        for (int i = 0; i < states.size(); i++) {
            EnforcedState state = states.get(i);
            schedule(now, state);
            if (state.isInThePast(now) && hasMorePastStates(states, i)) {
                addRemainingStatesTheNextDay(getRemaining(states, i), now);
                break;
            }
        }
    }

    private void schedule(LocalDateTime now, EnforcedState state) {
        schedule(state, state.getDelay(now));
    }

    private void schedule(EnforcedState state, long delayInSeconds) {
        LOG.debug("Schedule state change for {} in {}", state, Duration.ofSeconds(delayInSeconds));
        scheduledExecutorService.schedule(() -> {
            if (state.endsAfter(currentTime.get())) {
                state.resetConfirmations();
                schedule(state, Duration.ofHours(24).getSeconds());
                return;
            }
            hueApi.putState(state.getId(), state.getBrightness(), null, null, state.getCt());
            if (!hueApi.getState(state.getId()).isReachable()) {
                LOG.warn("Light {} not reachable, try again", state.getId());
                state.resetConfirmations();
                schedule(state, 1);
                return;
            }
            if (!state.isFullyConfirmed()) {
                state.addConfirmation();
                schedule(state, 1);
            } else {
                state.resetConfirmations();
                schedule(state, Duration.ofHours(24).getSeconds());
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
        remainingStates.forEach(state -> schedule(state, secondsUntilNextDayFromStart(state, now)));
    }

    private long secondsUntilNextDayFromStart(EnforcedState state, LocalDateTime now) {
        LocalDateTime startDateTime = LocalDateTime.of(now.toLocalDate(), state.getStart());
        return Duration.between(now, startDateTime).plusHours(24).getSeconds();
    }
}
