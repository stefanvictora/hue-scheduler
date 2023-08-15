package at.sv.hue.api;

public interface ManualOverrideTracker {

    void onManuallyOverridden(String id);

    boolean isManuallyOverridden(String id);

    void onLightTurnedOn(String id);

    boolean shouldEnforceSchedule(String id);

    void onAutomaticallyAssigned(String id);
}
