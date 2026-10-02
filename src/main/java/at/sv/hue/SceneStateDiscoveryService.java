package at.sv.hue;

import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.SceneDiscoveryListener;
import at.sv.hue.api.SceneNotFoundException;
import at.sv.hue.time.StartTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class SceneStateDiscoveryService implements SceneDiscoveryListener {

    private final HueApi api;
    private final SceneStateFactory stateFactory;
    private final ScheduledStateRegistry stateRegistry;
    private final Consumer<String> groupStatesRescheduler;
    private final Consumer<String> manualOverrideReset;
    private final Consumer<Runnable> retryScheduler;
    private final boolean enabled;

    // Guarded by this; an update remains current across reads and retries until superseded.
    private final Map<String, SceneUpdate> pendingUpdates = new HashMap<>();

    private enum UpdateMode { STARTUP, LIVE }

    private static final class SceneUpdate {
        private final String sceneId;

        // Object identity distinguishes consecutive changes to the same scene.
        private SceneUpdate(String sceneId) {
            this.sceneId = sceneId;
        }
    }

    public SceneStateDiscoveryService(HueApi api, StartTimeProvider startTimeProvider,
                                      ScheduledStateRegistry stateRegistry, Consumer<String> groupStatesRescheduler,
                                      Consumer<String> manualOverrideReset, Consumer<Runnable> retryScheduler,
                                      int minTrBeforeGapInMinutes, int brightnessOverrideThreshold,
                                      int colorTemperatureOverrideThresholdKelvin, double colorOverrideThreshold,
                                      boolean enabled, boolean interpolateAll) {
        this.api = api;
        this.stateFactory = new SceneStateFactory(api, startTimeProvider, minTrBeforeGapInMinutes,
                brightnessOverrideThreshold, colorTemperatureOverrideThresholdKelvin, colorOverrideThreshold,
                interpolateAll);
        this.stateRegistry = stateRegistry;
        this.groupStatesRescheduler = groupStatesRescheduler;
        this.manualOverrideReset = manualOverrideReset;
        this.retryScheduler = retryScheduler;
        this.enabled = enabled;
    }

    public void discoverSceneStates() {
        withDiscoveryContext(() -> {
            for (Identifier scene : api.getAllScenes()) {
                loadAndApply(beginUpdate(scene.id()), () -> scene, UpdateMode.STARTUP);
            }
        });
    }

    @Override
    public void onSceneCreatedOrRenamed(String sceneId) {
        if (enabled) {
            refreshScene(beginUpdate(sceneId));
        }
    }

    @Override
    public void onSceneDeleted(String sceneId) {
        if (!enabled) {
            return;
        }
        withDiscoveryContext(() -> {
            log.info("Scene '{}' deleted. Removing affected states.", sceneId);
            applyUpdate(beginUpdate(sceneId), null, UpdateMode.LIVE);
        });
    }

    private void refreshScene(SceneUpdate update) {
        withDiscoveryContext(() -> loadAndApply(update, () -> api.getScene(update.sceneId), UpdateMode.LIVE));
    }

    private void loadAndApply(SceneUpdate update, Supplier<Identifier> sceneLookup, UpdateMode mode) {
        ScheduledState replacement;
        try {
            replacement = stateFactory.create(sceneLookup.get());
        } catch (GroupNotFoundException | SceneNotFoundException e) {
            // Deleted scenes/groups no longer declare a schedule.
            replacement = null;
        } catch (ApiFailure | BridgeConnectionFailure e) {
            log.warn("Failed to load scene '{}'; retaining its previous definition and retrying: {}",
                    update.sceneId, e.getLocalizedMessage());
            scheduleRetry(update);
            return;
        } catch (Exception e) {
            log.error("Failed to create scheduled state for scene '{}': {}", update.sceneId, e.getLocalizedMessage(), e);
            synchronized (this) {
                pendingUpdates.remove(update.sceneId, update);
            }
            return;
        }
        // Only reads are retried. Failures while applying a completed update propagate to the caller.
        applyUpdate(update, replacement, mode);
    }

    private synchronized SceneUpdate beginUpdate(String sceneId) {
        SceneUpdate update = new SceneUpdate(sceneId);
        pendingUpdates.put(sceneId, update);
        return update;
    }

    private synchronized void scheduleRetry(SceneUpdate update) {
        if (pendingUpdates.get(update.sceneId) == update) {
            retryScheduler.accept(() -> retryScene(update));
        }
    }

    private void retryScene(SceneUpdate update) {
        synchronized (this) {
            if (pendingUpdates.get(update.sceneId) != update) {
                return;
            }
        }
        refreshScene(update);
    }

    private synchronized void applyUpdate(SceneUpdate update, ScheduledState replacement, UpdateMode mode) {
        if (!pendingUpdates.remove(update.sceneId, update)) {
            return;
        }
        if (mode == UpdateMode.STARTUP) {
            // Startup discovery adds to file-based definitions; only live edits replace them.
            if (replacement != null) {
                stateRegistry.addState(replacement);
            }
            return;
        }
        List<ScheduledState> removed = stateRegistry.replaceSceneStates(update.sceneId, replacement);
        removed.forEach(ScheduledState::invalidate);
        Set<String> affectedGroups = new LinkedHashSet<>();
        removed.forEach(state -> affectedGroups.add(state.getId()));
        if (replacement != null) {
            affectedGroups.add(replacement.getId());
        }
        affectedGroups.forEach(this::rescheduleGroupStates);
    }

    private void rescheduleGroupStates(String affectedGroup) {
        List<ScheduledState> states = stateRegistry.findStatesForId(affectedGroup);
        if (states == null) {
            return;
        }
        states.forEach(state -> manualOverrideReset.accept(state.getId()));
        groupStatesRescheduler.accept(affectedGroup);
    }

    private static void withDiscoveryContext(Runnable action) {
        String previousContext = MDC.get("context");
        MDC.put("context", "scene-discovery");
        try {
            action.run();
        } finally {
            if (previousContext == null) {
                MDC.remove("context");
            } else {
                MDC.put("context", previousContext);
            }
        }
    }
}
