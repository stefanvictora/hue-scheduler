package at.sv.hue;

import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.SceneDiscoveryListener;
import at.sv.hue.time.StartTimeProvider;

import java.util.List;

public class SceneStateDiscoveryService implements SceneDiscoveryListener {

    private final HueApi api;
    private final StartTimeProvider startTimeProvider;
    private final ScheduledStateRegistry stateRegistry;
    private final int minTrBeforeGapInMinutes;
    private final int brightnessOverrideThreshold;
    private final int colorTemperatureOverrideThresholdKelvin;
    private final double colorOverrideThreshold;

    public SceneStateDiscoveryService(HueApi api, StartTimeProvider startTimeProvider,
                                      ScheduledStateRegistry stateRegistry,
                                      int minTrBeforeGapInMinutes, int brightnessOverrideThreshold,
                                      int colorTemperatureOverrideThresholdKelvin, double colorOverrideThreshold) {
        this.api = api;
        this.startTimeProvider = startTimeProvider;
        this.stateRegistry = stateRegistry;
        this.minTrBeforeGapInMinutes = minTrBeforeGapInMinutes;
        this.brightnessOverrideThreshold = brightnessOverrideThreshold;
        this.colorTemperatureOverrideThresholdKelvin = colorTemperatureOverrideThresholdKelvin;
        this.colorOverrideThreshold = colorOverrideThreshold;
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
    public void onSceneCreatedOrUpdated(String sceneId) {

    }

    @Override
    public void onSceneDeleted(String sceneId) {

    }
}
