package at.sv.hue;

import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record Gradient(List<Pair<Double, Double>> points, String mode) {

    @Override
    public String toString() {
        return "{" +
               "points=" + points +
               (mode != null ? ", mode='" + mode + '\'' : "") +
               '}';
    }
}
