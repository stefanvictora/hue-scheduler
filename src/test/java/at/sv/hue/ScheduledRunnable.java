package at.sv.hue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Getter
final class ScheduledRunnable implements Runnable {
    private final ZonedDateTime start;
    private final ZonedDateTime end;
    private final Runnable runnable;

    @Override
    public void run() {
        runnable.run();
    }
}
