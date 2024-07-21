package at.sv.hue.api.hue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
final class ResourceReference {
    String rid;
    String rtype;

    boolean isLight() {
        return "light".equals(rtype);
    }

    boolean isDevice() {
        return "device".equals(rtype);
    }

    boolean isRoom() {
        return "room".equals(rtype);
    }
}
