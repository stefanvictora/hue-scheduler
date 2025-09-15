package at.sv.hue.api.hue;

import at.sv.hue.ColorMode;
import at.sv.hue.Effect;
import at.sv.hue.Pair;
import at.sv.hue.api.Capability;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

@Data
final class Light implements Resource {
    String id;
    String id_v1;
    ResourceReference owner;
    Metadata metadata;
    On on;
    Dimming dimming;
    ColorTemperature color_temperature;
    Color color;
    Effects effects_v2;
    Gradient gradient;
    String type;

    LightState getLightState(boolean unavailable) {
        return new LightState(id, getBrightnessV1(), getCt(), getX(), getY(), getEffect(), getGradient(), getColorMode(), isOn(),
                unavailable, getCapabilities());
    }

    boolean isOn() {
        return on != null && on.isOn();
    }

    boolean isOff() {
        return !isOn();
    }

    private Integer getBrightnessV1() {
        if (dimming == null) {
            return null;
        }
        return BigDecimal.valueOf(Math.round(dimming.brightness))
                         .multiply(BigDecimal.valueOf(254))
                         .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                         .intValue();
    }

    private Integer getCt() {
        if (color_temperature == null) {
            return null;
        }
        return color_temperature.getMirekIfValid();
    }

    private Double getX() {
        if (color == null) {
            return null;
        }
        return color.xy.x;
    }

    private Double getY() {
        if (color == null) {
            return null;
        }
        return color.xy.y;
    }

    private Effect getEffect() {
        if (effects_v2 == null) {
            return null;
        }
        return effects_v2.getEffect();
    }

    private at.sv.hue.Gradient getGradient() {
        if (gradient == null) {
            return null;
        }
        return gradient.getGradient();
    }

    LightCapabilities getCapabilities() {
        LightCapabilities.LightCapabilitiesBuilder builder = LightCapabilities.builder();
        EnumSet<Capability> capabilities = EnumSet.of(Capability.ON_OFF);
        if (color != null) {
            capabilities.add(Capability.COLOR);
            builder.colorGamutType(color.getGamut_type());
            builder.colorGamut(color.getGamut());
        }
        if (color_temperature != null) {
            capabilities.add(Capability.COLOR_TEMPERATURE);
            builder.ctMin(color_temperature.getCtMin());
            builder.ctMax(color_temperature.getCtMax());
        }
        if (dimming != null) {
            capabilities.add(Capability.BRIGHTNESS);
        }
        if (effects_v2 != null) {
            builder.effects(effects_v2.getAvailableEffects());
        }
        if (gradient != null) {
            capabilities.add(Capability.GRADIENT);
            builder.gradientModes(gradient.mode_values);
            builder.maxGradientPoints(gradient.points_capable);
        }
        return builder.capabilities(capabilities).build();
    }

    private ColorMode getColorMode() {
        if (getCt() != null) {
            return ColorMode.CT;
        }
        if (getGradient() != null) {
            return ColorMode.GRADIENT;
        }
        if (color != null) {
            return ColorMode.XY;
        }
        return ColorMode.NONE;
    }

    @Data
    private static final class Effects {
        EffectsStatus status;
        EffectsAction action;

        Effect getEffect() {
            Integer ct = getCt();
            if (ct != null) { // don't allow both ct and xy to be set
                return new Effect(status.getActiveEffect(), ct, null, null, getSpeed());
            } else {
                return new Effect(status.getActiveEffect(), null, getX(), getY(), getSpeed());
            }
        }

        Integer getCt() {
            if (status.parameters == null) {
                return null;
            }
            ColorTemperature colorTemperature = status.parameters.color_temperature;
            if (colorTemperature == null) { // todo: mutation coverage
                return null;
            }
            return colorTemperature.getMirekIfValid();
        }

        Double getX() {
            if (status.parameters == null) {
                return null;
            }
            Color color = status.parameters.color;
            if (color == null) { // todo: mutation coverage
                return null;
            }
            return color.xy.x;
        }

        Double getY() {
            if (status.parameters == null) {
                return null;
            }
            Color color = status.parameters.color;
            if (color == null) { // todo: mutation coverage
                return null;
            }
            return color.xy.y;
        }

        Double getSpeed() {
            if (status.parameters == null) {
                return null;
            }
            return status.parameters.speed;
        }

        List<String> getAvailableEffects() {
            return Arrays.stream(action.effect_values)
                         .filter(effect -> !"no_effect".equals(effect))
                         .toList();
        }
    }

    @Data
    private static final class EffectsAction {
        String[] effect_values;
    }

    @Data
    private static final class EffectsStatus {
        String effect;
        EffectsParameters parameters;

        String getActiveEffect() {
            if ("no_effect".equals(effect)) {
                return "none";
            }
            return effect;
        }
    }

    @Data
    private static final class EffectsParameters {
        Color color;
        ColorTemperature color_temperature;
        Double speed;
    }

    @Data
    private static final class Gradient {
        List<GradientPoint> points;
        String mode;
        Integer points_capable;
        List<String> mode_values;

        public at.sv.hue.Gradient getGradient() {
            if (points == null) { // todo: mutation coverage
                return null;
            }
            List<Pair<Double, Double>> points = this.points.stream()
                                                           .map(point ->
                                                                   Pair.of(point.color.xy.x, point.color.xy.y)
                                                           )
                                                           .toList();
            return new at.sv.hue.Gradient(points, mode);
        }
    }

    @Data
    static class GradientPoint {
        Color color;
    }
}

