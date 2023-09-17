package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.PutCall;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Getter
@EqualsAndHashCode
public class ScheduledStateSnapshot {
    private final ScheduledState scheduledState;
    private final ZonedDateTime definedStart;

    public ZonedDateTime getStart() {
        return scheduledState.getStart(definedStart);
    }

    public boolean isForced() {
        return scheduledState.isForced();
    }

    public boolean isNullState() {
        return scheduledState.isNullState();
    }

    public PutCall getPutCall(ZonedDateTime now) {
        return scheduledState.getPutCall(now, definedStart);
    }

    public LightCapabilities getCapabilities() {
        return scheduledState.getCapabilities();
    }
}
