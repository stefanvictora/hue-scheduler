package at.sv.hue.api;

public interface LightEventListener {
    void onLightOff(String id, String uuid);

    void onLightOn(String id, String uuid, boolean physical);

    void runWhenTurnedOn(String id, Runnable runnable);
}
