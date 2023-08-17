package at.sv.hue.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class HueApiImpl implements HueApi {

    private final HttpResourceProvider resourceProvider;
    private final ObjectMapper mapper;
    private final String baseApi;
    private final Object lightMapLock = new Object();
    private final Object groupMapLock = new Object();
    private final RateLimiter rateLimiter;
    private Map<Integer, Light> availableLights;
    private Map<Integer, Group> availableGroups;
    private Map<String, Integer> lightNameToIdMap;
    private Map<String, Integer> groupNameToIdMap;

    public HueApiImpl(HttpResourceProvider resourceProvider, String ip, String username, RateLimiter rateLimiter) {
        this.resourceProvider = resourceProvider;
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        baseApi = "https://" + ip + "/api/" + username;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public LightState getLightState(int id) {
        String response = getResourceAndAssertNoErrors(getLightStateUrl(id));
        try {
            Light light = mapper.readValue(response, Light.class);
            State state = light.state;
            return new LightState(state.bri, state.ct, getX(state.xy), getY(state.xy), state.hue, state.sat, state.effect, state.colormode, state.reachable, state.on);
        } catch (JsonProcessingException | NullPointerException e) {
            throw new HueApiFailure("Failed to parse light state response '" + response + "' for id " + id + ": " + e.getLocalizedMessage());
        }
    }
    
    @Override
    public GroupState getGroupState(int id) {
        String response = getResourceAndAssertNoErrors(getGroupStateUrl(id));
        try {
            Group group = mapper.readValue(response, Group.class);
            StateOfGroup state = group.state;
            Action action = group.action;
            return GroupState.builder()
                    .brightness(action.bri)
                    .colorTemperature(action.ct)
                    .x(getX(action.xy))
                    .y(getY(action.xy))
                    .hue(action.hue)
                    .sat(action.sat)
                    .effect(action.effect)
                    .colormode(action.colormode)
                    .on(action.on)
                    .allOn(state.all_on)
                    .anyOn(state.any_on)
                    .build();
        } catch (JsonProcessingException | NullPointerException e) {
            throw new HueApiFailure("Failed to parse group state response '" + response + "' for id " + id + ": " + e.getLocalizedMessage());
        }
    }
    
    private String getResourceAndAssertNoErrors(URL url) {
        return assertNoErrors(resourceProvider.getResource(url));
    }

    private String assertNoErrors(String resource) {
        if (resource.contains("\"error\"")) {
            throwFirstError(getErrors(resource));
        }
        return resource;
    }

    private List<HueApiResponse> getErrors(String resource) {
        return parseErrors(resource).stream()
                                    .filter(error -> error.error != null)
                                    .collect(Collectors.toList());
    }

    private List<HueApiResponse> parseErrors(String resource) {
        try {
            return mapper.readValue(resource, mapper.getTypeFactory().constructCollectionType(List.class, HueApiResponse.class));
        } catch (JsonProcessingException ignore) {
        }
        return Collections.emptyList();
    }

    private void throwFirstError(List<HueApiResponse> errorResponses) {
        for (HueApiResponse errorResponse : errorResponses) {
            String description = errorResponse.error.description;
            switch (errorResponse.error.type) {
                case 1:
                    throw new BridgeAuthenticationFailure();
                case 201:
                    throw new LightIsOff();
                default:
                    throw new HueApiFailure(description);
            }
        }
    }

    private Double getX(Double[] xy) {
        return getXY(xy, 0);
    }

    private Double getY(Double[] xy) {
        return getXY(xy, 1);
    }

    private Double getXY(Double[] xy, int i) {
        if (xy != null) {
            return xy[i];
        }
        return null;
    }

    private URL getLightStateUrl(int id) {
        return createUrl("/lights/" + id);
    }
    
    private URL getGroupStateUrl(int id) {
        return createUrl("/groups/" + id);
    }

    private URL createUrl(String url) {
        try {
            return new URL(baseApi + url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Failed to construct API url", e);
        }
    }

    @Override
    public boolean putState(PutCall putCall) {
        if (putCall.isGroupState()) {
            rateLimiter.acquire(10);
        } else {
            rateLimiter.acquire(1);
        }
        return assertNoPutErrors(resourceProvider.putResource(getUpdateUrl(putCall.id, putCall.groupState),
                getBody(new State(putCall.bri, putCall.ct, putCall.x, putCall.y, putCall.hue, putCall.sat, putCall.effect,
                        putCall.on, putCall.transitionTime))));
    }

    private boolean assertNoPutErrors(String putResource) {
        try {
            assertNoErrors(putResource);
            return true;
        } catch (LightIsOff e) {
            return false;
        }
    }

    private String getBody(State state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to create state body", e);
        }
    }

    @Override
    public List<Integer> getGroupLights(int groupId) {
        Group group = getAndAssertGroupExists(groupId);
        Integer[] lights = group.lights;
        if (lights.length == 0) {
            throw new EmptyGroupException("Group with id '" + groupId + "' has no lights to control!");
        }
        return Arrays.asList(lights);
    }
    
    @Override
    public List<Integer> getAssignedGroups(int lightId) {
        return getOrLookupGroups().entrySet()
                .stream()
                .filter(entry -> Arrays.asList(entry.getValue().getLights()).contains(lightId))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    private Map<Integer, Group> getOrLookupGroups() {
        synchronized (groupMapLock) { // todo: add some refresh mechanism after some timeout
            if (availableGroups == null) {
                availableGroups = lookupGroups();
            }
        }
        return availableGroups;
    }

    private Map<Integer, Group> lookupGroups() {
        String response = getResourceAndAssertNoErrors(getGroupsUrl());
        try {
            return mapper.readValue(response, new TypeReference<Map<Integer, Group>>() {
            });
        } catch (JsonProcessingException e) {
            throw new HueApiFailure("Failed to parse groups response '" + response + "': " + e.getLocalizedMessage());
        }
    }

    private URL getGroupsUrl() {
        return createUrl("/groups");
    }

    @Override
    public int getGroupId(String name) {
        Integer groupId = getOrLookupGroupNameToIdMap().get(name);
        if (groupId == null) {
            throw new GroupNotFoundException("Group with name '" + name + "' was not found!");
        }
        return groupId;
    }

    private Map<String, Integer> getOrLookupGroupNameToIdMap() {
        synchronized (groupMapLock) {
            if (groupNameToIdMap == null) {
                groupNameToIdMap = new HashMap<>();
                getOrLookupGroups().forEach((id, group) -> groupNameToIdMap.put(group.name, id));
            }
        }
        return groupNameToIdMap;
    }

    @Override
    public String getGroupName(int groupId) {
        return getAndAssertGroupExists(groupId).name;
    }
    
    private Group getAndAssertGroupExists(int groupId) {
        Group group = getOrLookupGroups().get(groupId);
        if (group == null) {
            throw new GroupNotFoundException("Group with id '" + groupId + "' not found!");
        }
        return group;
    }
    
    @Override
    public int getLightId(String name) {
        Integer lightId = getOrLookupLightNameToIdMap().get(name);
        if (lightId == null) {
            throw new LightNotFoundException("Light with name '" + name + "' was not found!");
        }
        return lightId;
    }

    private Map<String, Integer> getOrLookupLightNameToIdMap() {
        synchronized (lightMapLock) {
            if (lightNameToIdMap == null) {
                lightNameToIdMap = new HashMap<>();
                getOrLookupLights().forEach((id, light) -> lightNameToIdMap.put(light.name, id));
            }
        }
        return lightNameToIdMap;
    }

    private Map<Integer, Light> getOrLookupLights() {
        synchronized (lightMapLock) {
            if (availableLights == null) {
                availableLights = lookupLights();
            }
        }
        return availableLights;
    }

    private Map<Integer, Light> lookupLights() {
        String response = getResourceAndAssertNoErrors(getLightsUrl());
        try {
            return mapper.readValue(response, new TypeReference<Map<Integer, Light>>() {
            });
        } catch (JsonProcessingException e) {
            throw new HueApiFailure("Failed to parse lights response '" + response + "': " + e.getLocalizedMessage());
        }
    }

    @Override
    public String getLightName(int id) {
        Light light = getAndAssertLightExists(id);
        return light.name;
    }
    
    private Light getAndAssertLightExists(int id) {
        Light light = getOrLookupLights().get(id);
        if (light == null) {
            throw new LightNotFoundException("Light with id '" + id + "' not found!");
        }
        return light;
    }

    @Override
    public LightCapabilities getLightCapabilities(int id) {
        Light light = getAndAssertLightExists(id);
        if (light.capabilities == null || light.capabilities.control == null) return LightCapabilities.NO_CAPABILITIES;
        Control control = light.capabilities.control;
        return new LightCapabilities(
                control.colorgamuttype,
                control.colorgamut, getMinCtOrNull(control), getMaxCtOrNull(control), getCapabilities(light.type));
    }
    
    @Override
    public LightCapabilities getGroupCapabilities(int id) {
        List<LightCapabilities> lightCapabilities = getGroupLights(id)
                .stream()
                .map(this::getLightCapabilities)
                .collect(Collectors.toList());
        return LightCapabilities.builder()
                .colorGamut(getMaxGamut(lightCapabilities))
                .capabilities(getMaxCapabilities(lightCapabilities))
                .build();
    }
    
    private static Double[][] getMaxGamut(List<LightCapabilities> lightCapabilities) {
        Map<String, Double[][]> colorGamutMap = lightCapabilities.stream()
                .filter(c -> c.getColorGamut() != null)
                .collect(Collectors.toMap(LightCapabilities::getColorGamutType, LightCapabilities::getColorGamut));
        return colorGamutMap.getOrDefault("C", colorGamutMap.getOrDefault("B", colorGamutMap.getOrDefault("A", null)));
    }
    
    private static EnumSet<Capability> getMaxCapabilities(List<LightCapabilities> lightCapabilities) {
        return EnumSet.copyOf(lightCapabilities
                .stream()
                .map(LightCapabilities::getCapabilities)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet()));
    }
    
    private EnumSet<Capability> getCapabilities(String type) {
        switch (type.toLowerCase(Locale.ENGLISH)) {
            case "extended color light":
                return EnumSet.allOf(Capability.class);
            case "color light":
                return EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF);
            case "color temperature light":
                return EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF);
            case "dimmable light":
                return EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF);
            case "on/off plug-in unit":
                return EnumSet.of(Capability.ON_OFF);
            default:
                return EnumSet.noneOf(Capability.class);
        }
    }
    
    @Override
    public void assertConnection() {
        getOrLookupLights();
    }

    private Integer getMinCtOrNull(Control control) {
        if (control.ct == null) return null;
        return control.ct.min;
    }

    private Integer getMaxCtOrNull(Control control) {
        if (control.ct == null) return null;
        return control.ct.max;
    }

    private URL getLightsUrl() {
        return createUrl("/lights");
    }

    private URL getUpdateUrl(int id, boolean groupState) {
        if (groupState) {
            return createUrl("/groups/" + id + "/action");
        } else {
            return createUrl("/lights/" + id + "/state");
        }
    }

    @Data
    private static final class Light {
        State state;
        String type;
        Capabilities capabilities;
        String name;
    }

    @Data
    private static final class State {
        Integer bri;
        Integer ct;
        Double[] xy;
        Integer hue;
        Integer sat;
        String effect;
        String colormode;
        Boolean on;
        Boolean reachable;
        Integer transitiontime;

        public State() {
        }

        public State(Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat, String effect, Boolean on, Integer transitiontime) {
            this.ct = ct;
            this.bri = bri;
            this.hue = hue;
            this.sat = sat;
            this.effect = effect;
            this.on = on;
            if (isNotDefaultValue(transitiontime)) {
                this.transitiontime = transitiontime;
            }
            if (x != null && y != null) {
                this.xy = new Double[]{x, y};
            }
        }

        private boolean isNotDefaultValue(Integer transitiontime) {
            return transitiontime != null && transitiontime != 4;
        }
    }

    @Data
    private static final class Capabilities {
        Control control;
    }

    @Data
    private static final class Control {
        String colorgamuttype;
        Double[][] colorgamut;
        Ct ct;
    }

    @Data
    private static final class Ct {
        int min;
        int max;
    }

    @Data
    private static final class Group {
        String name;
        Integer[] lights = new Integer[0];
        StateOfGroup state;
        Action action;
    }
    
    @Data
    private static final class Action {
        Integer bri;
        Integer ct;
        Double[] xy;
        Integer hue;
        Integer sat;
        String effect;
        String colormode;
        Boolean on;
    }
    
    @Data
    private static final class StateOfGroup {
        Boolean all_on;
        Boolean any_on;
    }

    @Data
    private static class HueApiResponse {
        private Error error;
    }

    @Data
    private static class Error {
        int type;
        String address;
        String description;
    }

    private static final class LightIsOff extends RuntimeException {
        private LightIsOff() {
        }
    }
}
