package at.sv.hue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Getter
public class PreviousScheduledState {
    private final ScheduledState scheduledState;
    private final ZonedDateTime startDate;

    public boolean isNullState() {
        return scheduledState.isNullState();
    }

    public ZonedDateTime getDefinedStart() {
        return scheduledState.getDefinedStart(startDate);
    }
}
