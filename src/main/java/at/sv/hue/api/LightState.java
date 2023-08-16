package at.sv.hue.api;

import at.sv.hue.ColorMode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public final class LightState implements State {
    private final Integer brightness;
    private final Integer colorTemperature;
    private final Double x;
    private final Double y;
    private final Integer hue;
    private final Integer sat;
    private final String effect;
    private final String colormode;
    private final boolean reachable;
    private final boolean on;

    @Override
    public ColorMode getColormode() {
        return ColorMode.parse(colormode);
    }
}
