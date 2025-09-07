package at.sv.hue.api.hue;

import at.sv.hue.ColorMode;
import at.sv.hue.Effect;
import at.sv.hue.Gradient;
import at.sv.hue.Pair;
import at.sv.hue.ScheduledLightState;
import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupInfo;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.api.RateLimiter;
import at.sv.hue.color.ColorModeConverter;
import at.sv.hue.color.XYColorGamutCorrection;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public final class HueApiImpl implements HueApi {

    private static final String CACHE_KEY_LIGHTS = "allLights";
    private static final String CACHE_KEY_DEVICES = "allDevices";
    private static final String CACHE_KEY_GROUPED_LIGHTS = "allGroupedLights";
    private static final String CACHE_KEY_SCENES = "allScenes";
    private static final String CACHE_KEY_ZONES = "allZones";
    private static final String CACHE_KEY_ROOMS = "allRooms";
    private static final String CACHE_KEY_ZIGBEE_CONNECTIVITY = "allZigbeeConnectivity";

    private final HttpResourceProvider resourceProvider;
    private final ObjectMapper mapper;
    private final String baseApi;
    private final RateLimiter rateLimiter;
    private final AsyncLoadingCache<String, Map<String, Light>> availableLightsCache;
    private final AsyncLoadingCache<String, Map<String, Device>> availableDevicesCache;
    private final AsyncLoadingCache<String, Map<String, Light>> availableGroupedLightsCache;
    private final AsyncLoadingCache<String, Map<String, Scene>> availableScenesCache;
    private final AsyncLoadingCache<String, Map<String, Group>> availableZonesCache;
    private final AsyncLoadingCache<String, Map<String, Group>> availableRoomsCache;
    private final AsyncLoadingCache<String, Map<String, ZigbeeConnectivity>> availableZigbeeConnectivityCache;

    public HueApiImpl(HttpResourceProvider resourceProvider, String host, RateLimiter rateLimiter,
                      int apiCacheInvalidationIntervalInMinutes) {
        this.resourceProvider = resourceProvider;
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setDefaultMergeable(true);
        assertNotHttpSchemeProvided(host);
        baseApi = "https://" + host + "/clip/v2/resource";
        this.rateLimiter = rateLimiter;
        availableLightsCache = createCache(this::lookupLights, apiCacheInvalidationIntervalInMinutes);
        availableDevicesCache = createCache(this::lookupDevices, apiCacheInvalidationIntervalInMinutes);
        availableGroupedLightsCache = createCache(this::lookupGroupedLights, apiCacheInvalidationIntervalInMinutes);
        availableScenesCache = createCache(this::lookupScenes, apiCacheInvalidationIntervalInMinutes);
        availableZonesCache = createCache(this::lookupZones, apiCacheInvalidationIntervalInMinutes);
        availableRoomsCache = createCache(this::lookupRooms, apiCacheInvalidationIntervalInMinutes);
        availableZigbeeConnectivityCache = createCache(this::lookupZigbeeConnectivity, apiCacheInvalidationIntervalInMinutes);
    }

    private static void assertNotHttpSchemeProvided(String host) {
        if (host.toLowerCase(Locale.ROOT).startsWith("http")) {
            throw new InvalidConnectionException("Invalid host provided. Hue Bridge host can't contain a scheme: " + host);
        }
    }

    private static <T> AsyncLoadingCache<String, Map<String, T>> createCache(Supplier<Map<String, T>> supplier,
                                                                             int apiCacheInvalidationIntervalInMinutes) {
        return Caffeine.newBuilder()
                       .refreshAfterWrite(Duration.ofMinutes(apiCacheInvalidationIntervalInMinutes))
                       .expireAfterWrite(Duration.ofMinutes(apiCacheInvalidationIntervalInMinutes * 2L))
                       .buildAsync(key -> supplier.get());
    }

    @Override
    public void assertConnection() {
        getAvailableLights();
    }

    @Override
    public Identifier getLightIdentifier(String idv1) {
        return getAvailableLights().values()
                                   .stream()
                                   .filter(resource -> idv1.equals(resource.getId_v1()))
                                   .findFirst()
                                   .map(resource -> new Identifier(resource.getId(), resource.getName()))
                                   .orElseThrow(() -> new LightNotFoundException("Could not find light with id '" + idv1 + "'"));
    }

    @Override
    public Identifier getGroupIdentifier(String idv1) {
        return getAvailableGroups().values()
                                   .stream()
                                   .filter(group -> idv1.equals(group.getId_v1()))
                                   .findFirst()
                                   .map(group -> new Identifier(group.getGroupedLightId(), group.getName()))
                                   .orElseThrow(() -> new GroupNotFoundException("Could not find group with id '" + idv1 + "'"));

    }

    @Override
    public Identifier getLightIdentifierByName(String name) {
        return getAvailableLights()
                .values()
                .stream()
                .filter(light -> name.equals(light.getName()))
                .findFirst()
                .map(light -> new Identifier(light.getId(), name))
                .orElseThrow(() -> new LightNotFoundException("Light with name '" + name + "' was not found!"));
    }

    @Override
    public Identifier getGroupIdentifierByName(String name) {
        return getAvailableGroups().values()
                                   .stream()
                                   .filter(group -> name.equals(group.metadata.name))
                                   .findFirst()
                                   .map(group -> new Identifier(group.getGroupedLightId(), name))
                                   .orElseThrow(() -> new GroupNotFoundException("Group with name '" + name + "' was not found!"));
    }

    @Override
    public LightState getLightState(String lightId) {
        Light light = getAndAssertLightExists(lightId);
        return light.getLightState(isUnavailable(light));
    }

    @Override
    public List<LightState> getGroupStates(String groupedLightId) {
        Map<String, Light> currentLights = getAvailableLights();
        return getGroupLights(groupedLightId).stream()
                                             .map(lightId -> getLightState(currentLights, lightId))
                                             .filter(Objects::nonNull)
                                             .collect(Collectors.toList());
    }

    private LightState getLightState(Map<String, Light> lights, String lightId) {
        Light light = lights.get(lightId);
        if (light == null) {
            return null;
        }
        return light.getLightState(isUnavailable(light));
    }

    private boolean isUnavailable(Light light) {
        String owner = light.getOwner().getRid();
        Device device = getDevice(owner);
        if (device == null) {
            return false;
        }
        return device.getZigbeeConnectivityResource()
                     .map(this::getZigbeeConnectivity)
                     .map(ZigbeeConnectivity::isUnavailable)
                     .orElse(false);
    }

    private URL createUrl(String url) {
        try {
            return new URI(baseApi + url).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Failed to construct API url", e);
        }
    }

    @Override
    public boolean isLightOff(String lightId) {
        return getLightState(lightId).isOff();
    }

    @Override
    public boolean isGroupOff(String groupedLightId) {
        return getAndAssertGroupedLightExists(groupedLightId).isOff();
    }

    @Override
    public void putState(PutCall putCall) {
        rateLimiter.acquire(1);
        putStateInternal("/light/", putCall);
    }

    @Override
    public void putGroupState(PutCall putCall) {
        rateLimiter.acquire(10);
        putStateInternal("/grouped_light/", putCall);
    }

    private void putStateInternal(String path, PutCall putCall) {
        URL url = createUrl(path + putCall.getId());
        resourceProvider.putResource(url, getBody(getAction(putCall)));
    }

    @Override
    public void putSceneState(String groupedLightId, List<PutCall> putCalls) {
        String sceneId = createOrUpdateSceneInternal(groupedLightId, "•", putCalls);
        recallScene(sceneId);
        log.trace("Recalled temp scene for {}", groupedLightId);
    }

    private void recallScene(String sceneId) {
        RecallRequest recallBody = new RecallRequest(new Recall("active", null));
        rateLimiter.acquire(10);
        resourceProvider.putResource(createUrl("/scene/" + sceneId), getBody(recallBody));
    }

    private String getBody(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to create body", e);
        }
    }

    @Override
    public List<String> getGroupLights(String groupedLightId) {
        Group group = getAndAssertGroupExists(groupedLightId);
        List<String> lightIds = getContainedLightIds(group);
        if (lightIds.isEmpty()) {
            throw new EmptyGroupException("Group with id '" + groupedLightId + "' has no lights to control!");
        }
        return lightIds;
    }

    @Override
    public String getSceneName(String sceneId) {
        Scene scene = getAvailableScenes().get(sceneId);
        if (scene == null) {
            return null;
        }
        return scene.getName();
    }

    @Override
    public List<String> getAffectedIdsByScene(String sceneId) {
        Scene scene = getAvailableScenes().get(sceneId);
        if (scene == null) {
            return List.of();
        }
        return getAffectedIdsByScene(scene);
    }

    private List<String> getAffectedIdsByScene(Scene scene) {
        List<String> resourceIds = new ArrayList<>(scene.getActions().stream()
                                                        .filter(HueApiImpl::isOn)
                                                        .map(SceneAction::getTarget)
                                                        .map(ResourceReference::getRid)
                                                        .toList());
        String groupedLightId = getAndAssertGroupExists(scene.getGroup()).getGroupedLightId();
        resourceIds.add(groupedLightId);
        return resourceIds;
    }

    private static boolean isOn(SceneAction action) {
        On on = action.getAction().on;
        return on != null && on.isOn();
    }

    @Override
    public List<String> getAffectedIdsByDevice(String deviceId) {
        Device device = getAvailableDevices().get(deviceId);
        if (device == null) {
            return List.of();
        }
        return Stream.concat(device.getLightIds(), getAssignedGroups(device)).toList();
    }

    private Stream<String> getAssignedGroups(Device device) {
        return device.getLightIds()
                     .map(this::getAssignedGroups)
                     .flatMap(Collection::stream);
    }

    @Override
    public List<String> getAssignedGroups(String lightId) {
        return getAvailableGroups().values()
                                   .stream()
                                   .filter(group -> getContainedLightIds(group).contains(lightId))
                                   .map(Group::getGroupedLightId)
                                   .collect(Collectors.toList());
    }

    @Override
    public List<GroupInfo> getAdditionalAreas(List<String> lightIds) {
        return List.of();
    }

    @Override
    public LightCapabilities getLightCapabilities(String lightId) {
        return getAndAssertLightExists(lightId).getCapabilities();
    }

    @Override
    public LightCapabilities getGroupCapabilities(String groupedLightId) {
        List<LightCapabilities> lightCapabilities = getGroupLights(groupedLightId)
                .stream()
                .map(this::getLightCapabilities)
                .collect(Collectors.toList());
        return LightCapabilities.builder()
                                .ctMin(getMinCtMin(lightCapabilities))
                                .ctMax(getMaxCtMax(lightCapabilities))
                                .colorGamut(getMaxGamut(lightCapabilities))
                                .capabilities(getMaxCapabilities(lightCapabilities))
                                .build();
    }

    @Override
    public List<ScheduledLightState> getSceneLightState(String groupedLightId, String sceneName) {
        Group group = getAndAssertGroupExists(groupedLightId);
        Scene existingScene = getScene(group, sceneName);
        if (existingScene == null) {
            return List.of();
        }
        return existingScene.getActions()
                            .stream()
                            .map(this::createScheduledLightState)
                            .toList();
    }

    @Override
    public synchronized void createOrUpdateScene(String groupedLightId, String sceneSyncName, List<PutCall> putCalls) {
        createOrUpdateSceneInternal(groupedLightId, sceneSyncName, putCalls);
    }

    private String createOrUpdateSceneInternal(String groupedLightId, String sceneSyncName, List<PutCall> putCalls) {
        Group group = getAndAssertGroupExists(groupedLightId);
        Scene existingScene = getScene(group, sceneSyncName);
        List<SceneAction> actions = createSceneActions(group, putCalls);
        String sceneId;
        if (existingScene == null) {
            Scene newScene = new Scene(sceneSyncName, group.toResourceReference(), actions);
            sceneId = createScene(newScene);
            log.trace("Created scene id={}", sceneId);
        } else if (actionsDiffer(existingScene, actions)) {
            Scene updatedScene = new Scene(actions);
            updateScene(existingScene, updatedScene);
            log.trace("Updated scene id={}", existingScene.getId());
            sceneId = existingScene.getId();
        } else {
            sceneId = existingScene.getId();
        }
        return sceneId;
    }

    private List<SceneAction> createSceneActions(Group group, List<PutCall> putCalls) {
        Map<String, PutCall> putCallMap = putCalls.stream()
                                                  .collect(Collectors.toMap(PutCall::getId, Function.identity()));
        return getContainedLights(group)
                .stream()
                .map(resource -> createSceneAction(putCallMap.getOrDefault(resource.getRid(),
                        getDefaultPutCall(resource)), resource))
                .toList();
    }

    private static PutCall getDefaultPutCall(ResourceReference resource) {
        return PutCall.builder().id(resource.getRid()).on(false).build();
    }

    private SceneAction createSceneAction(PutCall putCall, ResourceReference resource) {
        PutCall updatedPutCall = createPutCallBasedOnCapabilities(putCall, resource.getRid());
        return new SceneAction(resource, getActionForScene(updatedPutCall));
    }

    private PutCall createPutCallBasedOnCapabilities(PutCall putCall, String lightId) {
        PutCall.PutCallBuilder putCallBuilder = putCall.toBuilder();
        LightCapabilities capabilities = getLightCapabilities(lightId);
        if (!capabilities.isBrightnessSupported()) {
            putCallBuilder.bri(null);
        }
        if (!capabilities.isCtSupported()) {
            putCallBuilder.ct(null);
        }
        if (!capabilities.isColorSupported() && !capabilities.isCtSupported()) {
            putCallBuilder.x(null).y(null);
        }
        putCallBuilder.gamut(capabilities.getColorGamut());
        PutCall updatedPutCall = putCallBuilder.build();
        if (!capabilities.isColorSupported() && capabilities.isCtSupported()) {
            ColorModeConverter.convertIfNeeded(updatedPutCall, ColorMode.CT);
        }
        if (updatedPutCall.getCt() != null) {
            Integer ctMin = capabilities.getCtMin();
            Integer ctMax = capabilities.getCtMax();
            updatedPutCall.setCt(Math.min(Math.max(updatedPutCall.getCt(), ctMin), ctMax));
        }
        return updatedPutCall;
    }

    private Action getActionForScene(PutCall putCall) {
        Action action = getAction(putCall);
        if (action.getOn() == null) {
            action.setOn(new On(true));
        }
        return action;
    }

    private Action getAction(PutCall putCall) {
        Action.ActionBuilder actionBuilder = Action.builder();
        Boolean on = putCall.getOn();
        if (on != null) {
            actionBuilder.on(new On(on));
        }
        if (hasNonDefaultTransitionTime(putCall.getTransitionTime())) {
            actionBuilder.dynamics(new Action.Dynamics(putCall.getTransitionTime() * 100));
        }
        if (on == Boolean.FALSE) {
            return actionBuilder.build(); // no further properties needed
        }
        if (putCall.getColorMode() == ColorMode.CT) {
            actionBuilder.color_temperature(new ColorTemperature(putCall.getCt()));
        }
        if (putCall.getColorMode() == ColorMode.XY) {
            XYColorGamutCorrection correction = new XYColorGamutCorrection(putCall.getX(), putCall.getY(), putCall.getGamut());
            actionBuilder.color(new Color(new XY(correction.getX(), correction.getY())));
        }
        if (putCall.getColorMode() == ColorMode.GRADIENT) {
            Gradient gradient = putCall.getGradient();
            List<Action.GradientPoint> points = gradient.points()
                                                        .stream()
                                                        .map(HueApiImpl::createGradientPoint)
                                                        .toList();
            actionBuilder.gradient(new Action.Gradient(points, gradient.mode()));
        }
        if (putCall.getBri() != null) {
            double dimming = BigDecimal.valueOf(putCall.getBri())
                                       .multiply(BigDecimal.valueOf(100))
                                       .divide(BigDecimal.valueOf(254), 2, RoundingMode.HALF_UP)
                                       .doubleValue();
            actionBuilder.dimming(new Dimming(dimming));
        }
        Effect effect = getEffectWithNoneConverted(putCall);
        if (effect != null) {
            Action.Effects effects = getEffectsAction(effect);
            actionBuilder.effects_v2(effects);
            actionBuilder.color_temperature(null);
            actionBuilder.color(null);
        }
        return actionBuilder.build();
    }

    private boolean hasNonDefaultTransitionTime(Integer transitionTime) {
        return transitionTime != null && transitionTime != 4;
    }

    private static Effect getEffectWithNoneConverted(PutCall putCall) {
        Effect effect = putCall.getEffect();
        if (effect == null) {
            return null;
        }
        if (effect.isNone()) {
            return effect.toBuilder()
                         .effect("no_effect")
                         .build();
        }
        return effect;
    }

    private static Action.Effects getEffectsAction(Effect effect) {
        Action.EffectsParameters parameters = getEffectsParameters(effect);
        Action.EffectsAction action = new Action.EffectsAction(effect.effect(), parameters);
        return new Action.Effects(action);
    }

    private static Action.EffectsParameters getEffectsParameters(Effect effect) {
        if (effect.hasNoParameters()) {
            return null;
        }
        Action.EffectsParameters parameters = new Action.EffectsParameters();
        if (effect.y() != null) {
            parameters.setColor(new Color(new XY(effect.x(), effect.y())));
        }
        if (effect.ct() != null) {
            parameters.setColor_temperature(new ColorTemperature(effect.ct()));
        }
        parameters.setSpeed(effect.speed());
        return parameters;
    }

    private static Action.GradientPoint createGradientPoint(Pair<Double, Double> pair) {
        return new Action.GradientPoint(new Color(new XY(pair.first(), pair.second())));
    }

    private static boolean actionsDiffer(Scene scene, List<SceneAction> actions) {
        List<SceneAction> currentActions = scene.getActions();
        return !new HashSet<>(currentActions).containsAll(actions);
    }

    private String createScene(Scene newScene) {
        rateLimiter.acquire(10);
        return getAffectedResourceId(resourceProvider.postResource(createUrl("/scene"), getBody(newScene)));
    }

    private String getAffectedResourceId(String response) {
        ResourceReferenceResponse ref = parseResourceReferenceResponse(response);
        if (ref == null || ref.data == null || ref.data.isEmpty()) {
            return null;
        }
        return ref.data.getFirst().getRid();
    }

    private ResourceReferenceResponse parseResourceReferenceResponse(String response) {
        try {
            return mapper.readValue(response, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new ApiFailure("Failed to parse response '" + response + "': " + e.getLocalizedMessage());
        }
    }

    private void updateScene(Scene scene, Scene updatedScene) {
        rateLimiter.acquire(10);
        resourceProvider.putResource(createUrl("/scene/" + scene.getId()), getBody(updatedScene));
    }

    private ScheduledLightState createScheduledLightState(SceneAction sceneAction) {
        ScheduledLightState.ScheduledLightStateBuilder state = ScheduledLightState.builder();
        String id = sceneAction.getTarget().getRid();
        state.id(id);
        state.gamut(getLightCapabilities(id).getColorGamut());
        Action action = sceneAction.getAction();
        if (action.getOn() != null) {
            // don't set on=true, as lights are per default on when recalling a scene
            if (!action.getOn().isOn()) {
                state.on(false);
            }
        }
        if (action.getDimming() != null) {
            int bri = BigDecimal.valueOf(action.getDimming().getBrightness())
                                .multiply(BigDecimal.valueOf(254))
                                .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP)
                                .intValue();
            state.bri(bri);
        }
        if (action.getColor_temperature() != null) {
            state.ct(action.getColor_temperature().getMirek());
        }
        if (action.getColor() != null) {
            XY xy = action.getColor().getXy();
            state.x(xy.getX());
            state.y(xy.getY());
        }
        if (action.getEffects_v2() != null) {
            Action.EffectsAction effectsAction = action.getEffects_v2().getAction();
            String effect = effectsAction.getEffect();
            if ("no_effect".equals(effect)) {
                effect = "none";
            }
            state.effect(getEffectState(effect, effectsAction.getParameters()));
        }
        if (action.getGradient() != null) {
            Action.Gradient gradient = action.getGradient();
            List<Pair<Double, Double>> points = gradient.getPoints()
                                                        .stream()
                                                        .map(point ->
                                                                Pair.of(point.getColor().getXy().getX(),
                                                                        point.getColor().getXy().getY()))
                                                        .toList();
            state.gradient(new Gradient(points, gradient.getMode()));
        }
        // todo: transition time?
        return state.build();
    }

    private static Effect getEffectState(String effect, Action.EffectsParameters parameters) {
        Effect.EffectBuilder effectBuilder = Effect.builder().effect(effect);
        if (parameters != null) {
            Color color = parameters.getColor();
            if (color != null) {
                XY xy = color.getXy();
                effectBuilder.x(xy.getX());
                effectBuilder.y(xy.getY());
            }
            ColorTemperature colorTemperature = parameters.getColor_temperature();
            if (colorTemperature != null) {
                effectBuilder.ct(colorTemperature.getMirek());
            }
            effectBuilder.speed(parameters.getSpeed());
        }
        return effectBuilder.build();
    }

    private List<String> getContainedLightIds(Group group) {
        return getContainedLights(group).stream()
                                        .map(ResourceReference::getRid)
                                        .toList();
    }

    private List<ResourceReference> getContainedLights(Group group) {
        List<ResourceReference> containedLights = new ArrayList<>();
        for (ResourceReference resourceReference : group.getChildren()) {
            if (resourceReference.isLight()) {
                containedLights.add(resourceReference);
            } else if (resourceReference.isDevice()) {
                Device device = getDevice(resourceReference.getRid());
                if (device != null) {
                    containedLights.addAll(device.getLightResources());
                }
            }
        }
        return containedLights;
    }

    private Light getAndAssertLightExists(String lightId) {
        Light light = getAvailableLights().get(lightId);
        if (light == null) {
            throw new LightNotFoundException("Light with id '" + lightId + "' was not found!");
        }
        return light;
    }

    private Group getAndAssertGroupExists(String groupedLightId) {
        Light groupedLight = getAndAssertGroupedLightExists(groupedLightId);
        return getAndAssertGroupExists(groupedLight.getOwner());
    }

    private Light getAndAssertGroupedLightExists(String groupedLightId) {
        Light light = getAvailableGroupedLights().get(groupedLightId);
        if (light == null) {
            throw new GroupNotFoundException("GroupedLight with id '" + groupedLightId + "' was not found!");
        }
        return light;
    }

    private Group getAndAssertGroupExists(ResourceReference resourceReference) {
        if (resourceReference.isRoom()) {
            return getAndAssertRoomExists(resourceReference.getRid());
        } else {
            return getAndAssertZoneExists(resourceReference.getRid());
        }
    }

    private Group getAndAssertRoomExists(String groupId) {
        Group group = getAvailableRooms().get(groupId);
        if (group == null) {
            throw new GroupNotFoundException("Room with id '" + groupId + "' was not found!");
        }
        return group;
    }

    private Group getAndAssertZoneExists(String groupId) {
        Group group = getAvailableZones().get(groupId);
        if (group == null) {
            throw new GroupNotFoundException("Zone with id '" + groupId + "' was not found!");
        }
        return group;
    }

    private Scene getScene(Group group, String name) {
        return getAvailableScenes().values()
                                   .stream()
                                   .filter(scene -> scene.isPartOf(group) && name.equals(scene.metadata.name))
                                   .findFirst()
                                   .orElse(null);
    }

    private Map<String, Light> getAvailableLights() {
        return availableLightsCache.synchronous().get(CACHE_KEY_LIGHTS);
    }

    private Map<String, Light> getAvailableGroupedLights() {
        return availableGroupedLightsCache.synchronous().get(CACHE_KEY_GROUPED_LIGHTS);
    }

    private Map<String, Group> getAvailableGroups() {
        Map<String, Group> rooms = getAvailableRooms();
        Map<String, Group> zones = getAvailableZones();
        HashMap<String, Group> result = new HashMap<>(rooms);
        result.putAll(zones);
        return result;
    }

    private Map<String, Scene> getAvailableScenes() {
        return availableScenesCache.synchronous().get(CACHE_KEY_SCENES);
    }

    private Map<String, Device> getAvailableDevices() {
        return availableDevicesCache.synchronous().get(CACHE_KEY_DEVICES);
    }

    private Map<String, Group> getAvailableZones() {
        return availableZonesCache.synchronous().get(CACHE_KEY_ZONES);
    }

    private Map<String, Group> getAvailableRooms() {
        return availableRoomsCache.synchronous().get(CACHE_KEY_ROOMS);
    }

    private Map<String, ZigbeeConnectivity> getAvailableZigbeeConnectivity() {
        return availableZigbeeConnectivityCache.synchronous().get(CACHE_KEY_ZIGBEE_CONNECTIVITY);
    }

    private Device getDevice(String deviceId) {
        return getAvailableDevices().get(deviceId);
    }

    private ZigbeeConnectivity getZigbeeConnectivity(String connectivityId) {
        return getAvailableZigbeeConnectivity().get(connectivityId);
    }

    private Map<String, Light> lookupGroupedLights() {
        return lookup("/grouped_light", new TypeReference<LightResponse>() {
        }, Light::getId);
    }

    private Map<String, Light> lookupLights() {
        return lookup("/light", new TypeReference<LightResponse>() {
        }, Light::getId);
    }

    private Map<String, ZigbeeConnectivity> lookupZigbeeConnectivity() {
        return lookup("/zigbee_connectivity", new TypeReference<ZigbeeConnectivityResponse>() {
        }, ZigbeeConnectivity::getId);
    }

    private Map<String, Scene> lookupScenes() {
        return lookup("/scene", new TypeReference<SceneResponse>() {
        }, Scene::getId);
    }

    private Map<String, Device> lookupDevices() {
        return lookup("/device", new TypeReference<DeviceResponse>() {
        }, Device::getId);
    }

    private Map<String, Group> lookupZones() {
        return lookup("/zone", new TypeReference<GroupResponse>() {
        }, Group::getId);
    }

    private Map<String, Group> lookupRooms() {
        return lookup("/room", new TypeReference<GroupResponse>() {
        }, Group::getId);
    }

    private <T, C extends DataListContainer<T>> Map<String, T> lookup(String endpoint, TypeReference<C> typeReference,
                                                                      Function<T, String> idFunction) {
        rateLimiter.acquire(1);
        String response = resourceProvider.getResource(createUrl(endpoint));
        try {
            C container = mapper.readValue(response, typeReference);
            return container.getData().stream().collect(Collectors.toConcurrentMap(idFunction, Function.identity()));
        } catch (Exception e) {
            throw new ApiFailure("Failed to parse response '" + response + "': " + e.getLocalizedMessage());
        }
    }

    @Override
    public void clearCaches() {
    }

    private static Double[][] getMaxGamut(List<LightCapabilities> lightCapabilities) {
        Map<String, Double[][]> colorGamutMap = lightCapabilities.stream()
                                                                 .filter(c -> c.getColorGamut() != null)
                                                                 .collect(Collectors.toMap(LightCapabilities::getColorGamutType,
                                                                         LightCapabilities::getColorGamut,
                                                                         (gamut1, gamut2) -> gamut1));
        return colorGamutMap.getOrDefault("C", colorGamutMap.getOrDefault("B", colorGamutMap.getOrDefault("A", null)));
    }

    private static EnumSet<Capability> getMaxCapabilities(List<LightCapabilities> lightCapabilities) {
        return EnumSet.copyOf(lightCapabilities
                .stream()
                .map(LightCapabilities::getCapabilities)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet()));
    }

    private Integer getMinCtMin(List<LightCapabilities> lightCapabilities) {
        return lightCapabilities.stream()
                                .map(LightCapabilities::getCtMin)
                                .filter(Objects::nonNull)
                                .min(Integer::compareTo)
                                .orElse(null);
    }

    private Integer getMaxCtMax(List<LightCapabilities> lightCapabilities) {
        return lightCapabilities.stream()
                                .map(LightCapabilities::getCtMax)
                                .filter(Objects::nonNull)
                                .max(Integer::compareTo)
                                .orElse(null);
    }

    @Override
    public void onModification(String type, String id, Object content) {
        if (type == null || id == null) {
            return;
        }
        if (content != null && !(content instanceof JsonNode)) {
            invalidateCache(type);
            return;
        }
        JsonNode update = (JsonNode) content;
        try {
            switch (type) {
                case "light" -> updateResourceCache(availableLightsCache, CACHE_KEY_LIGHTS, id, update, Light.class);
                case "grouped_light" ->
                        updateResourceCache(availableGroupedLightsCache, CACHE_KEY_GROUPED_LIGHTS, id, update, Light.class);
                case "scene" -> updateResourceCache(availableScenesCache, CACHE_KEY_SCENES, id, update, Scene.class);
                case "device" ->
                        updateResourceCache(availableDevicesCache, CACHE_KEY_DEVICES, id, update, Device.class);
                case "zone" -> updateResourceCache(availableZonesCache, CACHE_KEY_ZONES, id, update, Group.class);
                case "room" -> updateResourceCache(availableRoomsCache, CACHE_KEY_ROOMS, id, update, Group.class);
                case "zigbee_connectivity" ->
                        updateResourceCache(availableZigbeeConnectivityCache, CACHE_KEY_ZIGBEE_CONNECTIVITY, id, update, ZigbeeConnectivity.class);
                default -> {
                }
            }
        } catch (Exception e) {
            log.debug("Failed to apply {} update for {}: {}", type, id, e.getMessage());
            invalidateCache(type);
        }
    }

    private <T> void updateResourceCache(AsyncLoadingCache<String, Map<String, T>> cache, String cacheKey, String id,
                                         JsonNode update, Class<T> targetType) throws IOException {
        Map<String, T> resources = cache.synchronous().getIfPresent(cacheKey);
        if (resources == null) {
            return;
        }
        if (update == null || update.isNull()) {
            resources.remove(id);
            return;
        }
        T resource = resources.get(id);
        if (resource == null) {
            resource = mapper.treeToValue(update, targetType);
            resources.put(id, resource);
            return;
        }
        mapper.readerForUpdating(resource).readValue(update.traverse(mapper));
    }

    private void invalidateCache(String type) {
        switch (type) {
            case "light" -> invalidate(availableLightsCache);
            case "grouped_light" -> invalidate(availableGroupedLightsCache);
            case "scene" -> invalidate(availableScenesCache);
            case "device" -> invalidate(availableDevicesCache);
            case "zone" -> invalidate(availableZonesCache);
            case "room" -> invalidate(availableRoomsCache);
            case "zigbee_connectivity" -> invalidate(availableZigbeeConnectivityCache);
        }
        log.trace("Invalidated {} cache.", type);
    }

    private void invalidate(AsyncLoadingCache<String, ?> availableLightsCache) {
        availableLightsCache.synchronous().invalidateAll();
    }

    private interface DataListContainer<T> {
        List<T> getData();
    }

    @Data
    private static final class LightResponse implements DataListContainer<Light> {
        List<Light> data;
    }

    @Data
    private static final class ZigbeeConnectivityResponse implements DataListContainer<ZigbeeConnectivity> {
        List<ZigbeeConnectivity> data;
    }

    @Data
    private static final class SceneResponse implements DataListContainer<Scene> {
        List<Scene> data;
    }

    @Data
    private static final class GroupResponse implements DataListContainer<Group> {
        List<Group> data;
    }

    @Data
    private static final class DeviceResponse implements DataListContainer<Device> {
        List<Device> data;
    }

    @Data
    private static final class ResourceReferenceResponse implements DataListContainer<ResourceReference> {
        List<ResourceReference> data;
    }
}
