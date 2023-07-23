package at.sv.hue.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class LightCapabilities {

    public static final LightCapabilities NO_CAPABILITIES = new LightCapabilities(null, null, null);

    private final Double[][] colorGamut;
    private final Integer ctMin;
    private final Integer ctMax;

    public LightCapabilities(Double[][] colorGamut, Integer ctMin, Integer ctMax) {
        this.colorGamut = colorGamut;
        this.ctMin = ctMin;
        this.ctMax = ctMax;
    }

    public boolean isColorSupported() {
        return colorGamut != null;
    }

    public boolean isCtSupported() {
        return ctMin != null && ctMax != null;
    }
}
