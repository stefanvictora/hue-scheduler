package at.sv.hue.api.hue;

import lombok.Data;

@Data
final class ZigbeeConnectivity {
    String id;
    String id_v1;
    String status;

    boolean isUnavailable() {
        return !"connected".equals(status);
    }
}
