package at.sv.hue.api;

import at.sv.hue.ColorMode;
import at.sv.hue.Effect;
import at.sv.hue.Gradient;
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
    private final Effect effect;
    private final Gradient gradient;
    private final ColorMode colormode;
    private final boolean on;
    private final boolean unavailable;
    @Builder.Default
    private final LightCapabilities lightCapabilities = LightCapabilities.builder().build();
    
    public boolean isColorSupported() {
        return getCapabilities().contains(Capability.COLOR);
    }

    public boolean isGradientSupported() {
        return getCapabilities().contains(Capability.GRADIENT);
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

    public static class LightStateBuilder {
        public LightStateBuilder effect(String effect) {
            if (effect == null) {
                this.effect = null;
                return this;
            }
            this.effect = Effect.builder().effect(effect).build();
            return this;
        }

        public LightStateBuilder effect(Effect effect) {
            this.effect = effect;
            return this;
        }
    }
}
