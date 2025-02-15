package at.sv.hue.api;

public interface SceneEventListener {
    void onSceneActivated(String id);

    boolean wasRecentlyAffectedByNormalScene(String id);

    boolean wasRecentlyAffectedBySyncedScene(String id);
}
