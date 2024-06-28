package at.sv.hue.api;

public interface ManualOverrideTracker {

    void onManuallyOverridden(String id);

    boolean isManuallyOverridden(String id);

    void onLightTurnedOn(String id);

    void onLightOff(String id);

    boolean isOff(String id);

    boolean wasJustTurnedOn(String id);

    void onAutomaticallyAssigned(String id);
}
