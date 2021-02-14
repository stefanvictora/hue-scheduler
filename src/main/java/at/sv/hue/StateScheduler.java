package at.sv.hue;

import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

public interface StateScheduler {
    void schedule(Runnable runnable, ZonedDateTime start);

    void scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);
}
