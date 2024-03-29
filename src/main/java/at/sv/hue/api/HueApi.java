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
     * @throws HueApiFailure if the api call failed
     */
    LightState getLightState(int id);

    /**
     * @param id the id of the group
     * @return a list of light states for all the contained lights of the group
     * @throws HueApiFailure if the api call failed
     */
    List<LightState> getGroupStates(int id);

    /**
     * @return true if the put api call was successful, false if not because the light is off.
     * All other error cases are thrown as {@link HueApiFailure} exceptions.
     * @throws HueApiFailure if the api call failed
     */
    boolean putState(PutCall putCall);

    /**
     * @return the lights associated with the group of the given id. Not null.
     * @throws GroupNotFoundException if no group with given id was found
     * @throws EmptyGroupException    if the group has no lights associated
     * @throws HueApiFailure          if the api call failed
     */
    List<Integer> getGroupLights(int groupId);

    /**
     * @return a list of group ids the light is assigned to, not null
     */
    List<Integer> getAssignedGroups(int lightId);

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

    /**
     * Derives the group capabilities from all the contained lights. We take the maximum of all summed capabilities.
     * I.e., if a group contains a CT-only and a Brightness-only light, CT and Brightness are returned as group capabilities.
     * <br>
     * The color gamut is chosen in the order C > B > A > null. CT-max is the max value seen across all group lights. CT-min is the respective min value.
     *
     * @return the light capabilities derived from the given group
     * @throws GroupNotFoundException if no group with given id was found
     * @throws HueApiFailure          if the api call failed
     */
    LightCapabilities getGroupCapabilities(int id);

    /**
     * Clears caches for both the /lights and /groups resources, so that up-to-date information is fetched next time
     */
    void clearCaches();
}
