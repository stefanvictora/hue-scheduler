package at.sv.hue.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
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
        baseApi = "http://" + ip + "/api/" + username;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public LightState getLightState(int id) {
        String response = getResourceAndAssertNoErrors(getLightStateUrl(id));
        try {
            Light light = mapper.readValue(response, Light.class);
            State state = light.state;
            return new LightState(state.bri, state.ct, getX(state), getY(state), state.effect, state.reachable, state.on);
        } catch (JsonProcessingException | NullPointerException e) {
            throw new HueApiFailure("Failed to parse light state response '" + response + "' for id " + id + ": " + e.getLocalizedMessage());
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

    private Double getX(State state) {
        return getXY(state, 0);
    }

    private Double getY(State state) {
        return getXY(state, 1);
    }

    private Double getXY(State state, int i) {
        if (state.xy != null) {
            return state.xy[i];
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
    public boolean putState(int id, Integer bri, Integer ct, Double x, Double y, Integer hue, Integer sat, String effect,
                            Boolean on, Integer transitionTime, boolean groupState) {
        if (groupState) {
            rateLimiter.acquire(10);
        } else {
            rateLimiter.acquire(1);
        }
        return assertNoPutErrors(resourceProvider.putResource(getUpdateUrl(id, groupState),
                getBody(new State(bri, ct, x, y, hue, sat, effect, on, transitionTime))));
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
        Group group = getOrLookupGroups().get(groupId);
        if (group == null) {
            throw new GroupNotFoundException("Group with id '" + groupId + "' not found!");
        }
        Integer[] lights = group.lights;
        if (lights.length == 0) {
            throw new EmptyGroupException("Group with id '" + groupId + "' has no lights to control!");
        }
        return Arrays.asList(lights);
    }

    private Map<Integer, Group> getOrLookupGroups() {
        synchronized (groupMapLock) {
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
        Group group = getOrLookupGroups().get(groupId);
        if (group == null) {
            throw new GroupNotFoundException("Group with id '" + groupId + "' not found!");
        }
        return group.name;
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
        Light light = getAndAssertLight(id);
        return light.name;
    }

    private Light getAndAssertLight(int id) {
        Light light = getOrLookupLights().get(id);
        if (light == null) {
            throw new LightNotFoundException("Light with id '" + id + "' not found!");
        }
        return light;
    }

    @Override
    public LightCapabilities getLightCapabilities(int id) {
        Light light = getAndAssertLight(id);
        if (light.capabilities == null || light.capabilities.control == null) return LightCapabilities.NO_CAPABILITIES;
        Control control = light.capabilities.control;
        return new LightCapabilities(control.colorgamut, getMinCtOrNull(control), getMaxCtOrNull(control));
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

    private static final class Light {
        State state;
        Capabilities capabilities;
        String name;

        public void setState(State state) {
            this.state = state;
        }

        public void setCapabilities(Capabilities capabilities) {
            this.capabilities = capabilities;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final class State {
        Integer bri;
        Integer ct;
        Double[] xy;
        Integer hue;
        Integer sat;
        String effect;
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

        public Integer getBri() {
            return bri;
        }

        public void setBri(Integer bri) {
            this.bri = bri;
        }

        public Integer getCt() {
            return ct;
        }

        public void setCt(Integer ct) {
            this.ct = ct;
        }

        public Double[] getXy() {
            return xy;
        }

        public void setXy(Double[] xy) {
            this.xy = xy;
        }

        public Integer getHue() {
            return hue;
        }

        public void setHue(Integer hue) {
            this.hue = hue;
        }

        public Integer getSat() {
            return sat;
        }

        public void setSat(Integer sat) {
            this.sat = sat;
        }

        public String getEffect() {
            return effect;
        }

        public void setEffect(String effect) {
            this.effect = effect;
        }

        public Boolean getOn() {
            return on;
        }

        public void setOn(Boolean on) {
            this.on = on;
        }

        public Integer getTransitiontime() {
            return transitiontime;
        }

        public void setTransitiontime(Integer transitiontime) {
            this.transitiontime = transitiontime;
        }

        public Boolean getReachable() {
            return reachable;
        }

        public void setReachable(Boolean reachable) {
            this.reachable = reachable;
        }

        public Boolean isReachable() {
            return reachable;
        }
    }

    private static final class Capabilities {
        Control control;

        public void setControl(Control control) {
            this.control = control;
        }
    }

    private static final class Control {
        String colorgamuttype;
        Double[][] colorgamut;
        Ct ct;

        public void setColorgamuttype(String colorgamuttype) {
            this.colorgamuttype = colorgamuttype;
        }

        public void setColorgamut(Double[][] colorgamut) {
            this.colorgamut = colorgamut;
        }

        public void setCt(Ct ct) {
            this.ct = ct;
        }
    }

    private static final class Ct {
        int min;
        int max;

        public void setMin(int min) {
            this.min = min;
        }

        public void setMax(int max) {
            this.max = max;
        }
    }

    private static final class Group {
        String name;
        Integer[] lights = new Integer[0];

        public void setName(String name) {
            this.name = name;
        }

        public void setLights(Integer[] lights) {
            this.lights = lights;
        }
    }

    private static class HueApiResponse {
        private Error error;

        public void setError(Error error) {
            this.error = error;
        }
    }

    private static class Error {
        int type;
        String address;
        String description;

        public void setType(int type) {
            this.type = type;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    private static final class LightIsOff extends RuntimeException {
        private LightIsOff() {
        }
    }
}
