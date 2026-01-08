package at.sv.hue.api;

import at.sv.hue.ScheduledLightState;

import java.util.List;

public interface HueApi extends ResourceModificationEventListener {
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
     * @throws ApiFailure if the api call failed
     */
    void putGroupState(PutCall putCall);

    /**
     * @throws ApiFailure if the api call failed
     */
    void putSceneState(String groupId, List<PutCall> list);

    /**
     * @return the lights associated with the group of the given id. Not null.
     * @throws GroupNotFoundException if no group with given id was found
     * @throws EmptyGroupException    if the group has no lights associated
     * @throws ApiFailure             if the api call failed
     */
    List<String> getGroupLights(String groupId);

    /**
     * @return the name of the scene with the given ID. Null if scene not found.
     * @throws ApiFailure if the api call failed
     */
    String getSceneName(String sceneId);

    /**
     * @return the lights and group id related to the given scene and if they are already on. If not found, empty list. Not null.
     * @throws ApiFailure if the api call failed
     */
    List<AffectedId> getAffectedIdsByScene(String sceneId);

    /**
     * @return the lights and group id related to the given device. If not found, empty list. Not null.
     * @throws ApiFailure if the api call failed
     */
    List<String> getAffectedIdsByDevice(String deviceId);

    /**
     * @return a list of group ids the light is assigned to, not null
     */
    List<String> getAssignedGroups(String lightId);

    /**
     * Used for scene sync to determine which additional areas to consider for the given lights.
     * This is primarily used by Home Assistant to resolve areas that contain the specified lights,
     * enabling scene synchronization across related areas. For Hue implementations, this typically
     * returns an empty list as Hue uses a different grouping model.
     *
     * @param lightIds the light IDs to resolve areas for; must not be null
     * @return a list of GroupInfo objects representing additional areas and their contained light IDs;
     * each GroupInfo contains an area/group ID and the list of light IDs it contains; never null
     */
    List<GroupInfo> getAdditionalAreas(List<String> lightIds);

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
     * Retrieves the scheduled light states for a specific scene within a given group.
     * The light states represent the detailed configurations (e.g., brightness, color temperature)
     * assigned to each light in the group for the specified scene.
     *
     * @param groupId   the identifier of the group for which the scene light states are needed; must not be null or empty
     * @param sceneName the name of the scene within the group whose light states are to be retrieved; must not be null or empty
     * @return a list of ScheduledLightState objects representing the light states associated with the specified scene;
     * never null.
     * @throws SceneNotFoundException if no scene with the given name exists for the specified group
     * @throws GroupNotFoundException if no group with the given id exists
     * @throws NonUniqueNameException if multiple scenes with the same name exist for the specified group
     */
    List<ScheduledLightState> getSceneLightStates(String groupId, String sceneName);

    /**
     * Creates or updates a existing scene with the given name for the given group id.
     *
     * @param groupId       the id of the group. For Hue this is the groupedLightId
     * @param sceneSyncName the name of the scene to create or update
     * @param putCalls      put calls for lights in the scene. If a light is missing, it is considered off for the scene
     * @throws GroupNotFoundException if no group with given id was found
     * @throws ApiFailure             if the api call failed
     */
    void createOrUpdateScene(String groupId, String sceneSyncName, List<PutCall> putCalls);

    /**
     * Clears caches for both the /lights and /groups resources, so that up-to-date information is fetched next time
     */
    void clearCaches();
}
