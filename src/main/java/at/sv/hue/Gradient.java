package at.sv.hue;

import lombok.Builder;

import java.util.List;

@Builder
public record Gradient(List<Pair<Double, Double>> points, String mode) {
}
