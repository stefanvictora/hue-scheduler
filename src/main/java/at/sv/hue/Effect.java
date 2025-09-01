package at.sv.hue;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Builder(toBuilder = true)
@RequiredArgsConstructor
@Data
public class Effect {
    private final String effect;
    private final Integer ct;
    private final Double x;
    private final Double y;
    private final Double speed;

    public boolean isNone() {
        return "none".equals(effect);
    }

    public boolean hasNoParameters() {
        return ct == null && x == null && y == null && speed == null;
    }
}
