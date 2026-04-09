package at.sv.hue;

import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.SceneDiscoveryListener;
import at.sv.hue.time.StartTimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public class SceneStateDiscoveryService implements SceneDiscoveryListener {

    private final HueApi api;
    private final StartTimeProvider startTimeProvider;
    private final ScheduledStateRegistry stateRegistry;
    private final Supplier<ZonedDateTime> currentTime;
    private final BiConsumer<List<ScheduledState>, ZonedDateTime> initialSchedule;
    private final int minTrBeforeGapInMinutes;
    private final int brightnessOverrideThreshold;
    private final int colorTemperatureOverrideThresholdKelvin;
    private final double colorOverrideThreshold;
    private final boolean enabled;

    public SceneStateDiscoveryService(HueApi api, StartTimeProvider startTimeProvider,
                                      ScheduledStateRegistry stateRegistry, Supplier<ZonedDateTime> currentTime,
                                      BiConsumer<List<ScheduledState>, ZonedDateTime> initialSchedule,
                                      int minTrBeforeGapInMinutes, int brightnessOverrideThreshold,
                                      int colorTemperatureOverrideThresholdKelvin, double colorOverrideThreshold,
                                      boolean enabled) {
        this.api = api;
        this.startTimeProvider = startTimeProvider;
        this.stateRegistry = stateRegistry;
        this.currentTime = currentTime;
        this.initialSchedule = initialSchedule;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.brightnessOverrideThreshold = brightnessOverrideThreshold;
        this.colorTemperatureOverrideThresholdKelvin = colorTemperatureOverrideThresholdKelvin;
        this.colorOverrideThreshold = colorOverrideThreshold;
        this.enabled = enabled;
    }

    public void discoverSceneStates() {
        List<Identifier> scenes = api.getAllScenes();
        for (Identifier scene : scenes) {
            SceneNameParser.ParseResult result = SceneNameParser.parse(scene.name());
            if (result == null) {
                continue;
            }
            ScheduledState state = createScheduledState(scene, result);
            stateRegistry.addState(state);
        }
    }

    private ScheduledState createScheduledState(Identifier scene, SceneNameParser.ParseResult result) {
        Identifier identifier = api.getGroupIdForScene(scene.id());
        List<ScheduledLightState> sceneLightStates = api.getSceneLightStates(scene.id());
        List<String> groupLights = api.getGroupLights(identifier.id());
        return new ScheduledState(identifier, result.timeExpression(), sceneLightStates, groupLights, scene.id(),
                null, null, result.transitionTimeBefore(), parseTransitionTime(result),
                null, startTimeProvider, minTrBeforeGapInMinutes, brightnessOverrideThreshold,
                colorTemperatureOverrideThresholdKelvin, colorOverrideThreshold, null, result.interpolate(),
                true, false);
    }

    private static Integer parseTransitionTime(SceneNameParser.ParseResult result) {
        if (result.transitionTime() == null) {
            return null;
        }
        return InputConfigurationParser.parseTransitionTime("transition-time", result.transitionTime());
    }

    @Override
    public void onSceneCreatedOrRenamed(String sceneId) {
        if (!enabled) {
            return;
        }
        MDC.put("context", "scene-discovery");
        Identifier scene = api.getScene(sceneId);
        if (scene == null) {
            return;
        }
        log.debug("Scene '{}' created or renamed.", scene.name());
        Set<String> affectedGroups = removeAffectedStates(sceneId);
        SceneNameParser.ParseResult result = SceneNameParser.parse(scene.name());
        if (result != null) {
            log.info("Creating new state for scene '{}'.", scene.name());
            ScheduledState state = createScheduledState(scene, result);
            stateRegistry.addState(state);
            affectedGroups.add(state.getId());
        }
        if (affectedGroups.isEmpty()) {
            return;
        }
        Identifier group = api.getGroupIdForScene(sceneId);
        log.debug("Rescheduling states for group '{}'.", group.id());
        rescheduleGroupStates(group.id());
    }

    @Override
    public void onSceneDeleted(String sceneId) {
        if (!enabled) {
            return;
        }
        MDC.put("context", "scene-discovery");
        log.info("Scene '{}' deleted. Removing affected states.", sceneId);
        Set<String> affectedGroups = removeAffectedStates(sceneId);
        for (String affectedGroup : affectedGroups) {
            log.debug("Rescheduling remaining states for group '{}'.", affectedGroup);
            rescheduleGroupStates(affectedGroup);
        }
        MDC.remove("context");
    }

    private Set<String> removeAffectedStates(String sceneId) {
        List<ScheduledState> statesWithSceneId = stateRegistry.findStatesWithSceneId(sceneId);
        statesWithSceneId.forEach(stateRegistry::remove);
        statesWithSceneId.forEach(ScheduledState::invalidate);
        return statesWithSceneId.stream()
                                .map(ScheduledState::getId)
                                .collect(Collectors.toSet());
    }

    private void rescheduleGroupStates(String affectedGroup) {
        List<ScheduledState> states = stateRegistry.findStatesForId(affectedGroup);
        states.forEach(ScheduledState::invalidate);
        initialSchedule.accept(states, currentTime.get());
    }
}
