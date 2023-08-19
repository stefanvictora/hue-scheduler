package at.sv.hue.api;

import lombok.Builder;
import lombok.Data;

import java.util.Objects;
import java.util.stream.Stream;

@Data
@Builder(toBuilder = true)
public final class PutCall {
    int id;
    Integer bri;
    Integer ct;
    Double x;
    Double y;
    Integer hue;
    Integer sat;
    Boolean on;
    String effect;
    Integer transitionTime;
    boolean groupState;
    
    public boolean isNullCall() {
        return Stream.of(bri, ct, x, y, hue, sat, on, effect, transitionTime).allMatch(Objects::isNull);
    }
}
