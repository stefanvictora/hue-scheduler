package at.sv.hue.api.hass;

import at.sv.hue.ColorMode;
import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeConnectionFailure;
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
import at.sv.hue.api.hass.area.HassAreaRegistry;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static at.sv.hue.api.hass.BrightnessConverter.hassToHueBrightness;
import static at.sv.hue.api.hass.BrightnessConverter.hueToHassBrightness;

public class HassApiImpl implements HueApi {

    private final HttpResourceProvider httpResourceProvider;
    private final HassAreaRegistry hassAreaRegistry;
    private final RateLimiter rateLimiter;
    private final HassAvailabilityListener availabilityListener;
    private final ObjectMapper mapper;
    private final String baseUrl;

    private final Object lightMapLock = new Object();
    private Map<String, State> availableStates;
    private boolean availableStatesInvalidated;
    private Map<String, List<State>> nameToStatesMap;
    private boolean nameToStatesMapInvalidated;

    public HassApiImpl(String origin, HttpResourceProvider httpResourceProvider, HassAreaRegistry hassAreaRegistry,
                       HassAvailabilityListener availabilityListener, RateLimiter rateLimiter) {
        baseUrl = origin + "/api";
        this.httpResourceProvider = httpResourceProvider;
        this.hassAreaRegistry = hassAreaRegistry;
        this.availabilityListener = availabilityListener;
        this.rateLimiter = rateLimiter;
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public void assertConnection() {
        availabilityListener.performInitialCheck(() -> {
            try {
                return !lookupStates().isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
        if (!availabilityListener.isFullyStarted()) {
            throw new BridgeConnectionFailure("HA not fully started yet. Waiting for startup to complete...");
        }
    }

    @Override
    public Identifier getLightIdentifier(String id) {
        assertSupportedStateType(id);
        State state = getAndAssertLightExists(id);
        return new Identifier(id, state.attributes.friendly_name);
    }

    @Override
    public Identifier getGroupIdentifier(String id) {
        return getLightIdentifier(id);
    }

    @Override
    public LightState getLightState(String id) {
        assertSupportedStateType(id);
        String response = httpResourceProvider.getResource(createUrl("/states/" + id));
        try {
            State state = mapper.readValue(response, State.class);
            return createLightState(state);
        } catch (JsonProcessingException | NullPointerException e) {
            throw new ApiFailure("Failed to parse state response '" + response + "' for id " + id + ": " + e.getLocalizedMessage());
        }
    }

    @Override
    public List<LightState> getGroupStates(String id) {
        List<String> groupLights = getGroupLights(id);
        Map<String, State> currentStates = lookupStates();
        return groupLights.stream()
                          .map(currentStates::get)
                          .filter(Objects::nonNull)
                          .filter(HassApiImpl::isSupportedStateType)
                          .map(this::createLightState)
                          .collect(Collectors.toList());
    }

    @Override
    public boolean isLightOff(String id) {
        return getLightState(id).isOff();
    }

    @Override
    public boolean isGroupOff(String id) {
        return isLightOff(id);
    }

    @Override
    public void putState(PutCall putCall) {
        String id = putCall.getId();
        assertSupportedStateType(id);
        if (putCall.isGroupState()) {
            rateLimiter.acquire(10);
        } else {
            rateLimiter.acquire(1);
        }
        ChangeState changeState = getChangeState(putCall);
        changeState.setEntity_id(id);
        httpResourceProvider.postResource(getUpdateUrl(putCall), getBody(changeState));
    }

    private ChangeState getChangeState(PutCall putCall) {
        ChangeState changeState = new ChangeState();
        changeState.setBrightness(hueToHassBrightness(putCall.getBri()));
        changeState.setColor_temp(putCall.getCt());
        changeState.setEffect(putCall.getEffect());
        changeState.setTransition(convertToSeconds(putCall.getTransitionTime())); // todo: find out the max value of home assistant
        if (putCall.getHue() != null && putCall.getSat() != null) {
            changeState.setHs_color(getHsColor(putCall));
        } else if (putCall.getX() != null && putCall.getY() != null) {
            changeState.setXy_color(getXyColor(putCall));
        }
        return changeState;
    }

    private Integer[] getHsColor(PutCall putCall) {
        int hue = (int) (putCall.getHue() / 65535.0 * 360.0);
        int sat = (int) (putCall.getSat() / 254.0 * 100.0);
        return new Integer[]{hue, sat};
    }

    private static Double[] getXyColor(PutCall putCall) {
        return new Double[]{putCall.getX(), putCall.getY()};
    }

    @Override
    public List<String> getGroupLights(String groupId) {
        assertSupportedStateType(groupId);
        State state = getAndAssertLightExists(groupId);
        if (isNoGroupState(state)) {
            throw new GroupNotFoundException("No group with id '" + groupId + "' found");
        }

        List<String> groupLights = new ArrayList<>();
        if (isHassGroup(state)) {
            groupLights.addAll(state.attributes.entity_id);
        } else {
            groupLights.addAll(state.attributes.lights.stream()
                                                      .map(this::getNonGroupLightId)
                                                      .toList());
        }

        if (groupLights.isEmpty()) {
            throw new EmptyGroupException("Group with id '" + groupId + "' does not contain any lights!");
        }
        return groupLights;
    }

    @Override
    public String getSceneName(String sceneId) {
        State scene = getOrLookupStates().get(sceneId);
        if (scene == null) {
            return null;
        }
        return scene.attributes.friendly_name;
    }

    @Override
    public List<String> getAffectedIdsByScene(String sceneId) {
        State scene = getOrLookupStates().get(sceneId);
        if (scene == null) {
            return List.of();
        }
        List<String> affectedIds = new ArrayList<>();
        StateAttributes sceneAttributes = scene.getAttributes();
        if (sceneAttributes.getEntity_id() != null) { // HA scene
            affectedIds.addAll(sceneAttributes.getEntity_id());
        }
        if (sceneAttributes.getGroup_name() != null) { // Hue scene
            try {
                String groupId = getGroupIdentifierByName(sceneAttributes.getGroup_name()).id();
                affectedIds.add(groupId);
                affectedIds.addAll(getGroupLights(groupId));
            } catch (Exception ignore) {
            }
        }
        return affectedIds;
    }

    @Override
    public List<String> getAffectedIdsByDevice(String deviceId) {
        List<String> assignedGroups = getAssignedGroups(deviceId);

        List<String> affectedIds = new ArrayList<>(assignedGroups);
        affectedIds.add(deviceId);

        return affectedIds;
    }

    @Override
    public List<String> getAssignedGroups(String lightId) {
        String lightName = getLightIdentifier(lightId).name();
        return getOrLookupStates().values()
                                  .stream()
                                  .filter(HassApiImpl::isGroupState)
                                  .filter(state -> containsLightIdOrName(state, lightId, lightName))
                                  .map(State::getEntity_id)
                                  .collect(Collectors.toList());
    }

    @Override
    public List<GroupInfo> getAdditionalAreas(List<String> lightIds) {
        return lightIds.stream()
                       .map(hassAreaRegistry::lookupAreaForEntity)
                       .filter(Objects::nonNull)
                       .distinct()
                       .toList();
    }

    @Override
    public Identifier getGroupIdentifierByName(String name) {
        State state = lookupStateByName(name);
        if (isNoGroupState(state)) {
            throw new GroupNotFoundException("No group with name '" + name + "' found");
        }
        return new Identifier(state.entity_id, name);
    }

    @Override
    public Identifier getLightIdentifierByName(String name) {
        State state = lookupStateByName(name);
        return new Identifier(state.entity_id, name);
    }

    private State lookupStateByName(String name) {
        List<State> states = getOrLookupStatesByName(name);
        if (states.size() > 1) {
            throw new NonUniqueNameException("There are " + states.size() + " entities with the given name '" + name + "'." +
                                             " Please use a unique ID instead.");
        }
        return states.getFirst();
    }

    @Override
    public LightCapabilities getLightCapabilities(String id) {
        assertSupportedStateType(id);
        State state = getAndAssertLightExists(id);
        return createLightCapabilities(state);
    }

    @Override
    public LightCapabilities getGroupCapabilities(String id) {
        return getLightCapabilities(id);
    }

    @Override
    public void createOrUpdateScene(String groupId, String sceneSyncName, List<PutCall> putCalls) {
        CreateScene createScene = new CreateScene();
        createScene.setScene_id(HassApiUtils.getNormalizedSceneSyncName(sceneSyncName + "_" + groupId));
        HashMap<String, ChangeState> sceneStates = new HashMap<>();
        for (PutCall putCall : putCalls) {
            ChangeState changeState = getChangeState(putCall);
            if (putCall.getOn() == Boolean.FALSE) {
                changeState.setState("off");
            } else {
                changeState.setState("on");
            }
            sceneStates.put(putCall.getId(), changeState);
        }
        createScene.setEntities(sceneStates);
        rateLimiter.acquire(1);
        httpResourceProvider.postResource(createUrl("/services/scene/create"), getBody(createScene));
    }

    @Override
    public void clearCaches() {
        synchronized (lightMapLock) {
            availableStatesInvalidated = true;
            nameToStatesMapInvalidated = true;
        }
        hassAreaRegistry.clearCaches();
    }

    private Map<String, List<State>> getOrLookupNameToStateMap() {
        synchronized (lightMapLock) {
            if (nameToStatesMap == null || nameToStatesMapInvalidated) {
                nameToStatesMap = new HashMap<>();
                getOrLookupStates().forEach((id, state) ->
                        nameToStatesMap.computeIfAbsent(state.attributes.friendly_name, s -> new ArrayList<>()).add(state));
                nameToStatesMapInvalidated = false;
            }
        }
        return nameToStatesMap;
    }

    private Map<String, State> getOrLookupStates() {
        synchronized (lightMapLock) {
            if (availableStates == null || availableStatesInvalidated) {
                availableStates = lookupStates();
                availableStatesInvalidated = false;
            }
        }
        return availableStates;
    }

    private State getAndAssertLightExists(String id) {
        State state = getOrLookupStates().get(id);
        if (state == null) {
            throw new LightNotFoundException("Entity with id '" + id + "' not found!");
        }
        return state;
    }

    private Map<String, State> lookupStates() {
        String response = httpResourceProvider.getResource(createUrl("/states"));
        try {
            List<State> states = mapper.readValue(response, new TypeReference<>() {
            });
            return states.stream()
                         .collect(Collectors.toMap(State::getEntity_id, Function.identity()));
        } catch (JsonProcessingException | NullPointerException e) {
            throw new ApiFailure("Failed to parse light states response '" + response + "':" + e.getLocalizedMessage());
        }
    }


    private URL createUrl(String url) {
        try {
            return new URI(baseUrl + url).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Failed to construct API url", e);
        }
    }

    private LightState createLightState(State state) {
        StateAttributes attributes = state.getAttributes();
        return new LightState(state.entity_id, hassToHueBrightness(attributes.brightness), attributes.color_temp, getXY(attributes.xy_color, 0),
                getXY(attributes.xy_color, 1),
                getEffect(attributes.effect), getColorMode(attributes.color_mode),
                getOn(state.state), getUnavailable(state.state), createLightCapabilities(state));
    }

    private static Double getXY(Double[] xy, int i) {
        if (xy != null) {
            return xy[i];
        }
        return null;
    }

    private static String getEffect(String effect) {
        if (effect == null) {
            return null;
        }
        return effect.toLowerCase(Locale.getDefault());
    }

    private static ColorMode getColorMode(String colorMode) {
        if (colorMode == null) {
            return null;
        }
        if (colorMode.equals("color_temp")) {
            return ColorMode.CT;
        } else if (colorMode.equals("xy")) {
            return ColorMode.XY;
        }
        return null;
    }

    private static boolean getOn(String state) {
        return "on".equals(state);
    }

    private static boolean getUnavailable(String state) {
        return "unavailable".equals(state);
    }

    private static LightCapabilities createLightCapabilities(State state) {
        StateAttributes attributes = state.attributes;
        return new LightCapabilities(null, null, attributes.min_mireds, attributes.max_mireds,
                getCapabilities(state), getEffects(state));
    }

    private static List<String> getEffects(State state) {
        List<String> effectList = state.attributes.effect_list;
        if (effectList == null) {
            return null;
        }
        return effectList.stream()
                         .map(String::toLowerCase)
                         .filter(effect -> !"none".equals(effect) && !"unknown".equals(effect))
                         .toList();
    }

    private static EnumSet<Capability> getCapabilities(State state) {
        List<String> supportedColorModes = state.attributes.supported_color_modes;
        EnumSet<Capability> capabilities = EnumSet.of(Capability.ON_OFF);
        if (supportedColorModes == null) {
            return capabilities;
        }
        if (supportedColorModes.contains("color_temp")) {
            capabilities.add(Capability.COLOR_TEMPERATURE);
            capabilities.add(Capability.BRIGHTNESS);
        }
        if (supportedColorModes.contains("xy")) {
            capabilities.add(Capability.COLOR);
            capabilities.add(Capability.BRIGHTNESS);
        }
        if (supportedColorModes.contains("brightness")) {
            capabilities.add(Capability.BRIGHTNESS);
        }
        return capabilities;
    }

    private String getNonGroupLightId(String name) {
        return getOrLookupStatesByName(name).stream()
                                            .filter(HassApiImpl::isNoGroupState)
                                            .findFirst()
                                            .map(State::getEntity_id)
                                            .orElseThrow(() -> new LightNotFoundException("Non-group entity with name '" + name + "' was not found!"));
    }

    private static boolean isNoGroupState(State state) {
        return !isGroupState(state);
    }

    private static boolean isGroupState(State state) {
        return isHueGroup(state) || isHassGroup(state);
    }

    private static boolean isHueGroup(State state) {
        return Boolean.TRUE.equals(state.attributes.is_hue_group);
    }

    private static boolean isHassGroup(State state) {
        return state.attributes.entity_id != null && !state.isScene();
    }

    private boolean containsLightIdOrName(State state, String lightId, String lightName) {
        if (isHassGroup(state)) {
            return state.attributes.entity_id.contains(lightId);
        } else { // old Hue group
            return state.attributes.lights != null && state.attributes.lights.contains(lightName);
        }
    }

    private List<State> getOrLookupStatesByName(String name) {
        List<State> states = getOrLookupNameToStateMap().get(name);
        if (states == null) {
            throw new LightNotFoundException("Entity with name '" + name + "' was not found!");
        }
        return states;
    }

    private static void assertSupportedStateType(String id) {
        if (!isSupportedStateType(id)) {
            throw new UnsupportedStateException("Entity with id '" + id + "' is not supported");
        }
    }

    private static boolean isSupportedStateType(State state) {
        return isSupportedStateType(state.entity_id);
    }

    private static boolean isSupportedStateType(String entityId) {
        return HassSupportedEntityType.fromEntityId(entityId) != null;
    }

    private Float convertToSeconds(Integer transitionTime) {
        if (transitionTime == null) {
            return null;
        }
        return (float) transitionTime / 10;
    }

    private URL getUpdateUrl(PutCall putCall) {
        String service = getService(putCall.getId());
        if (Boolean.FALSE.equals(putCall.getOn())) {
            return createUrl("/services/" + service + "/turn_off");
        } else {
            return createUrl("/services/" + service + "/turn_on");
        }
    }

    private String getService(String id) {
        HassSupportedEntityType type = HassSupportedEntityType.fromEntityId(id);
        return type.name().toLowerCase(Locale.ROOT);
    }

    private String getBody(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to create state body", e);
        }
    }

    @Override
    public void onModification(String type, String entityId, Object content) {
        synchronized (lightMapLock) {
            if (availableStates != null) {
                if (content == null) { // entity deleted
                    availableStates.remove(entityId);
                } else if (content instanceof State newState) {
                    availableStates.put(entityId, newState);
                }
                // Invalidate the name-to-states map so it gets rebuilt on next access
                nameToStatesMapInvalidated = true;
            }
        }
    }

    @Data
    private static final class ChangeState {
        String entity_id;
        String state;
        Integer brightness;
        Integer color_temp;
        Double[] xy_color;
        Integer[] hs_color;
        String effect;
        Float transition;
    }

    @Data
    private static final class CreateScene {
        String scene_id;
        Map<String, ChangeState> entities;
    }
}
