package at.sv.hue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class TestStateScheduler implements StateScheduler {

    private final List<ScheduledState> scheduledStates;

    public TestStateScheduler() {
        scheduledStates = new ArrayList<>();
    }

    @Override
    public void schedule(Runnable runnable, ZonedDateTime start) {
        scheduledStates.add(new ScheduledState(start, runnable));
    }

    @Override
    public void scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        
    }

    public List<ScheduledState> getScheduledStates() {
        ArrayList<ScheduledState> states = new ArrayList<>(scheduledStates);
        states.sort(Comparator.comparing(ScheduledState::getStart));
        return states;
    }

    public void clear() {
        scheduledStates.clear();
    }
}
