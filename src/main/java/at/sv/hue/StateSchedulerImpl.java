package at.sv.hue;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
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
        scheduler.schedule(() -> executor.submit(logUncaughtException(runnable)),
                Duration.between(currentTime.get(), start).toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void scheduleAtFixedRate(Runnable runnable, long initialDelay, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(() -> executor.submit(logUncaughtException(runnable)), initialDelay, period, unit);
    }

    private Runnable logUncaughtException(Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (Exception e) {
                log.error("Uncaught exception: {}",  e.getLocalizedMessage(), e);
            }
        };
    }
}
