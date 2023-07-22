package at.sv.hue.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public final class LightState {

    private final int brightness;
    private final Integer colorTemperature;
    private final Double x;
    private final Double y;
    private final String effect;
    private final String colormode;
    private final boolean reachable;
    private final boolean on;

    public boolean isUnreachableOrOff() {
        return !reachable || !on;
    }

    public boolean isColorLoopEffect() {
        return "colorloop".equals(effect);
    }
}
