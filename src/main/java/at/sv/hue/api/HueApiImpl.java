package at.sv.hue.api;

import at.sv.hue.LightState;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.MalformedURLException;
import java.net.URL;

public final class HueApiImpl implements HueApi {

    private final HttpResourceProvider resourceProvider;
    private final ObjectMapper mapper;
    private final String baseApi;

    public HueApiImpl(HttpResourceProvider resourceProvider, String ip, String username) {
        this.resourceProvider = resourceProvider;
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        baseApi = "http://" + ip + "/api/" + username;
    }

    @Override
    public LightState getState(int id) {
        Light light = readValue(resourceProvider.getResource(getStateUrl(id)), Light.class);
        State state = light.state;
        return new LightState(light.name, state.bri, state.ct, getX(state), getY(state), state.reachable);
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

    private URL getStateUrl(int id) {
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
    public boolean putState(int id, Integer bri, Double x, Double y, Integer ct) {
        String response = resourceProvider.putResource(getUpdateUrl(id), getBody(new State(ct, bri, x, y)));
        if (response == null) {
            return false;
        }
        return response.contains("success");
    }

    private String getBody(State state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to create state body", e);
        }
    }

    private URL getUpdateUrl(int id) {
        return createUrl("/lights/" + id + "/state");
    }

    private static final class Light {
        State state;
        String name;

        public State getState() {
            return state;
        }

        public void setState(State state) {
            this.state = state;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final class State {
        Boolean reachable;
        Double[] xy;
        Integer ct;
        Integer bri;
        Integer transitiontime = 2;

        public State() {
        }

        public State(Integer ct, Integer bri, Double x, Double y) {
            this.ct = ct;
            this.bri = bri;
        }

        public Boolean isReachable() {
            return reachable;
        }

        public void setReachable(Boolean reachable) {
            this.reachable = reachable;
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
}
