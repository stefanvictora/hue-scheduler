package at.sv.hue.api.hass.area;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
class EntityRegistryEntry {
    private String area_id;
    private String device_id;
    private String entity_id;

    public String getEffectiveAreaId(Map<String, DeviceRegistryEntry> devices) {
        if (area_id == null && device_id != null) {
            DeviceRegistryEntry device = devices.get(device_id);
            if (device != null) {
                return device.getArea_id();
            }
        }
        return area_id;
    }

    public boolean isContainedIn(String areaId, Map<String, DeviceRegistryEntry> devices) {
        return areaId.equals(getEffectiveAreaId(devices));
    }
}
