package at.sv.hue.api.hass;

import lombok.Data;

@Data
final class State {
    String entity_id;
    String state;
    StateAttributes attributes;

    boolean isOn() {
        return "on".equals(state);
    }

    boolean isOff() {
        return "off".equals(state);
    }

    boolean isUnavailable() {
        return "unavailable".equals(state);
    }

    public boolean isScene() {
        return entity_id.startsWith("scene.");
    }
}
