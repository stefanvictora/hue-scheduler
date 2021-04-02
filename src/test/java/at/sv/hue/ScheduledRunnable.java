package at.sv.hue;

import java.time.ZonedDateTime;

final class ScheduledRunnable implements Runnable {
    private final ZonedDateTime start;
    private final Runnable runnable;

    public ScheduledRunnable(ZonedDateTime start, Runnable runnable) {
        this.start = start;
        this.runnable = runnable;
    }

    public ZonedDateTime getStart() {
        return start;
    }

    @Override
    public void run() {
        runnable.run();
    }
}
