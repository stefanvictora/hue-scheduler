package at.sv.hue.api;

import at.sv.hue.ColorMode;
import at.sv.hue.Effect;
import at.sv.hue.FormatUtil;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
    Effect effect;
    Integer transitionTime;
    @EqualsAndHashCode.Exclude
    Double[][] gamut;

    public boolean isNullCall() {
        return Stream.of(bri, ct, x, y, hue, sat, on, effect).allMatch(Objects::isNull);
    }

    public ColorMode getColorMode() {
        if (ct != null) {
            return ColorMode.CT;
        } else if (x != null && y != null) {
            return ColorMode.XY;
        } else if (hue != null || sat != null) {
            return ColorMode.HS;
        }
        return ColorMode.NONE;
    }

    public boolean isOn() {
        return on == Boolean.TRUE;
    }

    public boolean isOff() {
        return on == Boolean.FALSE;
    }

    @Override
    public String toString() {
        return "{" +
               "id=" + id +
               getFormattedPropertyIfSet("on", on) +
               getFormattedBriIfSet() +
               getFormattedCtIfSet() +
               getFormattedPropertyIfSet("x", x) +
               getFormattedPropertyIfSet("y", y) +
               getFormattedPropertyIfSet("hue", hue) +
               getFormattedPropertyIfSet("sat", sat) +
               getFormattedPropertyIfSet("effect", effect) +
               getFormattedTransitionTimeIfSet() +
               "}";
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String getFormattedBriIfSet() {
        if (bri == null) return "";
        return formatPropertyName("bri") + bri + " (" + FormatUtil.formatBrightnessPercent(bri) + "%)";
    }

    private String getFormattedCtIfSet() {
        if (ct == null) return "";
        long kelvin = Math.round(1_000_000.0 / ct);
        return formatPropertyName("ct") + ct + " (" + kelvin + "K)";
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }

    private String getFormattedTransitionTimeIfSet() {
        if (transitionTime == null) return "";
        return formatPropertyName("tr") + transitionTime + " (" + Duration.ofMillis(transitionTime * 100L) + ")";
    }

    public boolean hasSameLightState(PutCall other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(this.on, other.on) &&
               Objects.equals(this.bri, other.bri) &&
               Objects.equals(this.hue, other.hue) &&
               Objects.equals(this.sat, other.sat) &&
               Objects.equals(this.effect, other.effect) &&
               Objects.equals(this.x, other.x) &&
               Objects.equals(this.y, other.y) &&
               Objects.equals(this.ct, other.ct);
    }
}
