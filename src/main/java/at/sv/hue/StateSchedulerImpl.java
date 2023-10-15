package at.sv.hue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class StateSchedulerImpl implements StateScheduler {

    private final ScheduledExecutorService scheduler;
    private final Supplier<ZonedDateTime> currentTime;
    private final ExecutorService executor;

    public StateSchedulerImpl(ScheduledExecutorService scheduler, Supplier<ZonedDateTime> currentTime) {
        this.scheduler = scheduler;
        this.currentTime = currentTime;
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void schedule(Runnable runnable, ZonedDateTime start, ZonedDateTime end) {
        scheduler.schedule(() -> executor.submit(runnable), Duration.between(currentTime.get(), start).toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(() -> executor.submit(command), initialDelay, period, unit);
    }
}
