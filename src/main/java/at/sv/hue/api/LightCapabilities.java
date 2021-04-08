package at.sv.hue.api;

public final class LightCapabilities {

    public static final LightCapabilities NO_CAPABILITIES = new LightCapabilities(null, null, null);

    private final Double[][] gamut;
    private final Integer ctMin;
    private final Integer ctMax;

    public LightCapabilities(Double[][] gamut, Integer ctMin, Integer ctMax) {
        this.gamut = gamut;
        this.ctMin = ctMin;
        this.ctMax = ctMax;
    }

    public boolean isColorSupported() {
        return gamut != null;
    }

    public boolean isCtSupported() {
        return ctMin != null && ctMax != null;
    }

    public Integer getCtMin() {
        return ctMin;
    }

    public Integer getCtMax() {
        return ctMax;
    }

    public Double[][] getColorGamut() {
        return gamut;
    }
}
