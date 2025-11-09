package at.sv.hue.api.hue;

import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.LightCapabilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for HueApiImpl.onModification(), focusing on cache coherence and
 * the absence/presence of HTTP calls after SSE-driven updates.
 */
class HueApiCacheUpdateTest {

    private static final String HOST = "localhost";
    private static final String BASE = "https://" + HOST + "/clip/v2/resource";

    private static final String EP_LIGHT = "/light";
    private static final String EP_GROUPED_LIGHT = "/grouped_light";
    private static final String EP_SCENE = "/scene";
    private static final String EP_DEVICE = "/device";
    private static final String EP_ZONE = "/zone";
    private static final String EP_ROOM = "/room";
    private static final String EP_ZIGBEE = "/zigbee_connectivity";

    @Language("JSON")
    private static final String EMPTY = """
            { "errors": [], "data": [] }
            """;

    private HueApiImpl api;
    private HttpResourceProvider http;
    private ObjectMapper mapper;

    @BeforeEach
    void init() {
        http = mock(HttpResourceProvider.class);
        api = new HueApiImpl(http, HOST, _ -> {
        }, 5, null, null, null, null);
        mapper = new ObjectMapper();
        reset(http);
    }

    @Test
    void nullType_ok() {
        api.onModification(null, "some-id", null);
    }

    @Test
    void nullId_ok() {
        api.onModification("light", null, null);
    }

    @Test
    void bothNull_ok() {
        api.onModification(null, null, null);
    }

    @Test
    void unknownType_ok() {
        api.onModification("unknown_type", "id", null);
    }

    @Test
    void nonJsonContent_invalidatesCache_triggersNextReload() {
        // prime cache
        stubGet(EP_LIGHT, light("light-1", "Light 1"));
        stubGet(EP_DEVICE, EMPTY);
        api.getLightState("light-1");

        verifyOneGet(EP_LIGHT);
        verifyOneGet(EP_DEVICE);
        clearHttp();

        // invalid content => invalidate light cache
        api.onModification("light", "light-1", "not-a-json-node");

        // next access must re-fetch
        stubGet(EP_LIGHT, light("light-1", "Light 1 Updated"));
        stubGet(EP_DEVICE, EMPTY);
        api.getLightState("light-1");

        verifyOneGet(EP_LIGHT);
    }

    @Test
    void cacheNotPrimed_updateIgnored_noHttp() throws Exception {
        api.onModification("light", "light-1", json("""
                    { "on": { "on": true } }
                """));
        verifyNoHttpCalls();
    }

    @Test
    void null_treatedAsRemoval_noHttp() {
        primeLightCache("light-1", "Kitchen");
        clearHttp();

        api.onModification("light", "light-1", null);

        assertThatThrownBy(() -> api.getLightState("light-1")).hasMessageContaining("not found");
        verifyNoHttpCalls();
    }

    @Test
    void nullJsonNode_treatedAsRemoval_noHttp() {
        primeLightCache("light-1", "Kitchen");
        clearHttp();

        api.onModification("light", "light-1", mapper.nullNode());

        assertThatThrownBy(() -> api.getLightState("light-1")).hasMessageContaining("not found");
        verifyNoHttpCalls();
    }

    @Test
    void invalidJson_updateHandled_gracefully_noHttp() throws Exception {
        primeLightCache("light-1", "Kitchen");
        clearHttp();

        JsonNode malformed = json("""
                    { "owner": "not-a-resource-identifier-object" }
                """);

        api.onModification("light", "light-1", malformed);
        verifyNoHttpCalls();
    }

    @Test
    void lightCache_update_appliesToCache_avoidsHttp() throws Exception {
        stubGet(EP_LIGHT, light("light-1", "Kitchen Light"));
        stubGet(EP_DEVICE, EMPTY);

        assertThat(api.getLightState("light-1").isOn()).isFalse();
        verifyOneGet(EP_LIGHT);
        verifyOneGet(EP_DEVICE);
        clearHttp();

        JsonNode update = json("""
                    { "on": { "on": true }, "dimming": { "brightness": 80.0 } }
                """);

        api.onModification("light", "light-1", update);

        assertThat(api.getLightState("light-1").isOn()).isTrue();
        assertThat(api.getLightState("light-1").getBrightness()).isEqualTo(203); // 80% of 254
        verifyNoHttpCalls();
    }

    @Test
    void lightCache_update_colorTemperature_appliesToCache_keepsMissingProperties_avoidsHttp() throws Exception {
        stubGet(EP_LIGHT, """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "light-1",
                      "owner": {
                        "rid": "device-1",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Light Name"
                      },
                      "on": {
                        "on": false
                      },
                      "dimming": {
                        "brightness": 49.8
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "type": "light"
                    }
                  ]
                }
                """);
        stubGet(EP_DEVICE, EMPTY);

        LightCapabilities lightCapabilities = api.getLightState("light-1").getLightCapabilities();
        assertThat(lightCapabilities.getCtMin()).isEqualTo(153);
        assertThat(lightCapabilities.getCtMax()).isEqualTo(500);
        clearHttp();

        JsonNode update = json("""
                {
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true
                      },
                      "id": "light-1",
                      "owner": {
                        "rid": "device-1",
                        "rtype": "device"
                      },
                      "type": "light"
                    }""");

        api.onModification("light", "light-1", update);

        assertThat(api.getLightState("light-1").getLightCapabilities()).isEqualTo(lightCapabilities); // capabilities are not overridden
        verifyNoHttpCalls();
    }

