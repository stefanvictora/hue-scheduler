package at.sv.hue.api.hass.area;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
class DeviceRegistryEntry {
    private String area_id;
    private String id;
}
