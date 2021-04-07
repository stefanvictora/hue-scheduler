package at.sv.hue.api;

import java.util.List;

public interface HueApi {
    LightState getLightState(int id);

    /**
     * @return true if the put api call was successful, false otherwise
     */
    boolean putState(int id, Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat, Boolean on,
                     Integer transitionTime, boolean groupState);

    /**
     * @throws GroupNotFoundException if no group with given id was found
     */
    List<Integer> getGroupLights(int groupId);

    /**
     * @throws GroupNotFoundException if no group with given name was found
     */
    int getGroupId(String name);

    /**
     * @throws GroupNotFoundException if no group with given id was found
     */
    String getGroupName(int groupId);

    /**
     * @throws LightNotFoundException if no light with given name was found
     */
    int getLightId(String name);

    /**
     * @throws LightNotFoundException if no light with given id was found
     */
    String getLightName(int id);

    /**
     * @throws LightNotFoundException if no light with given id was found
     * @return the light capabilities, or {@link LightCapabilities#NO_CAPABILITIES} if no capabilities were found. Not null.
     */
    LightCapabilities getLightCapabilities(int id);
}
