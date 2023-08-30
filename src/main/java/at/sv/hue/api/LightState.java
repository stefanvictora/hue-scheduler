package at.sv.hue.api;

import at.sv.hue.ColorMode;
import lombok.Builder;
import lombok.Data;

import java.util.EnumSet;

@Data
@Builder
public final class LightState {
    private final Integer brightness;
    private final Integer colorTemperature;
    private final Double x;
    private final Double y;
    private final Integer hue;
    private final Integer sat;
    private final String effect;
    private final String colormode;
    private final boolean reachable;
    private final boolean on;
    @Builder.Default
    private final EnumSet<Capability> capabilities = EnumSet.noneOf(Capability.class);

    public ColorMode getColormode() {
        return ColorMode.parse(colormode);
    }
    
    public boolean isColorLoopEffect() {
        return "colorloop".equals(getEffect());
    }
    
    public boolean isColorSupported() {
        return capabilities.contains(Capability.COLOR);
    }
    
    public boolean isCtSupported() {
        return capabilities.contains(Capability.COLOR_TEMPERATURE);
    }
    
    public boolean isBrightnessSupported() {
        return capabilities.contains(Capability.BRIGHTNESS);
    }

    public boolean isOnOffSupported() {
        return capabilities.contains(Capability.ON_OFF);
    }

    public boolean isOff() {
        return !on;
    }
}
