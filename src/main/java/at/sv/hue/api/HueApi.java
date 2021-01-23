package at.sv.hue.api;

import at.sv.hue.LightState;

public interface HueApi {
    LightState getState(int id);

    boolean putState(int id, Integer bri, Double x, Double y, Integer ct);
}
