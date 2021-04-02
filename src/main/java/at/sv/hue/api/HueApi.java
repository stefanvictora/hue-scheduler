package at.sv.hue.api;

import at.sv.hue.LightState;

import java.util.List;

public interface HueApi {
    LightState getLightState(int id);

    boolean putState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, boolean groupState);

    List<Integer> getGroupLights(int groupId);

    String getGroupName(int groupId);

    int getLightId(String name);

    String getLightName(int id);
}
