package at.sv.hue.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public class ManualOverrideTrackerImpl implements ManualOverrideTracker {

    private static final TrackedState DEFAULT_STATE = new TrackedState();

    private final ConcurrentHashMap<String, TrackedState> trackedStatesPerId;
    private final Cache<String, String> wasJustTurnedOnIds;

    public ManualOverrideTrackerImpl(Ticker ticker, int justTurnedOnWindowInSeconds) {
        trackedStatesPerId = new ConcurrentHashMap<>();
        wasJustTurnedOnIds = Caffeine.newBuilder()
                                     .ticker(ticker)
                                     .expireAfterWrite(Duration.ofSeconds(justTurnedOnWindowInSeconds))
                                     .build();
    }

    @Override
    public void onManuallyOverridden(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(true);
        wasJustTurnedOnIds.invalidate(id);
        trackedState.setTurnedOnBySyncedScene(false);
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
        wasJustTurnedOnIds.put(id, id);
    }

    @Override
    public void onLightTurnedOnBySyncedScene(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setTurnedOnBySyncedScene(true);
    }

    @Override
    public void onLightOff(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setLightIsOff(true);
        trackedState.setTurnedOnBySyncedScene(false);
        wasJustTurnedOnIds.invalidate(id);
    }

    @Override
    public boolean isOff(String id) {
        return getOrDefaultState(id).isLightIsOff();
    }

    @Override
    public boolean wasJustTurnedOn(String id) {
        return wasJustTurnedOnIds.getIfPresent(id) != null;
    }

    @Override
    public boolean wasTurnedOnBySyncedScene(String id) {
        return getOrDefaultState(id).isTurnedOnBySyncedScene();
    }

    @Override
    public void onAutomaticallyAssigned(String id) {
        TrackedState trackedState = getOrCreateTrackedState(id);
        trackedState.setManuallyOverridden(false);  // maybe not needed, as this flag is overridden also on light-on events
        wasJustTurnedOnIds.invalidate(id);
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
        private boolean turnedOnBySyncedScene;
        private boolean lightIsOff;
    }
}
