package at.sv.hue;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public final class ScheduledLightState {
    private final String id;
    private final Integer bri;
    private final Integer ct;
    private final Double x;
    private final Double y;
    private final Integer hue;
    private final Integer sat;
    private final Boolean on;
    private final String effect;

    public boolean isNullState() {
        return on == null && hasNoOtherPropertiesThanOn();
    }

    private boolean hasNoOtherPropertiesThanOn() {
        return bri == null && ct == null && x == null && y == null && hue == null && sat == null && effect == null;
    }

    public boolean hasOtherPropertiesThanOn() {
        return !hasNoOtherPropertiesThanOn();
    }

    public boolean isOff() {
        return on == Boolean.FALSE;
    }

    public boolean isOn() {
        return on == Boolean.TRUE;
    }

    @Override
    public String toString() {
        return "(id=" + id +
               getFormattedPropertyIfSet("bri", bri) +
               getFormattedPropertyIfSet("ct", ct) +
               getFormattedPropertyIfSet("x", x) +
               getFormattedPropertyIfSet("y", y) +
               getFormattedPropertyIfSet("hue", hue) +
               getFormattedPropertyIfSet("sat", sat) +
               getFormattedPropertyIfSet("on", on) +
               getFormattedPropertyIfSet("effect", effect)
               + ")";
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }
}
