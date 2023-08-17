package at.sv.hue.api;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;

@Data
@RequiredArgsConstructor
@Builder
public final class LightCapabilities {

    public static final LightCapabilities NO_CAPABILITIES = new LightCapabilities(null, null, null, EnumSet.noneOf(Capability.class));

    private final Double[][] colorGamut;
    private final Integer ctMin;
    private final Integer ctMax;
    private final EnumSet<Capability> capabilities;

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
