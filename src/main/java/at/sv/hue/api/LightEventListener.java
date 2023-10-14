package at.sv.hue.api;

public interface LightEventListener {
    void onLightOff(String idv1, String uuid);

    void onLightOn(String idv1, String uuid, boolean physical);

    void runWhenTurnedOn(String idV1, Runnable runnable);
}
