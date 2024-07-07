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

    public String getId() {
        return scheduledState.getId();
    }

    public ZonedDateTime getStart() {
        return scheduledState.getStart(definedStart);
    }

    public ZonedDateTime getEnd() {
        return scheduledState.getEnd(); // todo: this seems not right; as it will change and not be a snapshot
    }

    public boolean isForced() {
        return scheduledState.isForced();
    }

    public boolean isOn() {
        return scheduledState.isOn();
    }

    public boolean hasTransitionBefore() {
        return scheduledState.hasTransitionBefore();
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
