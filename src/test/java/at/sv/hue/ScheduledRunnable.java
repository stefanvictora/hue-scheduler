package at.sv.hue;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

final class ScheduledRunnable implements Runnable {

    private final Runnable runnable;
    private final Duration secondsUntil;

    public ScheduledRunnable(Runnable runnable, long delay, TimeUnit unit) {
        this.runnable = runnable;
        secondsUntil = Duration.of(unit.toSeconds(delay), ChronoUnit.SECONDS);
    }

    @Override
    public void run() {
        runnable.run();
    }

    public Duration getSecondsUntil() {
        return secondsUntil;
    }
}