    @Test
    void groupedLightCache_update_appliesToCache_avoidsHttp() throws Exception {
        stubGet(EP_GROUPED_LIGHT, groupedLight("grouped-1"));
        assertThat(api.isGroupOff("grouped-1")).isTrue();
        clearHttp();

        api.onModification("grouped_light", "grouped-1", json("""
                { "on": { "on": true } }
                """));

        assertThat(api.isGroupOff("grouped-1")).isFalse();
        verifyNoHttpCalls();
    }

    @Test
    void sceneCache_add_whenNotPresent_addsToCache_avoidsHttp() throws Exception {
        stubGet(EP_SCENE, EMPTY);
        assertThat(api.getSceneName("scene-1")).isNull();
        clearHttp();

        JsonNode newScene = json("""
                {
                      "actions": [],
                      "group": {
                        "rid": "a2f6a14c-9dd6-4d01-a31d-5a5f2e1d1b11",
                        "rtype": "zone"
                      },
                      "id": "scene-1",
                      "metadata": {
                        "name": "New Scene"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    }""");

        api.onModification("scene", "scene-1", newScene);

        assertThat(api.getSceneName("scene-1")).isEqualTo("New Scene");
        verifyNoHttpCalls();
    }

    @Test
    void sceneCache_update_appliesToCache_avoidsHttp() throws Exception {
        stubGet(EP_SCENE, scene("scene-1", "Bright"));
        assertThat(api.getSceneName("scene-1")).isEqualTo("Bright");
        clearHttp();

        api.onModification("scene", "scene-1", json("""
                { "metadata": { "name": "Bright Updated" } }
                """));

        assertThat(api.getSceneName("scene-1")).isEqualTo("Bright Updated");
        verifyNoHttpCalls();
    }

    @Test
    void sceneCache_update_actions_overridesOldValues_avoidsHttp() throws Exception {
        stubGet(EP_SCENE, scene("scene-1", "Bright"));
        stubGet(EP_ZONE, zone("zone-1", "Living Room", "grouped-light-1"));
        assertThat(api.getAffectedIdsByScene("scene-1")).containsExactlyInAnyOrder("old-light", "grouped-light-1");
        clearHttp();

        api.onModification("scene", "scene-1", json("""
                { "actions": [ { "target": { "rid": "new-light", "rtype": "light" }, "action": { "on": { "on": true } } } ] }
                """));

        assertThat(api.getAffectedIdsByScene("scene-1")).containsExactlyInAnyOrder("new-light", "grouped-light-1");
        verifyNoHttpCalls();
    }

    @Test
    void deviceCache_update_services_overridesOldValue_avoidsHttp() throws Exception {
        stubGet(EP_DEVICE, device("device-1", "Bridge"));
        stubGet(EP_LIGHT, EMPTY);
        stubGet(EP_ZONE, EMPTY);
        stubGet(EP_ROOM, EMPTY);

        assertThat(api.getAffectedIdsByDevice("device-1")).containsExactly("old-light");
        clearHttp();

        api.onModification("device", "device-1", json("""
                    { "services": [ { "rid": "new-light", "rtype": "light" } ] }
                """));

        assertThat(api.getAffectedIdsByDevice("device-1")).containsExactly("new-light");

        verifyNoHttpCalls();
    }

    @Test
    void zoneCache_update_name_applies_avoidsHttp() throws Exception {
        stubGet(EP_ZONE, zone("zone-1", "Downstairs", "grouped-light-1"));
        stubGet(EP_ROOM, EMPTY);
        stubGet(EP_GROUPED_LIGHT, groupedLight("grouped-light-1"));
        api.getGroupIdentifier("/groups/1");
        clearHttp();

        api.onModification("zone", "zone-1", json(""" 
                { "metadata": { "name": "Upstairs" } }
                """));

        assertThat(api.getGroupIdentifierByName("Upstairs").name()).isEqualTo("Upstairs");
        verifyNoHttpCalls();
    }

    @Test
    void zoneCache_update_children_overridesOldValues_avoidsHttp() throws Exception {
        stubGet(EP_ZONE, zone("zone-1", "Downstairs", "grouped-light-1"));
        stubGet(EP_ROOM, EMPTY);
        stubGet(EP_GROUPED_LIGHT, groupedLight("grouped-light-1"));
        List<String> oldGroupLights = api.getGroupLights("grouped-light-1");
        clearHttp();

        api.onModification("zone", "zone-1", json(""" 
                { "children": [ { "rid": "new-light", "rtype": "light" } ] }
                """));

        assertThat(api.getGroupLights("grouped-light-1")).doesNotContainSequence(oldGroupLights);
        verifyNoHttpCalls();
    }

