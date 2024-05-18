package at.sv.hue.api.hass;

import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.api.RateLimiter;
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

public class HassApiImpl implements HueApi {

    private final HttpResourceProvider httpResourceProvider;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;
    private final String baseApi;

    private final Object lightMapLock = new Object();
    private Map<String, State> availableStates;
    private boolean availableStatesInvalidated;
    private Map<String, List<State>> nameToStatesMap;
    private boolean nameToStatesMapInvalidated;

    public HassApiImpl(HttpResourceProvider httpResourceProvider, String hostname, String port, RateLimiter rateLimiter) {
        this.httpResourceProvider = httpResourceProvider;
        this.rateLimiter = rateLimiter;
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        baseApi = "http://" + hostname + ":" + port + "/api"; // todo: allow other schemas
    }

    @Override
    public void assertConnection() {
        getOrLookupStates();
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
        ChangeState changeState = new ChangeState();
        changeState.setEntity_id(id);
        changeState.setBrightness(hueToHassBrightness(putCall.getBri()));
        changeState.setColor_temp(putCall.getCt());
        changeState.setEffect(putCall.getEffect());
        changeState.setTransition(convertToSeconds(putCall.getTransitionTime())); // todo: find out the max value of home assistant
        if (putCall.getX() != null && putCall.getY() != null) {
            changeState.setXy_color(new Double[]{putCall.getX(), putCall.getY()});
        }

        httpResourceProvider.postResource(getUpdateUrl(putCall), getBody(changeState));
    }

    @Override
    public List<String> getGroupLights(String groupId) {
        assertSupportedStateType(groupId);
        State state = getAndAssertLightExists(groupId);
        if (isNoGroupState(state)) {
            throw new GroupNotFoundException("No group with id '" + groupId + "' found");
        }

        List<String> groupLights = new ArrayList<>();
        if (isHueGroup(state)) {
            groupLights.addAll(state.attributes.lights.stream()
                                                      .map(this::getNonGroupLightId)
                                                      .toList());
        } else {
            groupLights.addAll(state.attributes.entity_id);
        }

        if (groupLights.isEmpty()) {
            throw new EmptyGroupException("Group with id '" + groupId + "' does not contain any lights!");
        }
        return groupLights;
    }

    @Override
    public List<String> getAssignedGroups(String lightId) {
        String lightName = getLightName(lightId);
        return getOrLookupStates().values()
                                  .stream()
                                  .filter(HassApiImpl::isGroupState)
                                  .filter(state -> containsLightIdOrName(state, lightId, lightName))
                                  .map(State::getEntity_id)
                                  .collect(Collectors.toList());
    }

    @Override
    public String getGroupId(String name) {
        State state = lookupStateByName(name);
        if (isNoGroupState(state)) {
            throw new GroupNotFoundException("No group with name '" + name + "' found");
        }
        return state.entity_id;
    }

    @Override
    public String getGroupName(String groupId) {
        return getLightName(groupId);
    }

    @Override
    public String getLightId(String name) {
        return lookupStateByName(name).entity_id;
    }

    private State lookupStateByName(String name) {
        List<State> states = getOrLookupStatesByName(name);
        if (states.size() > 1) {
            throw new NonUniqueNameException("There are " + states.size() + " states with the given name '" + name + "'." +
                                             " Please use a unique ID instead.");
        }
        return states.getFirst();
    }

    @Override
    public String getLightName(String id) {
        assertSupportedStateType(id);
        return getAndAssertLightExists(id).attributes.friendly_name;
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
    public void clearCaches() {
        synchronized (lightMapLock) {
            availableStatesInvalidated = true;
            nameToStatesMapInvalidated = true;
        }
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
            throw new LightNotFoundException("State with id '" + id + "' not found!");
        }
        return state;
    }

