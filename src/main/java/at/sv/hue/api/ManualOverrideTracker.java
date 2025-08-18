package at.sv.hue.api;

public interface ManualOverrideTracker {

    void onManuallyOverridden(String id);

    boolean isManuallyOverridden(String id);

    void onLightTurnedOn(String id);

    void onLightTurnedOnBySyncedScene(String id);

    void onLightOff(String id);

    boolean isOff(String id);

    boolean wasTurnedOnBySyncedScene(String id);
}
