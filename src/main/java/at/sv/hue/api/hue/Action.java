package at.sv.hue.api.hue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
final class Action {
    On on;
    Dimming dimming;
    Color color;
    ColorTemperature color_temperature;
    Effects effects_v2;
    Gradient gradient;
    Dynamics dynamics;

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    static class Dynamics {
        Integer duration;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    static class Effects {
        EffectsAction action;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    static class EffectsAction {
        String effect;
        EffectsParameters parameters;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    static class EffectsParameters {
        Color color;
        ColorTemperature color_temperature;
        Double speed;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    static class Gradient {
        List<GradientPoint> points;
        String mode;
    }


    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    static class GradientPoint {
        Color color;
    }
}
