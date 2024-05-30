package at.sv.hue.api;

import at.sv.hue.ColorMode;
import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;

@Data
@Builder(toBuilder = true)
public final class PutCall {
    String id;
    Integer bri;
    Integer ct;
    Double x;
    Double y;
    Integer hue;
    Integer sat;
    Boolean on;
    String effect;
    Integer transitionTime;
    boolean groupState;

    public boolean isNullCall() {
        return Stream.of(bri, ct, x, y, hue, sat, on, effect).allMatch(Objects::isNull);
    }

    public ColorMode getColorMode() {
        if (ct != null) {
            return ColorMode.CT;
        } else if (x != null) {
            return ColorMode.XY;
        } else if (hue != null || sat != null) {
            return ColorMode.HS;
        }
        return ColorMode.NONE;
    }

    public boolean isOn() {
        return on == Boolean.TRUE;
    }


    @Override
    public String toString() {
        return "PutCall {" +
               "id=" + id +
               getFormattedPropertyIfSet("on", on) +
               getFormattedPropertyIfSet("bri", bri) +
               getFormattedPropertyIfSet("ct", ct) +
               getFormattedPropertyIfSet("x", x) +
               getFormattedPropertyIfSet("y", y) +
               getFormattedPropertyIfSet("hue", hue) +
               getFormattedPropertyIfSet("sat", sat) +
               getFormattedPropertyIfSet("effect", effect) +
               getFormattedTransitionTimeIfSet() +
               (groupState ? ", group=true" : "") +
               "}";
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }

    private String getFormattedTransitionTimeIfSet() {
        if (transitionTime == null) return "";
        return formatPropertyName("tr") + transitionTime + " (" + Duration.ofMillis(transitionTime * 100L) + ")";
    }
}
