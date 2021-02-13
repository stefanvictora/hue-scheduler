package at.sv.hue;

import java.time.ZonedDateTime;

final class ScheduledState implements Runnable {
    private final ZonedDateTime start;
    private final Runnable runnable;

    public ScheduledState(ZonedDateTime start, Runnable runnable) {
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
