package at.sv.hue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class TestStateScheduler implements StateScheduler {

    private final List<ScheduledRunnable> scheduledRunnables;

    public TestStateScheduler() {
        scheduledRunnables = new ArrayList<>();
    }

    @Override
    public void schedule(Runnable runnable, ZonedDateTime start) {
        scheduledRunnables.add(new ScheduledRunnable(start, runnable));
    }

    @Override
    public void scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        
    }

    public List<ScheduledRunnable> getScheduledStates() {
        ArrayList<ScheduledRunnable> states = new ArrayList<>(scheduledRunnables);
        states.sort(Comparator.comparing(ScheduledRunnable::getStart));
        return states;
    }

    public void clear() {
        scheduledRunnables.clear();
    }
}
