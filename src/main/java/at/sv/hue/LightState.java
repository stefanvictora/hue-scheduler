package at.sv.hue;

public final class LightState {

    private final String name;
    private final boolean reachable;
    private final int brightness;
    private final Integer colorTemperature;
    private final Double x;
    private final Double y;

    public LightState(String name, int brightness, Integer colorTemperature, Double x, Double y, boolean reachable) {
        this.name = name;
        this.reachable = reachable;
        this.brightness = brightness;
        this.colorTemperature = colorTemperature;
        this.x = x;
        this.y = y;
    }

    public String getName() {
        return name;
    }

    public int getBrightness() {
        return brightness;
    }

    public Integer getColorTemperature() {
        return colorTemperature;
    }

    public Double getX() {
        return x;
    }

    public Double getY() {
        return y;
    }

    public boolean isReachable() {
        return reachable;
    }
}
