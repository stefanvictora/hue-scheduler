package at.sv.hue.api.hass;

import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.LightEventListener;
import at.sv.hue.api.SceneEventListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

public final class HassEventHandler {

    private final ObjectMapper objectMapper;
    private final LightEventListener eventListener;
    private final SceneEventListener sceneEventListener;

    public HassEventHandler(LightEventListener eventListener, SceneEventListener sceneEventListener) {
        this.eventListener = eventListener;
        this.sceneEventListener = sceneEventListener;
        objectMapper = new ObjectMapper();
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void onMessage(String text) {
        try {
            Event event = objectMapper.readValue(text, Event.class);
            if ("auth_invalid".equals(event.type)) {
                throw new BridgeAuthenticationFailure();
            }
            if (event.isStateChangedEvent()) {
                EventData data = event.event.data;
                handleStateChangedEvent(data.getEntity_id(), data.old_state, data.new_state);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleStateChangedEvent(String entityId, State oldState, State newState) {
        if ((oldState == null || oldState.isOff()) && newState.isOn()) {
            eventListener.onLightOn(entityId);
        } else if (oldState != null && oldState.isUnavailable() && newState.isOn()) {
            eventListener.onPhysicalOn(entityId);
        } else if (oldState != null && oldState.isOn() && (newState.isOff() || newState.isUnavailable())) {
            eventListener.onLightOff(entityId);
        } else if (newState.isScene() && !newState.isUnavailable()) {
            sceneEventListener.onSceneActivated(entityId);
        }
    }

    @Data
    private static final class Event {
        int id;
        String type;
        EventDetails event;

        boolean isStateChangedEvent() {
            return "event".equals(type) && "state_changed".equals(event.getEvent_type());
        }
    }

    @Data
    private static final class EventDetails {
        String event_type;
        EventData data;
    }

    @Data
    private static final class EventData {
        String entity_id;
        State old_state;
        State new_state;
    }
}
