package at.sv.hue.api;

import java.util.List;

public interface HueApi {
    /**
     * @throws BridgeConnectionFailure     if the bridge could not be reached
     * @throws BridgeAuthenticationFailure if the bridge rejected the request due to an unauthorized username
     * @throws HueApiFailure               if another api error occurs
     */
    void assertConnection();

    /**
     * @return the light state for the light of the given id. Not null.
     * @throws LightNotFoundException if no light with id was found
     * @throws HueApiFailure          if the api call failed
     */
    LightState getLightState(int id);

    /**
     * @return true if the put api call was successful, false if not because the light is off.
     * All other error cases are thrown as {@link HueApiFailure} exceptions.
     * @throws HueApiFailure if the api call failed
     */
    boolean putState(int id, Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat, String effect, Boolean on,
                     Integer transitionTime, boolean groupState);

    /**
     * @return the lights associated with the group of the given id. Not null.
     * @throws GroupNotFoundException if no group with given id was found
     * @throws EmptyGroupException    if the group has no lights associated
     * @throws HueApiFailure          if the api call failed
     */
    List<Integer> getGroupLights(int groupId);

    /**
     * @throws GroupNotFoundException if no group with given name was found
     * @throws HueApiFailure          if the api call failed
     */
    int getGroupId(String name);

    /**
     * @throws GroupNotFoundException if no group with given id was found
     * @throws HueApiFailure          if the api call failed
     */
    String getGroupName(int groupId);

    /**
     * @throws LightNotFoundException if no light with given name was found
     * @throws HueApiFailure          if the api call failed
     */
    int getLightId(String name);

    /**
     * @throws LightNotFoundException if no light with given id was found
     * @throws HueApiFailure          if the api call failed
     */
    String getLightName(int id);

    /**
     * @return the light capabilities, or {@link LightCapabilities#NO_CAPABILITIES} if no capabilities were found. Not null.
     * @throws LightNotFoundException if no light with given id was found
     * @throws HueApiFailure          if the api call failed
     */
    LightCapabilities getLightCapabilities(int id);
}
