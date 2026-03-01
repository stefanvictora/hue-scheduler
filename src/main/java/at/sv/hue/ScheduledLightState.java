package at.sv.hue;

import lombok.Builder;
import lombok.Getter;

@Builder(toBuilder = true)
@Getter
public final class ScheduledLightState {
    private final String id;
    private final Integer bri;
    private final Integer ct;
    private final Double x;
    private final Double y;
    private final Boolean on;
    private final Effect effect;
    private final Gradient gradient;
    private final Double[][] gamut;

    public boolean isNullState() {
        return on == null && hasNoOtherPropertiesThanOn();
    }

    private boolean hasNoOtherPropertiesThanOn() {
        return bri == null && ct == null && x == null && y == null && effect == null && gradient == null;
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
               getFormattedBriIfSet() +
               getFormattedCtIfSet() +
               getFormattedPropertyIfSet("x", x) +
               getFormattedPropertyIfSet("y", y) +
               getFormattedPropertyIfSet("on", on) +
               getFormattedPropertyIfSet("effect", effect) +
               getFormattedPropertyIfSet("gradient", gradient)
               + ")";
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

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }

    public static class ScheduledLightStateBuilder {
        public ScheduledLightStateBuilder effect(String effect) {
            if (effect == null) {
                this.effect = null;
                return this;
            }
            this.effect = Effect.builder().effect(effect).build();
            return this;
        }

        public ScheduledLightStateBuilder effect(Effect effect) {
            this.effect = effect;
            return this;
        }
    }
}
