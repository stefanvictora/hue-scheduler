package at.sv.hue;

import java.time.ZonedDateTime;

public interface StateScheduler {
    void schedule(Runnable runnable, ZonedDateTime start);
}
