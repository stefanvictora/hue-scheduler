package at.sv.hue.api.hass.area;

import at.sv.hue.api.GroupInfo;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.hass.HassSupportedEntityType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HassAreaRegistryImpl implements HassAreaRegistry {

    private final HassWebSocketClient webSocketClient;
    private final ObjectMapper mapper;
    private volatile Map<String, EntityRegistryEntry> entityRegistryCache;
    private volatile Map<String, DeviceRegistryEntry> deviceRegistryCache;

    public HassAreaRegistryImpl(HassWebSocketClient webSocketClient) {
        this.webSocketClient = webSocketClient;
        this.mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public GroupInfo lookupAreaForEntity(String entityId) {
        Map<String, EntityRegistryEntry> entities = getEntityRegistry();
        Map<String, DeviceRegistryEntry> devices = getDeviceRegistry();
        EntityRegistryEntry targetEntry = entities.get(entityId);
        if (targetEntry == null) {
            throw new LightNotFoundException("Entity with id '" + entityId + "' not found in HA entity registry");
        }
        String effectiveAreaId = targetEntry.getEffectiveAreaId(devices);
        if (effectiveAreaId == null || effectiveAreaId.isBlank()) {
            return null;
        }
        List<String> areaEntities = entities.values().stream()
                                            .filter(entry -> entry.isContainedIn(effectiveAreaId, devices))
                                            .map(EntityRegistryEntry::getEntity_id)
                                            .collect(Collectors.toList());
        return new GroupInfo(effectiveAreaId, areaEntities);
    }

    private Map<String, EntityRegistryEntry> getEntityRegistry() {
        if (entityRegistryCache == null) {
            synchronized (this) {
                if (entityRegistryCache == null) {
                    String response = webSocketClient.sendCommand("config/entity_registry/list");
                    try {
                        EntityRegistryResponse registryResponse = mapper.readValue(response, EntityRegistryResponse.class);
                        if (!registryResponse.isSuccess()) {
                            throw new HassWebSocketException("Failed to get entity registry: " + response);
                        }
                        entityRegistryCache = registryResponse.getResult().stream()
                                                              .filter(entry -> HassSupportedEntityType.isSupportedEntityType(entry.getEntity_id()))
                                                              .collect(Collectors.toConcurrentMap(EntityRegistryEntry::getEntity_id,
                                                                      Function.identity(),
                                                                      (e1, e2) -> e1));
                    } catch (Exception e) {
                        throw new HassWebSocketException("Failed to parse entity registry response", e);
                    }
                }
            }
        }
        return entityRegistryCache;
    }

    private Map<String, DeviceRegistryEntry> getDeviceRegistry() {
        if (deviceRegistryCache == null) {
            synchronized (this) {
                if (deviceRegistryCache == null) {
                    String response = webSocketClient.sendCommand("config/device_registry/list");
                    try {
                        DeviceRegistryResponse registryResponse = mapper.readValue(response, DeviceRegistryResponse.class);
                        if (!registryResponse.isSuccess()) {
                            throw new HassWebSocketException("Failed to get device registry: " + response);
                        }
                        deviceRegistryCache = registryResponse.getResult().stream()
                                                              .filter(entry -> entry.getArea_id() != null)
                                                              .collect(Collectors.toConcurrentMap(DeviceRegistryEntry::getId,
                                                                      Function.identity(),
                                                                      (e1, e2) -> e1));
                    } catch (Exception e) {
                        throw new HassWebSocketException("Failed to parse device registry response", e);
                    }
                }
            }
        }
        return deviceRegistryCache;
    }

    @Override
    public synchronized void clearCaches() {
        entityRegistryCache = null;
        deviceRegistryCache = null;
    }

    @Data
    @NoArgsConstructor
    private static class EntityRegistryResponse {
        private int id;
        private String type;
        private boolean success;
        private List<EntityRegistryEntry> result;
    }

    @Data
    @NoArgsConstructor
    private static class DeviceRegistryResponse {
        private int id;
        private String type;
        private boolean success;
        private List<DeviceRegistryEntry> result;
    }
}
