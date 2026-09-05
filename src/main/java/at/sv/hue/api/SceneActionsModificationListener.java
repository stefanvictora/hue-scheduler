package at.sv.hue.api;

public interface SceneActionsModificationListener {
    /**
     * Called when a scene's light actions are modified (e.g., user changes light colors/brightness in a scene).
     *
     * @param sceneId the v2 resource ID of the modified scene
     */
    void onSceneActionsModified(String sceneId);
}
