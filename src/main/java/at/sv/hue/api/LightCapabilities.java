package at.sv.hue.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.EnumSet;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public final class LightCapabilities {
    private final String colorGamutType;
    private final Double[][] colorGamut;
    private final Integer ctMin;
    private final Integer ctMax;
    @Builder.Default
    private final EnumSet<Capability> capabilities = EnumSet.noneOf(Capability.class);
    private final List<String> effects;

    public boolean isColorSupported() {
        return capabilities.contains(Capability.COLOR);
    }

    public boolean isCtSupported() {
        return capabilities.contains(Capability.COLOR_TEMPERATURE);
    }

    public boolean isBrightnessSupported() {
        return capabilities.contains(Capability.BRIGHTNESS);
    }
}
