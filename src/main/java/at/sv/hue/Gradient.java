package at.sv.hue;

import lombok.Builder;

import java.util.List;
import java.util.Objects;

@Builder(toBuilder = true)
public record Gradient(List<Pair<Double, Double>> points, String mode) {

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Gradient gradient = (Gradient) o;
        return Objects.equals(mode, gradient.mode) && Objects.equals(points, gradient.points);
    }

    @Override
    public int hashCode() {
        return Objects.hash(points, mode);
    }

    @Override
    public String toString() {
        return "{" +
               "points=" + points +
               (mode != null ? ", mode='" + mode + '\'' : "") +
               '}';
    }
}
