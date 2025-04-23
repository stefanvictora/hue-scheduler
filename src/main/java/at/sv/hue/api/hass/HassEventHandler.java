package at.sv.hue.api.hass;

import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.LightEventListener;
import at.sv.hue.api.SceneEventListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.Objects;

public final class HassEventHandler {

    private final ObjectMapper objectMapper;
    private final LightEventListener eventListener;
    private final SceneEventListener sceneEventListener;
    private final HassAvailabilityEventListener availabilityListener;

    public HassEventHandler(LightEventListener eventListener, SceneEventListener sceneEventListener,
                            HassAvailabilityEventListener availabilityListener) {
        this.eventListener = eventListener;
        this.sceneEventListener = sceneEventListener;
        this.availabilityListener = availabilityListener;
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
            } else if (event.isHomeAssistantStartedEvent()) {
                availabilityListener.onStarted();
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleStateChangedEvent(String entityId, State oldState, State newState) {
        if (newState == null || oldState == null) {
            return;
        }
        if (oldState.isOff() && newState.isOn()) {
            if (HassSupportedEntityType.isSupportedEntityType(entityId)) {
                eventListener.onLightOn(entityId);
            }
        } else if (oldState.isUnavailable() && newState.isOn()) {
            if (HassSupportedEntityType.isSupportedEntityType(entityId)) {
                eventListener.onPhysicalOn(entityId);
            }
        } else if (oldState.isOn() && (newState.isOff() || newState.isUnavailable())) {
            eventListener.onLightOff(entityId);
        } else if (newState.isScene() && !newState.isUnavailable() && !newState.isUnknown()) {
            if (!Objects.equals(oldState.state, newState.state)) {
                sceneEventListener.onSceneActivated(entityId);
            }
        }
    }

    @Data
    private static final class Event {
        int id;
        String type;
        EventDetails event;

        boolean isStateChangedEvent() {
            return hasEventType("state_changed");
        }

        boolean isHomeAssistantStartedEvent() {
            return hasEventType("homeassistant_started");
        }

        private boolean hasEventType(String eventType) {
            return "event".equals(this.type) && event != null && eventType.equals(event.getEvent_type());
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
