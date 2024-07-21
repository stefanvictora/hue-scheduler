package at.sv.hue.api;

import java.util.List;

public interface HueApi {
    /**
     * @throws BridgeConnectionFailure     if the bridge could not be reached
     * @throws BridgeAuthenticationFailure if the bridge rejected the request due to an unauthorized username
     * @throws ApiFailure                  if another api error occurs
     */
    void assertConnection();

    /**
     * @param id the id of the light. For Hue this is the id_v1.
     * @return the identifier of the light, containing id and name
     * @throws LightNotFoundException if no light with given id was found
     * @throws ApiFailure             if the api call failed
     */
    Identifier getLightIdentifier(String id);

    /**
     * @param id the id of the light. For Hue this is the id_v1.
     * @return the identifier of the light, containing id and name
     * @throws GroupNotFoundException if no group with given id was found
     * @throws ApiFailure             if the api call failed
     */
    Identifier getGroupIdentifier(String id);

    /**
     * @throws ResourceNotFoundException if no light with given name was found
     * @throws ApiFailure                if the api call failed
     */
    Identifier getLightIdentifierByName(String name);

    /**
     * @throws GroupNotFoundException if no group with given name was found
     * @throws ApiFailure             if the api call failed
     */
    Identifier getGroupIdentifierByName(String name);

    /**
     * @return the up-to-date light state for the light of the given id. Not null.
     * @throws ApiFailure if the api call failed
     */
    LightState getLightState(String id);

    /**
     * @param id the id of the group
     * @return an up-to-date list of light states for all the contained lights of the group, ignoring unknown lights
     * @throws ApiFailure if the api call failed
     */
    List<LightState> getGroupStates(String id);

    /**
     * @return true, iff the light is currently considered off by the bridge
     */
    boolean isLightOff(String id);

    /**
     * @return true, iff the group is currently considered fully off by the bridge
     */
    boolean isGroupOff(String id);

    /**
     * @throws ApiFailure if the api call failed
     */
    void putState(PutCall putCall);

    /**
     * @return the lights associated with the group of the given id. Not null.
     * @throws GroupNotFoundException if no group with given id was found
     * @throws EmptyGroupException    if the group has no lights associated
     * @throws ApiFailure             if the api call failed
     */
    List<String> getGroupLights(String groupId);

    /**
     * @return the lights and group id related to the given scene. If not found, empty list. Not null.
     * @throws ApiFailure if the api call failed
     */
    List<String> getAffectedIdsByScene(String sceneId);

    /**
     * @return a list of group ids the light is assigned to, not null
     */
    List<String> getAssignedGroups(String lightId);

    /**
     * @return the light capabilities. Not null.
     * @throws LightNotFoundException if no light with given id was found
     * @throws ApiFailure             if the api call failed
     */
    LightCapabilities getLightCapabilities(String id);

    /**
     * Derives the group capabilities from all the contained lights. We take the maximum of all summed capabilities.
     * I.e., if a group contains a CT-only and a Brightness-only light, CT and Brightness are returned as group capabilities.
     * <br>
     * The color gamut is chosen in the order C > B > A > null. CT-max is the max value seen across all group lights. CT-min is the respective min value.
     *
     * @return the light capabilities derived from the given group
     * @throws GroupNotFoundException if no group with given id was found
     * @throws ApiFailure             if the api call failed
     */
    LightCapabilities getGroupCapabilities(String id);

    /**
     * Creates or updates a existing scene with the given name for the given group id.
     *
     * @param groupId       the id of the group. For Hue this is the groupedLightId
     * @param putCall       the desired state of lights for the scene
     * @param sceneSyncName the name of the scene to create or update
     * @throws GroupNotFoundException if no group with given id was found
     * @throws ApiFailure             if the api call failed
     */
    void createOrUpdateScene(String groupId, PutCall putCall, String sceneSyncName);

    /**
     * Clears caches for both the /lights and /groups resources, so that up-to-date information is fetched next time
     */
    void clearCaches();
}
