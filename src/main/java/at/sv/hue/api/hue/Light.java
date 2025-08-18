package at.sv.hue.api.hue;

import at.sv.hue.ColorMode;
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
    Effects effects;
    String type;

    LightState getLightState(boolean unavailable) {
        return new LightState(id, getBrightnessV1(), getCt(), getX(), getY(), getEffect(), getColorMode(), isOn(),
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
        if (!isMirekValid()) {
            return null;
        }
        return color_temperature.mirek;
    }

    private boolean isMirekValid() {
        return color_temperature != null && color_temperature.mirek_valid;
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

    private String getEffect() {
        if (effects == null) {
            return null;
        }
        return effects.getStatus();
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
        if (effects != null) {
            builder.effects(effects.getAvailableEffects());
        }
        return builder.capabilities(capabilities).build();
    }

    private ColorMode getColorMode() {
        if (getCt() != null) {
            return ColorMode.CT;
        }
        if (color != null) {
            return ColorMode.XY;
        }
        return ColorMode.NONE;
    }

    @Data
    private static final class Effects {
        String[] status_values;
        String status;
        String[] effect_values;

        String getStatus() {
            if ("no_effect".equals(status)) {
                return "none";
            }
            return status;
        }

        List<String> getAvailableEffects() {
            return Arrays.stream(effect_values)
                         .filter(effect -> !"no_effect".equals(effect))
                         .toList();
        }
    }
}