    @Test
    void roomCache_update_name_applies_avoidsHttp() throws Exception {
        stubGet(EP_ROOM, room("room-1", "Kitchen", "grouped-light-1"));
        stubGet(EP_ZONE, EMPTY);
        stubGet(EP_GROUPED_LIGHT, groupedLight("grouped-light-1"));
        api.getGroupIdentifierByName("Kitchen");
        clearHttp();

        api.onModification("room", "room-1", json("""
                { "metadata": { "name": "Dining Room" } }
                """));

        assertThat(api.getGroupIdentifierByName("Dining Room").name()).isEqualTo("Dining Room");
        verifyNoHttpCalls();
    }

    @Test
    void zigbeeCache_update_status_applies_avoidsHttp() throws Exception {
        stubGet(EP_LIGHT, light("light-1", "Kitchen"));
        stubGet(EP_DEVICE, deviceWithZigbee("device-1", "zigbee-1"));
        stubGet(EP_ZIGBEE, zigbee("zigbee-1", "connected"));

        assertThat(api.getLightState("light-1").isUnavailable()).isFalse();
        clearHttp();

        api.onModification("zigbee_connectivity", "zigbee-1", json("""
                { "status": "connectivity_issue" }"""));

        assertThat(api.getLightState("light-1").isUnavailable()).isTrue();
        verifyNoHttpCalls();
    }

    private void stubGet(String endpoint, @Language("JSON") String response) {
        when(http.getResource(url(endpoint))).thenReturn(response);
    }

    private void clearHttp() {
        clearInvocations(http);
    }

    private void verifyNoHttpCalls() {
        verify(http, never()).getResource(any(URL.class));
    }

    private void verifyOneGet(String endpoint) {
        verify(http, times(1)).getResource(url(endpoint));
    }

    private URL url(String endpoint) {
        try {
            return new URI(BASE + endpoint).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Could not create URL", e);
        }
    }

    private JsonNode json(@Language("JSON") String s) throws Exception {
        return mapper.readTree(s);
    }

    private void primeLightCache(String id, String name) {
        stubGet(EP_LIGHT, light(id, name));
        stubGet(EP_DEVICE, EMPTY);
        api.getLightState(id);
        verifyOneGet(EP_LIGHT);
        verifyOneGet(EP_DEVICE);
    }

    private String light(String id, String name) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "id_v1": "/lights/1",
                  "owner": { "rid": "device-1", "rtype": "device" },
                  "metadata": { "name": "%s" },
                  "on": { "on": false },
                  "dimming": { "brightness": 50.0 },
                  "type": "light"
                } ] }""".formatted(id, name);
    }

    private String groupedLight(String id) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "id_v1": "/groups/1",
                  "owner": { "rid": "zone-1", "rtype": "zone" },
                  "on": { "on": false },
                  "type": "grouped_light"
                } ] }""".formatted(id);
    }

    private String scene(String id, String name) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "id_v1": "/scenes/1",
                  "metadata": { "name": "%s" },
                  "group": { "rid": "zone-1", "rtype": "zone" },
                  "actions": [ { "target": { "rid": "old-light", "rtype": "light" }, "action": { "on": { "on": true } } } ],
                  "type": "scene"
                } ] }""".formatted(id, name);
    }

    private String device(String id, String name) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "metadata": { "name": "%s" },
                  "services": [ { "rid": "old-light", "rtype": "light" } ],
                  "type": "device"
                } ] }""".formatted(id, name);
    }

    private String deviceWithZigbee(String deviceId, String zigbeeId) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "metadata": { "name": "Device" },
                  "services": [ { "rid": "%s", "rtype": "zigbee_connectivity" } ],
                  "type": "device"
                } ] }""".formatted(deviceId, zigbeeId);
    }

    private String zone(String id, String name, String groupedLightId) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "id_v1": "/groups/1",
                  "metadata": { "name": "%s" },
                  "services": [ { "rid": "%s", "rtype": "grouped_light" } ],
                  "children": [ {"rid": "old-light", "rtype": "light" } ],
                  "type": "zone"
                } ] }""".formatted(id, name, groupedLightId);
    }

    private String room(String id, String name, String groupedLightId) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "id_v1": "/groups/1",
                  "metadata": { "name": "%s" },
                  "services": [ { "rid": "%s", "rtype": "grouped_light" } ],
                  "children": [],
                  "type": "room"
                } ] }""".formatted(id, name, groupedLightId);
    }

    private String zigbee(String id, String status) {
        return """
                { "errors": [], "data": [ {
                  "id": "%s",
                  "status": "%s",
                  "type": "zigbee_connectivity"
                } ] }""".formatted(id, status);
    }
}
