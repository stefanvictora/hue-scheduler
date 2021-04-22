package at.sv.hue;

import java.time.ZonedDateTime;

final class ScheduledRunnable implements Runnable {
    private final ZonedDateTime start;
    private final ZonedDateTime end;
    private final Runnable runnable;

    public ScheduledRunnable(ZonedDateTime start, ZonedDateTime end, Runnable runnable) {
        this.start = start;
        this.end = end;
        this.runnable = runnable;
    }

    public ZonedDateTime getStart() {
        return start;
    }

    public ZonedDateTime getEnd() {
        return end;
    }

    @Override
    public void run() {
        runnable.run();
    }
}
