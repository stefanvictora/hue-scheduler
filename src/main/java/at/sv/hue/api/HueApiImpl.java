package at.sv.hue.api;

import at.sv.hue.LightState;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HueApiImpl implements HueApi {

    private final HttpResourceProvider resourceProvider;
    private final ObjectMapper mapper;
    private final String baseApi;
    private final Object lightMapLock = new Object();
    private final Object groupMapLock = new Object();
    private Map<Integer, Light> availableLights;
    private Map<Integer, Group> availableGroups;
    private Map<String, Integer> lightNameToIdMap;
    private Map<String, Integer> groupNameToIdMap;

    public HueApiImpl(HttpResourceProvider resourceProvider, String ip, String username) {
        this.resourceProvider = resourceProvider;
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        baseApi = "http://" + ip + "/api/" + username;
    }

    @Override
    public LightState getLightState(int id) {
        Light light = readValue(resourceProvider.getResource(getLightStateUrl(id)), Light.class);
        State state = light.state;
        return new LightState(state.bri, state.ct, getX(state), getY(state), state.reachable, state.on);
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

    private <T> T readValue(String resource, Class<T> clazz) {
        try {
            return mapper.readValue(resource, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse result JSON for type: " + clazz.getSimpleName(), e);
        }
    }

    @Override
    public boolean putState(int id, Integer bri, Double x, Double y, Integer ct, Boolean on, Integer transitionTime,
                            boolean groupState) {
        String response = resourceProvider.putResource(getUpdateUrl(id, groupState), getBody(new State(ct, bri, x, y, on, transitionTime)));
        if (response == null) {
            return false;
        }
        return !response.contains("error") && response.contains("success");
    }

    @Override
    public List<Integer> getGroupLights(int groupId) {
        Group group = getOrLookupGroups().get(groupId);
        if (group == null) {
            throw new GroupNotFoundException("Group with id '" + groupId + "' not found!");
        }
        return Arrays.asList(group.lights);
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
        try {
            return mapper.readValue(resourceProvider.getResource(getGroupsUrl()), new TypeReference<Map<Integer, Group>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse groups response", e);
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
        try {
            return mapper.readValue(resourceProvider.getResource(getLightsUrl()), new TypeReference<Map<Integer, Light>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse lights response", e);
        }
    }

    @Override
    public String getLightName(int id) {
        Light light = getOrLookupLights().get(id);
        if (light == null) {
            throw new LightNotFoundException("Light with id '" + id + "' not found!");
        }
        return light.name;
    }

    private URL getLightsUrl() {
        return createUrl("/lights");
    }

    private String getBody(State state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to create state body", e);
        }
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
        String name;

        public void setState(State state) {
            this.state = state;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final class State {
        Boolean on;
        Boolean reachable;
        Double[] xy;
        Integer ct;
        Integer bri;
        Integer transitiontime;

        public State() {
        }

        public State(Integer ct, Integer bri, Double x, Double y, Boolean on, Integer transitiontime) {
            this.ct = ct;
            this.bri = bri;
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

        public Boolean getOn() {
            return on;
        }

        public void setOn(Boolean on) {
            this.on = on;
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

        public Double[] getXy() {
            return xy;
        }

        public void setXy(Double[] xy) {
            this.xy = xy;
        }

        public Integer getCt() {
            return ct;
        }

        public void setCt(Integer ct) {
            this.ct = ct;
        }

        public Integer getBri() {
            return bri;
        }

        public void setBri(Integer bri) {
            this.bri = bri;
        }

        public Integer getTransitiontime() {
            return transitiontime;
        }

        public void setTransitiontime(Integer transitiontime) {
            this.transitiontime = transitiontime;
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
}
