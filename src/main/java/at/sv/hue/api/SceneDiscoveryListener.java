package at.sv.hue.api;

public interface SceneDiscoveryListener {

    /**
     * Called when a scene is created, or its name was changed.
     *
     * @param sceneId the v2 resource ID of the modified scene
     */
    void onSceneCreatedOrRenamed(String sceneId);

    /**
     * Called when a scene is deleted.
     *
     * @param sceneId the v2 resource ID of the deleted scene
     */
    void onSceneDeleted(String sceneId);
}
