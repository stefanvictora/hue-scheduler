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
import java.util.Objects;
import java.util.stream.Collectors;

public final class HueApiImpl implements HueApi {

    private final HttpResourceProvider resourceProvider;
    private final ObjectMapper mapper;
    private final String baseApi;
    private final Object lightMapLock = new Object();
    private final Object groupMapLock = new Object();
    private final RateLimiter rateLimiter;
    private Map<Integer, Light> availableLights;
    private boolean availableLightsInvalidated;
    private Map<Integer, Group> availableGroups;
    private boolean availableGroupsInvalidated;
    private Map<String, Integer> lightNameToIdMap;
    private boolean lightNameToIdMapInvalidated;
    private Map<String, Integer> groupNameToIdMap;
    private boolean groupNameToIdMapInvalidated;

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
            return createLightState(light);
        } catch (JsonProcessingException | NullPointerException e) {
            throw new HueApiFailure("Failed to parse light state response '" + response + "' for id " + id + ": " + e.getLocalizedMessage());
        }
    }

    private LightState createLightState(Light light) {
        State state = light.state;
        return new LightState(
                state.bri, state.ct, getX(state.xy), getY(state.xy), state.hue, state.sat, state.effect, state.colormode, state.reachable, state.on,
                createLightCapabilities(light));
    }

    @Override
    public List<LightState> getGroupStates(int id) {
        List<Integer> groupLights = getGroupLights(id);
        Map<Integer, Light> currentLights = lookupLights();
        return groupLights.stream()
                          .map(currentLights::get)
                          .filter(Objects::nonNull)
                          .map(this::createLightState)
                          .collect(Collectors.toList());
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
        synchronized (groupMapLock) {
            if (availableGroups == null || availableGroupsInvalidated) {
                availableGroups = lookupGroups();
                availableGroupsInvalidated = false;
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
            if (groupNameToIdMap == null || groupNameToIdMapInvalidated) {
                groupNameToIdMap = new HashMap<>();
                getOrLookupGroups().forEach((id, group) -> groupNameToIdMap.put(group.name, id));
                groupNameToIdMapInvalidated = false;
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
            if (lightNameToIdMap == null || lightNameToIdMapInvalidated) {
                lightNameToIdMap = new HashMap<>();
                getOrLookupLights().forEach((id, light) -> lightNameToIdMap.put(light.name, id));
                lightNameToIdMapInvalidated = false;
            }
        }
        return lightNameToIdMap;
    }

    private Map<Integer, Light> getOrLookupLights() {
        synchronized (lightMapLock) {
            if (availableLights == null || availableLightsInvalidated) {
                availableLights = lookupLights();
                availableLightsInvalidated = false;
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
        return createLightCapabilities(light);
    }

    private LightCapabilities createLightCapabilities(Light light) {
        return new LightCapabilities(
                getGamutTypeOrNull(light.capabilities), getColorGamutOrNull(light.capabilities),
                getMinCtOrNull(light.capabilities), getMaxCtOrNull(light.capabilities), getCapabilities(light.type));
    }

    private static String getGamutTypeOrNull(Capabilities capabilities) {
        if (capabilities == null || capabilities.control == null) return null;
        return capabilities.control.colorgamuttype;
    }

    private static Double[][] getColorGamutOrNull(Capabilities capabilities) {
        if (capabilities == null || capabilities.control == null) return null;
        return capabilities.control.colorgamut;
    }

    private Integer getMinCtOrNull(Capabilities capabilities) {
        if (capabilities == null || capabilities.control.ct == null) return null;
        return capabilities.control.ct.min;
    }

    private Integer getMaxCtOrNull(Capabilities capabilities) {
        if (capabilities == null || capabilities.control.ct == null) return null;
        return capabilities.control.ct.max;
    }

    @Override
    public LightCapabilities getGroupCapabilities(int id) {
        List<LightCapabilities> lightCapabilities = getGroupLights(id)
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
    public void clearCaches() {
        synchronized (lightMapLock) {
            availableLightsInvalidated = true;
            lightNameToIdMapInvalidated = true;
        }
        synchronized (groupMapLock) {
            availableGroupsInvalidated = true;
            groupNameToIdMapInvalidated = true;
        }
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

    private EnumSet<Capability> getCapabilities(String type) {
        if (type == null) {
            return EnumSet.noneOf(Capability.class);
        }
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
