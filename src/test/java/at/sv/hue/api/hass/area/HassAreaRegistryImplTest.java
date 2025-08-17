package at.sv.hue.api.hass.area;

import at.sv.hue.api.GroupInfo;
import at.sv.hue.api.LightNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class HassAreaRegistryImplTest {
    private static final String ENTITY_REGISTRY_COMMAND = "config/entity_registry/list";
    private static final String DEVICE_REGISTRY_COMMAND = "config/device_registry/list";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HassWebSocketClient webSocketClient;
    private HassAreaRegistryImpl registry;

    @BeforeEach
    void setUp() {
        webSocketClient = Mockito.mock(HassWebSocketClient.class);
        mockDevices();
        mockEntities();
        registry = new HassAreaRegistryImpl(webSocketClient);
    }

    @Test
    void lookupAreaForEntity_throwException_whenEntityNotFound() {
        assertThatThrownBy(() -> lookupAreaForEntityId("light.non_existent"))
                .isInstanceOf(LightNotFoundException.class).hasMessageContaining("light.non_existent");
    }

    @Test
    void lookupAreaForEntity_handleInvalidJsonResponse_entities() {
        mockClientResponse(ENTITY_REGISTRY_COMMAND, "invalid json");

        assertThatThrownBy(() -> lookupAreaForEntityId("light.any"))
                .isInstanceOf(HassWebSocketException.class)
                .hasMessageContaining("Failed to parse entity registry response")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void lookupAreaForEntity_handleInvalidJsonResponse_devices() {
        mockClientResponse(DEVICE_REGISTRY_COMMAND, "invalid json");

        assertThatThrownBy(() -> lookupAreaForEntityId("light.any"))
                .isInstanceOf(HassWebSocketException.class)
                .hasMessageContaining("Failed to parse device registry response")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void lookupAreaForEntity_handleFailureResponse_entities() {
        mockClientResponse(ENTITY_REGISTRY_COMMAND, getFailureResponse());

        assertThatThrownBy(() -> lookupAreaForEntityId("light.any"))
                .isInstanceOf(HassWebSocketException.class)
                .hasMessageContaining("Failed to get entity registry");
    }

    @Test
    void lookupAreaForEntity_handleFailureResponse_devices() {
        mockClientResponse(DEVICE_REGISTRY_COMMAND, getFailureResponse());

        assertThatThrownBy(() -> lookupAreaForEntityId("light.any"))
                .isInstanceOf(HassWebSocketException.class)
                .hasMessageContaining("Failed to get device registry");
    }

    @Test
    void lookupAreaForEntity_returnNull_whenEntityHasNoAreaAndDeviceMapping() {
        String entityId = "light.unmapped";
        mockEntities(createEntity(entityId, null, null));

        assertThat(lookupAreaForEntityId(entityId)).isNull();
    }

    @Test
    void lookupAreaForEntity_returnNull_whenDeviceNotFound() {
        String entityId = "light.bedroom";
        String deviceId = "non_existent_device";
        mockEntities(createEntity(entityId, null, deviceId));

        assertThat(lookupAreaForEntityId(entityId)).isNull();
    }

    @Test
    void lookupAreaForEntity_returnNull_whenDeviceHasNoArea() {
        String entityId = "light.bedroom";
        String deviceId = "device_1";
        mockEntities(createEntity(entityId, null, deviceId));
        mockDevices(createDevice(deviceId, null));

        assertThat(lookupAreaForEntityId(entityId)).isNull();
    }

    @Test
    void lookupAreaForEntity_returnNull_whenDeviceHasBlankArea() {
        String entityId = "light.bedroom";
        String deviceId = "device_1";
        mockEntities(createEntity(entityId, null, deviceId));
        mockDevices(createDevice(deviceId, "  "));

        assertThat(lookupAreaForEntityId(entityId)).isNull();
    }

    @Test
    void lookupAreaForEntity_returnNull_whenEntityHasEmptyString() {
        String entityId = "light.living_room";
        mockEntities(createEntity(entityId, "", null));

        assertThat(lookupAreaForEntityId(entityId)).isNull();
    }

    @Test
    void lookupAreaForEntity_returnNull_whenEntityHasBlankString() {
        String entityId = "light.living_room";
        mockEntities(createEntity(entityId, "  ", null));

        assertThat(lookupAreaForEntityId(entityId)).isNull();
    }

    @Test
    void lookupAreaForEntity_returnGroupInfo_whenEntityMappedDirectlyToArea() {
        String entityId = "light.living_room";
        String areaId = "living_room";
        mockEntities(createEntity(entityId, areaId, null));

        GroupInfo result = lookupAreaForEntityId(entityId);

        assertGroupInfo(result, areaId, entityId);
    }

    @Test
    void lookupAreaForEntity_returnGroupInfo_whenEntityMappedDirectlyToArea_ignoresAnyDeviceArea() {
        String entityId = "light.living_room";
        String areaId = "living_room";
        String deviceId = "device";
        mockEntities(createEntity(entityId, areaId, deviceId));
        mockDevices(createDevice(deviceId, "ignored_device_area"));

        GroupInfo result = lookupAreaForEntityId(entityId);

        assertGroupInfo(result, areaId, entityId);
    }

    @Test
    void lookupAreaForEntity_returnGroupInfo_whenEntityAreaResolvedFromDevice() {
        String entityId = "light.bedroom";
        String deviceId = "device_2";
        String areaId = "bedroom";
        mockEntities(createEntity(entityId, null, deviceId));
        mockDevices(createDevice(deviceId, areaId));

        GroupInfo result = lookupAreaForEntityId(entityId);

        assertGroupInfo(result, areaId, entityId);
    }

    @Test
    void lookupAreaForEntity_returnGroupInfoWithAllEntities_whenAreaHasMultipleEntities_ignoringNotSupportedEntityTypes() {
        String areaId = "kitchen";
        String entityId1 = "light.kitchen_1";
        String entityId2 = "light.kitchen_2";
        String entityId3 = "light.kitchen_3";
        String notSupportedEntity = "sun.sun";
        String entityId4 = "light.another1";
        String entityId5 = "light.another2";
        String entityId6 = "light.another3";
        String device1 = "device_1";
        String device2 = "device_2";
        mockEntities(
                createEntity(entityId1, areaId, null),
                createEntity(entityId2, areaId, null),
                createEntity(entityId2, areaId, null), // ignores duplicate
                createEntity(entityId3, null, device1),
                createEntity(notSupportedEntity, areaId, null),
                createEntity(entityId4, null, null),
                createEntity(entityId5, null, "unknown"),
                createEntity(entityId6, null, device2)
        );
        mockDevices(
                createDevice(device1, areaId),
                createDevice(device1, areaId), // ignores duplicate
                createDevice(device2, "another_area")
        );

        GroupInfo result = lookupAreaForEntityId(entityId1);

        assertThat(result).isNotNull();
        assertThat(result.groupId()).isEqualTo(areaId);
        assertThat(result.groupLights()).containsExactlyInAnyOrder(entityId1, entityId2, entityId3);
        assertThat(result.groupLights()).containsOnlyOnce(entityId2);
    }

    @Test
    void clearCaches_shouldRefreshRegistriesOnNextLookup() {
        String entityId = "light.living_room";
        String areaId = "living_room";
        mockEntities(createEntity(entityId, areaId, null));

        GroupInfo initialResult = lookupAreaForEntityId(entityId);
        assertGroupInfo(initialResult, areaId, entityId);

        registry.clearCaches();
        mockEntities(createEntity(entityId, "new_area", null));

        GroupInfo updatedResult = lookupAreaForEntityId(entityId);
        assertGroupInfo(updatedResult, "new_area", entityId);
    }

    private void mockDevices(DeviceRegistryEntry... devices) {
        mockClientResponse(DEVICE_REGISTRY_COMMAND, getRegistryResponse(List.of(devices)));
    }

    private void mockEntities(EntityRegistryEntry... entities) {
        mockClientResponse(ENTITY_REGISTRY_COMMAND, getRegistryResponse(List.of(entities)));
    }

    private void mockClientResponse(String command, String response) {
        when(webSocketClient.sendCommand(command)).thenReturn(response);
    }

    private GroupInfo lookupAreaForEntityId(String entityId) {
        return registry.lookupAreaForEntity(entityId);
    }

    private void assertGroupInfo(GroupInfo result, String expectedAreaId, String expectedEntityId) {
        assertThat(result).isNotNull();
        assertThat(result.groupId()).isEqualTo(expectedAreaId);
        assertThat(result.groupLights()).containsExactly(expectedEntityId);
    }

    private EntityRegistryEntry createEntity(String entityId, String areaId, String deviceId) {
        EntityRegistryEntry entry = new EntityRegistryEntry();
        entry.setEntity_id(entityId);
        entry.setArea_id(areaId);
        entry.setDevice_id(deviceId);
        return entry;
    }

    private DeviceRegistryEntry createDevice(String deviceId, String areaId) {
        DeviceRegistryEntry entry = new DeviceRegistryEntry();
        entry.setId(deviceId);
        entry.setArea_id(areaId);
        return entry;
    }

    private String getRegistryResponse(List<?> entries) {
        try {
            String arrayContent = MAPPER.writeValueAsString(entries);
            return """
                    {
                      "id": 1,
                      "type": "result",
                      "success": true,
                      "anotherField": "thatIsIgnored",
                      "result": %s
                    }
                    """.formatted(arrayContent);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert entries to JSON", e);
        }
    }

    private String getFailureResponse() {
        return """
                {
                  "id": 1,
                  "type": "result",
                  "success": false,
                  "error": {
                      "code": "not_found",
                      "message": "Entity not found"
                  }
                }
                """;
    }
}
