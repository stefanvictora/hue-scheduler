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
    Effects effects;

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
        String effect;
    }
}
