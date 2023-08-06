package at.sv.hue.api;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
@Builder
public final class LightCapabilities {

    public static final LightCapabilities NO_CAPABILITIES = new LightCapabilities(null, null, null);

    private final Double[][] colorGamut;
    private final Integer ctMin;
    private final Integer ctMax;

    public boolean isColorSupported() {
        return colorGamut != null;
    }

    public boolean isCtSupported() {
        return ctMin != null && ctMax != null;
    }
}
