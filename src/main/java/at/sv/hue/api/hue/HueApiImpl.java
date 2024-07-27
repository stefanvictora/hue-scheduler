package at.sv.hue.api.hue;

import at.sv.hue.ColorMode;
import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public final class HueApiImpl implements HueApi {

    private final HttpResourceProvider resourceProvider;
    private final ObjectMapper mapper;
    private final String baseApi;
    private final RateLimiter rateLimiter;
    private final LoadingCache<String, Map<String, Light>> availableLightsCache;
    private final LoadingCache<String, Map<String, Scene>> availableScenesCache;
    private final LoadingCache<String, Map<String, Device>> availableDevicesCache;
    private final LoadingCache<String, Map<String, Light>> availableGroupedLightsCache;
    private final LoadingCache<String, Map<String, Group>> availableZonesCache;
    private final LoadingCache<String, Map<String, Group>> availableRoomsCache;

    public HueApiImpl(HttpResourceProvider resourceProvider, String host, RateLimiter rateLimiter,
                      int apiCacheInvalidationIntervalInMinutes) {
        this.resourceProvider = resourceProvider;
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        assertNotHttpSchemeProvided(host);
        baseApi = "https://" + host + "/clip/v2/resource";
        this.rateLimiter = rateLimiter;
        availableScenesCache = createCache(this::lookupScenes, apiCacheInvalidationIntervalInMinutes);
        availableDevicesCache = createCache(this::lookupDevices, apiCacheInvalidationIntervalInMinutes);
        availableLightsCache = createCache(this::lookupLights, apiCacheInvalidationIntervalInMinutes);
        availableGroupedLightsCache = createCache(this::lookupGroupedLights, apiCacheInvalidationIntervalInMinutes);
        availableZonesCache = createCache(this::lookupZones, apiCacheInvalidationIntervalInMinutes);
        availableRoomsCache = createCache(this::lookupRooms, apiCacheInvalidationIntervalInMinutes);
    }

    private static void assertNotHttpSchemeProvided(String host) {
        if (host.toLowerCase(Locale.ROOT).startsWith("http")) {
            throw new InvalidConnectionException("Invalid host provided. Hue Bridge host can't contain a scheme: " + host);
        }
    }

    private static <T> LoadingCache<String, Map<String, T>> createCache(Supplier<Map<String, T>> supplier,
                                                                        int apiCacheInvalidationIntervalInMinutes) {
        return Caffeine.newBuilder()
                       .expireAfterWrite(Duration.ofMinutes(apiCacheInvalidationIntervalInMinutes))
                       .build(key -> supplier.get());
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
        return lookupLight(lightId).getLightState();
    }

    @Override
    public List<LightState> getGroupStates(String groupedLightId) {
        Map<String, Light> currentLights = lookupLights();
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
        return light.getLightState();
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
        return lookupGroupedLight(groupedLightId).isOff();
    }

    @Override
    public void putState(PutCall putCall) {
        URL url;
        if (putCall.isGroupState()) {
            rateLimiter.acquire(10);
            url = createUrl("/grouped_light/" + putCall.getId());
        } else {
            rateLimiter.acquire(1);
            url = createUrl("/light/" + putCall.getId());
        }
        resourceProvider.putResource(url, getBody(getAction(putCall)));
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
        Light groupedLight = getAndAssertGroupedLightExists(groupedLightId);
        Group group = getAndAssertGroupExists(groupedLight.getOwner());
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
                                                        .map(SceneAction::getTarget)
                                                        .map(ResourceReference::getRid)
                                                        .toList());
        String groupedLightId = getAndAssertGroupExists(scene.getGroup()).getGroupedLightId();
        resourceIds.add(groupedLightId);
        return resourceIds;
    }

    @Override
    public List<String> getAssignedGroups(String lightId) {
        return getAvailableGroups().entrySet()
                                   .stream()
                                   .filter(entry -> getContainedLightIds(entry.getValue()).contains(lightId))
                                   .map(Map.Entry::getKey)
                                   .collect(Collectors.toList());
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

    // todo: actually we need to get the "full picture" as put call, not just what the state is right now
    //  meaning if the previous state defined a brightness, but this state does not. THe full picture would include the previous brightness

    @Override
    public void createOrUpdateScene(String groupedLightId, PutCall putCall, String sceneSyncName) {
        rateLimiter.acquire(10);
        log.trace("Sync scene: {}", putCall);

        Light groupedLight = getAndAssertGroupedLightExists(groupedLightId);
        Group group = getAndAssertGroupExists(groupedLight.getOwner());
        Scene scene = getScene(group, sceneSyncName);
        Action action = getSceneAction(putCall);

        // todo: we need to make sure that the lights support the action passed to it -> we can't set color to CT only lights
        //  the API call will actually fail
        // todo: we should also already consider the case for different put calls for different lights.
        List<SceneAction> sceneActions = getContainedLights(group).stream()
                                                                  .map(resource -> new SceneAction(resource, action))
                                                                  .toList();
        if (scene == null) {
            Scene newScene = new Scene(sceneSyncName, group.toResourceReference(), sceneActions);
            String response = resourceProvider.postResource(createUrl("/scene"), getBody(newScene));
            availableScenesCache.invalidateAll();
        } else {
            // todo: we could check if the actions actually differ from the current scene actions -> and only then update
            Scene updatedScene = new Scene(sceneActions);
            String response = resourceProvider.putResource(createUrl("/scene/" + scene.getId()), getBody(updatedScene));
        }
    }

    private Action getSceneAction(PutCall putCall) {
        Action action = getAction(putCall);
        action.setOn(new On(true));
        return action;
    }

    private Action getAction(PutCall putCall) {
        Action.ActionBuilder actionBuilder = Action.builder();
        if (putCall.getColorMode() == ColorMode.HS) {
            ColorModeConverter.convertIfNeeded(putCall, ColorMode.XY);
        }
        if (putCall.getColorMode() == ColorMode.CT) {
            actionBuilder.color_temperature(new ColorTemperature(putCall.getCt()));
        }
        if (putCall.getColorMode() == ColorMode.XY) {
            actionBuilder.color(new Color(new XY(putCall.getX(), putCall.getY())));
        }
        if (putCall.getOn() != null) {
            actionBuilder.on(new On(putCall.getOn()));
        }
        if (putCall.getBri() != null) {
            double dimming = BigDecimal.valueOf(putCall.getBri())
                                       .multiply(BigDecimal.valueOf(100))
                                       .divide(BigDecimal.valueOf(254), 2, RoundingMode.HALF_UP)
                                       .doubleValue();
            actionBuilder.dimming(new Dimming(dimming));
        }
        if (putCall.hasNonDefaultTransitionTime()) {
            actionBuilder.dynamics(new Action.Dynamics(putCall.getTransitionTime() * 100));
        }
        String effect = getEffectWithNoneConverted(putCall);
        if (effect != null) {
            actionBuilder.effects(new Action.Effects(effect));
            actionBuilder.color_temperature(null);
            actionBuilder.color(null);
        }
        return actionBuilder.build();
    }

    private static String getEffectWithNoneConverted(PutCall putCall) {
        String effect = putCall.getEffect();
        if (effect == null) {
            return null;
        }
        if ("none".equals(effect)) {
            return "no_effect";
        }
        return effect;
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
                Device device = getAvailableDevices().get(resourceReference.getRid());
                containedLights.addAll(device.getLightResources());
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
        return availableLightsCache.get("allLights");
    }

    private Map<String, Light> getAvailableGroupedLights() {
        return availableGroupedLightsCache.get("allGroupedLights");
    }

    private Map<String, Group> getAvailableGroups() {
        Map<String, Group> rooms = getAvailableRooms();
        Map<String, Group> zones = getAvailableZones();
        HashMap<String, Group> result = new HashMap<>(rooms);
        result.putAll(zones);
        return result;
    }

    private Map<String, Scene> getAvailableScenes() {
        return availableScenesCache.get("allScenes");
    }

    private Map<String, Device> getAvailableDevices() {
        return availableDevicesCache.get("allDevices");
    }

    private Map<String, Group> getAvailableZones() {
        return availableZonesCache.get("allZones");
    }

    private Map<String, Group> getAvailableRooms() {
        return availableRoomsCache.get("allRooms");
    }

    private Light lookupGroupedLight(String id) {
        return lookup("/grouped_light/" + id, new TypeReference<LightResponse>() {
        }, Light::getId).get(id);
    }

    private Map<String, Light> lookupGroupedLights() {
        return lookup("/grouped_light", new TypeReference<LightResponse>() {
        }, Light::getId);
    }

    private Light lookupLight(String id) {
        return lookup("/light/" + id, new TypeReference<LightResponse>() {
        }, Light::getId).get(id);
    }

    private Map<String, Light> lookupLights() {
        return lookup("/light", new TypeReference<LightResponse>() {
        }, Light::getId);
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
        String response = resourceProvider.getResource(createUrl(endpoint));
        try {
            C container = mapper.readValue(response, typeReference);
            return container.getData().stream().collect(Collectors.toMap(idFunction, Function.identity()));
        } catch (Exception e) {
            throw new ApiFailure("Failed to parse response '" + response + "': " + e.getLocalizedMessage());
        }
    }

    @Override
    public void clearCaches() {
        // todo: maybe use this mechanism when we detect changes to groups or scenes from outside via the event stream
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

    private interface DataListContainer<T> {
        List<T> getData();
    }

    @Data
    private static final class LightResponse implements DataListContainer<Light> {
        List<Light> data;
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
}
