package at.sv.hue.api;

import at.sv.hue.ColorMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.EnumSet;

@Data
@AllArgsConstructor
@Builder
public final class LightState {
    private final String id;
    private final Integer brightness;
    private final Integer colorTemperature;
    private final Double x;
    private final Double y;
    private final String effect;
    private final ColorMode colormode;
    private final boolean on;
    @Builder.Default
    private final LightCapabilities lightCapabilities = LightCapabilities.builder().build();

    public boolean isColorLoopEffect() {
        return "colorloop".equals(getEffect());
    }

    public boolean isColorSupported() {
        return getCapabilities().contains(Capability.COLOR);
    }

    public boolean isCtSupported() {
        return getCapabilities().contains(Capability.COLOR_TEMPERATURE);
    }

    public boolean isBrightnessSupported() {
        return getCapabilities().contains(Capability.BRIGHTNESS);
    }

    public boolean isOnOffSupported() {
        return getCapabilities().contains(Capability.ON_OFF);
    }

    private EnumSet<Capability> getCapabilities() {
        return lightCapabilities.getCapabilities();
    }

    public boolean isOff() {
        return !on;
    }
}
