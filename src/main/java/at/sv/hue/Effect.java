package at.sv.hue;

import lombok.Builder;

@Builder(toBuilder = true)
public record Effect(String effect, Integer ct, Double x, Double y, Double speed) {
    public boolean isNone() {
        return "none".equals(effect);
    }

    public boolean hasNoParameters() {
        return ct == null && x == null && y == null && speed == null;
    }

    @Override
    public String toString() {
        return "{" +
                effect +
               getFormattedPropertyIfSet("ct", ct) +
               getFormattedPropertyIfSet("x", x) +
               getFormattedPropertyIfSet("y", y) +
               getFormattedPropertyIfSet("speed", speed) +
               '}';
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }
}
