package at.sv.hue.api;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;

public class ManualOverrideTrackerImpl implements ManualOverrideTracker {

    private static final TrackedState DEFAULT_STATE = new TrackedState();

    private final HashMap<String, TrackedState> trackedStatesPerId;

    public ManualOverrideTrackerImpl() {
        trackedStatesPerId = new HashMap<>();
    }

    @Override
    public void onManuallyOverridden(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(true);
        trackedState.setJustTurnedOn(false);
    }

    @Override
    public boolean isManuallyOverridden(String id) {
        return getOrDefaultState(id).isManuallyOverridden();
    }

    @Override
    public void onLightTurnedOn(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(false);
        trackedState.setLightIsOff(false);
        trackedState.setJustTurnedOn(true);
    }

    @Override
    public void onLightOff(String id) {
        getOrCreateTrackedState(id).setLightIsOff(true);
    }

    @Override
    public boolean isOff(String id) {
        return getOrDefaultState(id).isLightIsOff();
    }

    @Override
    public boolean wasJustTurnedOn(String id) {
        return getOrDefaultState(id).isJustTurnedOn();
    }

    @Override
    public void onAutomaticallyAssigned(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(false);  // maybe not needed, as this flag is overridden also on light-on events
        trackedState.setJustTurnedOn(false);
    }

    private TrackedState getOrCreateTrackedState(String id) {
        return trackedStatesPerId.computeIfAbsent(id, i -> new TrackedState());
    }

    private TrackedState getOrDefaultState(String id) {
        return trackedStatesPerId.getOrDefault(id, DEFAULT_STATE);
    }

    @Getter
    @Setter
    private static final class TrackedState {
        private boolean manuallyOverridden;
        private boolean justTurnedOn;
        private boolean lightIsOff;
    }
}
