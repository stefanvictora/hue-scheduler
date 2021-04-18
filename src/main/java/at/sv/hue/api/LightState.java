package at.sv.hue.api;

public final class LightState {

    private final boolean reachable;
    private final int brightness;
    private final Integer colorTemperature;
    private final Double x;
    private final Double y;
    private final boolean on;
    private final String effect;

    public LightState(int brightness, Integer colorTemperature, Double x, Double y, String effect, boolean reachable, boolean on) {
        this.reachable = reachable;
        this.on = on;
        this.brightness = brightness;
        this.colorTemperature = colorTemperature;
        this.x = x;
        this.y = y;
        this.effect = effect;
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

    public boolean isOn() {
        return on;
    }

    public boolean isUnreachableOrOff() {
        return !reachable || !on;
    }

    public String getEffect() {
        return effect;
    }

    public boolean isColorLoopEffect() {
        return "colorloop".equals(effect);
    }
}
