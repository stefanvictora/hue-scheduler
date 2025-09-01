package at.sv.hue.api.hue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
final class Action {
    On on;
    Dimming dimming;
    Color color;
    ColorTemperature color_temperature;
    Dynamics dynamics;
    Effects effects_v2;

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
}
