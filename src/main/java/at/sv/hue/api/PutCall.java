package at.sv.hue.api;

import lombok.Builder;
import lombok.Data;

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
}
