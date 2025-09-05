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
}