    private Map<String, State> lookupStates() {
        String response = httpResourceProvider.getResource(createUrl("/states"));
        try {
            List<State> states = mapper.readValue(response, new TypeReference<>() {
            });
            return states.stream()
                         .filter(HassApiImpl::isSupportedStateType)
                         .collect(Collectors.toMap(State::getEntity_id, Function.identity()));
        } catch (JsonProcessingException | NullPointerException e) {
            throw new ApiFailure("Failed to parse light states response '" + response + ":" + e.getLocalizedMessage());
        }
    }


    private URL createUrl(String url) {
        try {
            return new URI(baseApi + url).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Failed to construct API url", e);
        }
    }

    private LightState createLightState(State state) {
        StateAttributes attributes = state.getAttributes();
        return new LightState(hassToHueBrightness(attributes.brightness), attributes.color_temp, getXY(attributes.xy_color, 0),
                getXY(attributes.xy_color, 1), null, null, getEffect(attributes.effect), getColorMode(attributes.color_mode),
                getReachable(state.state), getOn(state.state), createLightCapabilities(state));
    }

    /**
     * Convert hass format 0..255 to hue brightness 1..254
     */
    private static Integer hassToHueBrightness(Integer hassBrightness) {
        if (hassBrightness == null) {
            return null;
        }
        return Math.round(((float) hassBrightness / 255) * 253 + 1);
    }

    /**
     * Convert hass format 0..255 to hue brightness 1..254
     */
    private static Integer hueToHassBrightness(Integer hueBrightness) {
        if (hueBrightness == null) {
            return null;
        }
        return Math.round(((float) hueBrightness - 1) / 253 * 255);
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

    private static String getColorMode(String colorMode) {
        if (colorMode == null) {
            return null;
        }
        if (colorMode.equals("color_temp")) {
            return "ct";
        } else if (colorMode.equals("xy")) {
            return "xy";
        }
        return null;
    }

    private static boolean getReachable(String state) {
        return !"unavailable".equals(state);
    }

    private static boolean getOn(String state) {
        return "on".equals(state);
    }

    private static LightCapabilities createLightCapabilities(State state) {
        StateAttributes attributes = state.attributes;
        return new LightCapabilities(null, null, attributes.min_mireds, attributes.max_mireds,
                getCapabilities(state));
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
                                            .orElseThrow(() -> new LightNotFoundException("Non-group state with name '" + name + "' was not found!"));
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
        return state.attributes.entity_id != null;
    }

    private boolean containsLightIdOrName(State state, String lightId, String lightName) {
        if (isHueGroup(state)) {
            return state.attributes.lights.contains(lightName);
        } else { // isHassGroup
            return state.attributes.entity_id.contains(lightId);
        }
    }

    private List<State> getOrLookupStatesByName(String name) {
        List<State> states = getOrLookupNameToStateMap().get(name);
        if (states == null) {
            throw new LightNotFoundException("State with name '" + name + "' was not found!");
        }
        return states;
    }

    private static void assertSupportedStateType(String id) {
        if (!isSupportedStateType(id)) {
            throw new UnsupportedStateException("State with id '" + id + "' is not supported");
        }
    }

    private static boolean isSupportedStateType(State state) {
        return isSupportedStateType(state.entity_id);
    }

    private static boolean isSupportedStateType(String entityId) {
        return HassSupportedEntityTypes.fromEntityId(entityId) != null;
    }

    private Float convertToSeconds(Integer transitionTime) {
        if (transitionTime == null) {
            return null;
        }
        return (float) transitionTime / 10;
    }

    private URL getUpdateUrl(PutCall putCall) {
        if (Boolean.FALSE.equals(putCall.getOn())) {
            return createUrl("/services/light/turn_off");
        } else {
            return createUrl("/services/light/turn_on");
        }
    }

    private String getBody(ChangeState changeState) {
        try {
            return mapper.writeValueAsString(changeState);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to create state body", e);
        }
    }

    @Data
    private static final class ChangeState {
        String entity_id;
        Integer brightness;
        Integer color_temp;
        String hs_color;
        Double[] xy_color;
        String effect;
        Float transition;
    }
}
