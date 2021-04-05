package at.sv.hue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class StateSchedulerImpl implements StateScheduler {

    private final ScheduledExecutorService scheduledExecutorService;
    private final Supplier<ZonedDateTime> currentTime;

    public StateSchedulerImpl(ScheduledExecutorService scheduledExecutorService, Supplier<ZonedDateTime> currentTime) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.currentTime = currentTime;
    }

    @Override
    public void schedule(Runnable runnable, ZonedDateTime start) {
        scheduledExecutorService.schedule(runnable, Duration.between(currentTime.get(), start).toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        scheduledExecutorService.scheduleAtFixedRate(command, initialDelay, period, unit);
    }
}
