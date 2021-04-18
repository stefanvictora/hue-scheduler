package at.sv.hue.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

class HueApiTest {
    private HueApi api;
    private String baseUrl;
    private String expectedUrl;
    private String expectedBody;
    private String response;
    private boolean assertResponseMatch;

    private void setGetResponse(String expectedUrl, String response) {
        this.expectedUrl = baseUrl + expectedUrl;
        this.response = response;
    }

    private void setPutResponse(String expectedUrl, String expectedBody, String response) {
        this.expectedUrl = baseUrl + expectedUrl;
        this.expectedBody = expectedBody;
        this.response = response;
    }

    private void clearResponse() {
        expectedUrl = null;
        expectedBody = null;
        response = null;
    }

    private String createApiStateResponse(double x, double y, int ct, int bri, boolean reachable, boolean on, String effect) {
        return "{\n" +
                "\"state\": {\n" +
                "\"on\": " + on + ",\n" +
                "\"bri\": " + bri + ",\n" +
                "\"hue\": 979,\n" +
                "\"sat\": 254,\n" +
                "\"effect\": \"" + effect + "\",\n" +
                "\"xy\": [\n" +
                "" + x + ",\n" +
                "" + y + "\n" +
                "],\n" +
                "\"ct\": " + ct + ",\n" +
                "\"reachable\": " + reachable + "\n" +
                "}, \"name\": \"Name 2\"}";
    }

    private LightState getLightState(int lightId) {
        return api.getLightState(lightId);
    }

    private void assertLightState(LightState lightState, boolean reachable, boolean on, Double x, Double y, Integer ct, int brightness, String effect) {
        assertNotNull(lightState, "Light state is null");
        assertThat("Reachable differs", lightState.isReachable(), is(reachable));
        assertThat("On differs", lightState.isOn(), is(on));
        assertThat("X differs", lightState.getX(), is(x));
        assertThat("Y differs", lightState.getY(), is(y));
        assertThat("Brightness differs", lightState.getBrightness(), is(brightness));
        assertThat("Color temperature differs", lightState.getColorTemperature(), is(ct));
        assertThat("Effect differs", lightState.getEffect(), is(effect));
    }

    private boolean putState(int id, int bri) {
        return putState(id, bri, false);
    }

    private boolean putState(int id, int bri, boolean groupState) {
        return api.putState(id, bri, null, null, null, null, null, null, null, null, groupState);
    }

    private List<Integer> getGroupLights(int groupId) {
        return api.getGroupLights(groupId);
    }

    private void assertCapabilities(LightCapabilities capabilities, boolean colorSupported, Double[][] colorGamut, boolean ctSupported) {
        assertThat("No capabilities returned", capabilities, notNullValue());
        assertThat("Color support differs", capabilities.isColorSupported(), is(colorSupported));
        assertThat("Color gamut differs", capabilities.getColorGamut(), is(colorGamut));
        assertThat("CT support differs", capabilities.isCtSupported(), is(ctSupported));
    }

    @BeforeEach
    void setUp() {
        String ip = "localhost";
        String username = "username";
        assertResponseMatch = true;
        HttpResourceProvider resourceProvider = new HttpResourceProvider() {
            @Override
            public String getResource(URL url) {
                if (assertResponseMatch) {
                    assertEquals(expectedUrl, url.toString(), "Url differs");
                }
                if (url.toString().equals(expectedUrl)) {
                    String result = response;
                    clearResponse();
                    return result;
                }
                throw new BridgeConnectionFailure("GET failure for '" + url + "'.");
            }

            @Override
            public String putResource(URL url, String body) {
                if (assertResponseMatch) {
                    assertEquals(expectedUrl, url.toString(), "Url differs");
                    assertEquals(expectedBody, body, "Body differs");
                }
                if (url.toString().equals(expectedUrl) && body.equals(expectedBody)) {
                    String result = response;
                    clearResponse();
                    return result;
                }
                throw new BridgeConnectionFailure("PUT failure for '" + url + "' with '" + body + "'.");
            }
        };
        api = new HueApiImpl(resourceProvider, ip, username, permits -> {});
        baseUrl = "http://" + ip + "/api/" + username;
    }

    @Test
    void checkConnection_unauthorizedUser_exception() {
        setGetResponse("/lights", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 1,\n" +
                "\"address\": \"/lights\",\n" +
                "\"description\": \"unauthorized user\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(BridgeAuthenticationFailure.class, () -> api.assertConnection());
    }

    @Test
    void getState_networkFailure_exception() {
        assertResponseMatch = false;
        assertThrows(BridgeConnectionFailure.class, () -> getLightState(1));
    }

    @Test
    void getState_unknownResourceError_exception() {
        setGetResponse("/lights/1", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 3,\n" +
                "\"address\": \"/lights/1/state\",\n" +
                "\"description\": \"resource, /lights/1/state, not available\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(HueApiFailure.class, () -> getLightState(1), "resource, /lights/1/state, not available");
    }

    @Test
    void getState_anyOtherError_exception() {
        setGetResponse("/lights/1", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 1000,\n" +
                "\"address\": \"/lights/1/state\",\n" +
                "\"description\": \"any other error\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(HueApiFailure.class, () -> getLightState(1), "any other error");
    }

    @Test
    void getState_emptyResponse_exception() {
        setGetResponse("/lights/1", "");

        assertThrows(HueApiFailure.class, () -> getLightState(1));
    }

    @Test
    void getState_emptyJSON_exception() {
        setGetResponse("/lights/1", "{}");

        assertThrows(HueApiFailure.class, () -> getLightState(1));
    }

    @Test
    void getState_returnsLightState_callsCorrectApiURL() {
        int lightId = 22;
        double x = 0.6715;
        double y = 0.3233;
        int ct = 153;
        int bri = 100;
        setGetResponse("/lights/" + lightId, createApiStateResponse(x, y, ct, bri, true, true, "colorloop"));

        LightState lightState = getLightState(lightId);

        assertLightState(lightState, true, true, x, y, ct, bri, "colorloop");
    }

    @Test
    void getState_differentResult_correctState() {
        int lightId = 11;
        double x = 0.5111;
        double y = 0.1132;
        int ct = 100;
        int bri = 41;
        setGetResponse("/lights/" + lightId, createApiStateResponse(x, y, ct, bri, false, false, "none"));

        LightState lightState = getLightState(lightId);

        assertLightState(lightState, false, false, x, y, ct, bri, "none");
    }

    @Test
    void getState_whiteBulbOnly_noNullPointerException() {
        int lightId = 11;
        int bri = 41;
        setGetResponse("/lights/" + lightId, "{\n" +
                "\"state\": {\n" +
                "\"on\": true,\n" +
                "\"bri\": " + bri + ",\n" +
                "\"sat\": 254,\n" +
                "\"reachable\": " + true + "\n" +
                "}, \"name\": \"Name 2\"}");

        LightState lightState = getLightState(lightId);

        assertLightState(lightState, true, true, null, null, null, bri, null);
    }

    @Test
    void getGroupLights_returnsLightIdsForGroup_reusesSameForName() {
        setGetResponse("/groups", "{\n" +
                "\"1\": {\n" +
                "\"name\": \"Group 1\",\n" +
                "\"lights\": [\n" +
                "\"1\",\n" +
                "\"2\",\n" +
                "\"3\"\n" +
                "]\n" +
                "},\n" +
                "\"2\": {\n" +
                "\"name\": \"Group 2\",\n" +
                "\"lights\": [\n" +
                "\"4\",\n" +
                "\"5\"\n" +
                "]\n" +
                "}}");

        List<Integer> groupLights = getGroupLights(2);
        String groupName = api.getGroupName(2);

        assertNotNull(groupLights, "GroupLights are null");
        assertArrayEquals(new Integer[]{4, 5}, groupLights.toArray(), "GroupLights differ");
        assertThat("Group name differs", groupName, is("Group 2"));
    }

    @Test
    void getGroupLights_emptyLights_exception() {
        setGetResponse("/groups", "{\n" +
                "\"1\": {\n" +
                "\"name\": \"Group 1\",\n" +
                "\"lights\": [\n" +
                "]\n" +
                "}\n" +
                "}}");

        assertThrows(EmptyGroupException.class, () -> getGroupLights(1));
    }

    @Test
    void getGroupLights_unknownId_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> api.getGroupLights(1234));
    }

    @Test
    void getGroupLights_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(HueApiFailure.class, () -> api.getGroupLights(1));
    }

    @Test
    void getGroupName_returnsNameForGroupId() {
        setGetResponse("/groups", "{\n" +
                "\"1\": {\n" +
                "\"name\": \"Group 1\",\n" +
                "\"lights\": [\n" +
                "\"1\",\n" +
                "\"2\",\n" +
                "\"3\"\n" +
                "]\n" +
                "},\n" +
                "\"2\": {\n" +
                "\"name\": \"Group 2\",\n" +
                "\"lights\": [\n" +
                "\"4\",\n" +
                "\"5\"\n" +
                "]\n" +
                "}}");

        String name1 = api.getGroupName(1);
        String name2 = api.getGroupName(2);

        assertThat("Group name differs", name1, is("Group 1"));
        assertThat("Group name differs", name2, is("Group 2"));
    }

    @Test
    void getGroupName_unknownId_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> api.getGroupName(1234));
    }

    @Test
    void getGroupName_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(HueApiFailure.class, () -> api.getGroupName(1));
    }

    @Test
    void getLightId_returnsIdForLightName_reusesResponseForMultipleRequest() {
        setGetResponse("/lights", "{\n" +
                "\"7\": {\n" +
                "\"name\": \"Lamp 1\"\n" +
                "},\n" +
                "\"1234\": {\n" +
                "\"name\": \"Lamp 2\"\n" +
                "}\n" +
                "}");

        int lightId1 = api.getLightId("Lamp 1");
        int lightId2 = api.getLightId("Lamp 2");

        assertThat("Lamp id differs", lightId1, is(7));
        assertThat("Lamp id differs", lightId2, is(1234));
    }

    @Test
    void getLightId_unknownName_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> api.getLightId("Lamp"));
    }

    @Test
    void getLightId_emptyResponse_exception() {
        setGetResponse("/lights", "");

        assertThrows(HueApiFailure.class, () -> api.getLightId("Lamp"));
    }

    @Test
    void getGroupId_returnsIdForGroupName_reusesResponse() {
        setGetResponse("/groups", "{\n" +
                "\"11\": {\n" +
                "\"name\": \"Group 1\",\n" +
                "\"lights\": [\n" +
                "\"1\",\n" +
                "\"2\"\n" +
                "]\n" +
                "},\n" +
                "\"789\": {\n" +
                "\"name\": \"Group 2\",\n" +
                "\"lights\": [\n" +
                "\"3\",\n" +
                "\"4\"\n" +
                "]\n" +
                "}\n" +
                "}");

        int groupId1 = api.getGroupId("Group 1");
        int groupId2 = api.getGroupId("Group 2");

        assertThat("Group id differs", groupId1, is(11));
        assertThat("Group id differs", groupId2, is(789));
    }

    @Test
    void getGroupId_unknownName_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> api.getGroupId("Unknown Group"));
    }

    @Test
    void getGroupId_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(HueApiFailure.class, () -> api.getGroupId("Group"));
    }

    @Test
    void getLightName_returnsNameForLightId() {
        setGetResponse("/lights", "{\n" +
                "\"7\": {\n" +
                "\"name\": \"Lamp 1\"\n" +
                "},\n" +
                "\"1234\": {\n" +
                "\"name\": \"Lamp 2\"\n" +
                "}\n" +
                "}");

        String name1 = api.getLightName(7);
        String name2 = api.getLightName(1234);

        assertThat(name1, is("Lamp 1"));
        assertThat(name2, is("Lamp 2"));
    }

    @Test
    void getLightName_unknownId_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> api.getLightName(1234));
    }

    @Test
    void getLightName_emptyResponse_exception() {
        setGetResponse("/lights", "");

        assertThrows(HueApiFailure.class, () -> api.getLightName(2));
    }

    @Test
    void getCapabilities_hasColorAndCtSupport() {
        setGetResponse("/lights", "{\n" +
                "\"22\": {\n" +
                "\"type\": \"Extended color light\",\n" +
                "\"capabilities\": {\n" +
                "\"control\": {\n" +
                "\"mindimlevel\": 200,\n" +
                "\"maxlumen\": 800,\n" +
                "\"colorgamuttype\": \"C\",\n" +
                "\"colorgamut\": [\n" +
                "[\n" +
                "0.6915,\n" +
                "0.3083\n" +
                "],\n" +
                "[\n" +
                "0.17,\n" +
                "0.7\n" +
                "],\n" +
                "[\n" +
                "0.1532,\n" +
                "0.0475\n" +
                "]\n" +
                "],\n" +
                "\"ct\": {\n" +
                "\"min\": 153,\n" +
                "\"max\": 500\n" +
                "}\n" +
                "}\n" +
                "}\n" +
                "}\n" +
                "}");

        LightCapabilities capabilities = api.getLightCapabilities(22);

        Double[][] gamut = {{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
        assertCapabilities(capabilities, true, gamut, true);
    }

    @Test
    void getCapabilities_hasNoColorAndNoCTSupport() {
        setGetResponse("/lights", "{\n" +
                "\"7\": {\n" +
                "\"type\": \"Dimmable light\",\n" +
                "\"capabilities\": {\n" +
                "\"control\": {\n" +
                "\"mindimlevel\": 5000,\n" +
                "\"maxlumen\": 1600\n" +
                "}\n" +
                "}\n" +
                "}\n" +
                "}");

        LightCapabilities capabilities = api.getLightCapabilities(7);

        assertCapabilities(capabilities, false, null, false);
    }

    @Test
    void getCapabilities_unknownId_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> api.getLightCapabilities(1234));
    }

    @Test
    void putState_brightness_success_callsCorrectUrl() {
        setPutResponse("/lights/" + 15 + "/state", "{\"bri\":200}",
                "[\n" +
                        "{\n" +
                        "\"success\": {\n" +
                        "\"/lights/22/state/bri\": 200\n" +
                        "}\n" +
                        "}\n" +
                        "]");

        boolean success = putState(15, 200);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_group_usesCorrectUrl() {
        setPutResponse("/groups/" + 9 + "/action", "{\"bri\":200}",
                "[\n" +
                        "{\n" +
                        "\"success\": {\n" +
                        "\"/groups/9/action/bri\": 200\n" +
                        "}\n" +
                        "}\n" +
                        "]");

        boolean success = putState(9, 200, true);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_ct_correctBody() {
        setPutResponse("/lights/" + 16 + "/state", "{\"ct\":100}", "[success]");

        boolean success = api.putState(16, null, 100, null, null, null, null, null, null, null, false);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_XAndY_correctBody() {
        double x = 0.6075;
        double y = 0.3525;
        setPutResponse("/lights/" + 16 + "/state", "{\"xy\":[" + x + "," + y + "]}", "[success]");

        boolean success = api.putState(16, null, null, x, y, null, null, null, null, null, false);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_hueAndSaturation_correctBody() {
        int hue = 0;
        int sat = 254;
        setPutResponse("/lights/" + 16 + "/state", "{\"hue\":" + hue + ",\"sat\":" + sat + "}", "[success]");

        boolean success = api.putState(16, null, null, null, null, hue, sat, null, null, null, false);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_on_setsFlagCorrectly() {
        setPutResponse("/lights/" + 16 + "/state", "{\"on\":true}", "[success]");

        boolean success = api.putState(16, null, null, null, null, null, null, null, true, null, false);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_transitionTime_setsTimeCorrectly() {
        setPutResponse("/lights/" + 16 + "/state", "{\"transitiontime\":2}", "[success]");

        boolean success = api.putState(16, null, null, null, null, null, null, null, null, 2, false);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_transitionTime_defaultValueOfFour_isIgnored() {
        setPutResponse("/lights/" + 16 + "/state", "{}", "[success]");

        boolean success = api.putState(16, null, null, null, null, null, null, null, null, 4, false);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_effect_correctlySet() {
        setPutResponse("/lights/" + 1 + "/state", "{\"effect\":\"colorloop\"}", "[success]");

        boolean success = api.putState(1, null, null, null, null, null, null, "colorloop", null, null, false);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_unauthorized_throwsException() {
        setPutResponse("/lights/" + 10 + "/state", "{\"bri\":100}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 1,\n" +
                "\"address\": \"/lights\",\n" +
                "\"description\": \"unauthorized user\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(BridgeAuthenticationFailure.class, () -> putState(10, 100));
    }

    @Test
    void putState_lights_resourceNotAvailable_exception() {
        setPutResponse("/lights/" + 1 + "/state", "{\"bri\":100}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 3,\n" +
                "\"address\": \"/lights/1/state\",\n" +
                "\"description\": \"resource, /lights/1/state, not available\"\n" +
                "}\n" +
                "}\n" +
                "]");

        HueApiFailure hueApiFailure = assertThrows(HueApiFailure.class, () -> putState(1, 100));
        assertThat(hueApiFailure.getMessage(), is("resource, /lights/1/state, not available"));
    }

    @Test
    void putState_groups_resourceNotAvailable_exception() {
        setPutResponse("/groups/" + 1 + "/action", "{\"bri\":100}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 3,\n" +
                "\"address\": \"/groups/1/action\",\n" +
                "\"description\": \"resource, /groups/11/action, not available\"\n" +
                "}\n" +
                "}\n" +
                "]");

        HueApiFailure hueApiFailure = assertThrows(HueApiFailure.class, () -> putState(1, 100, true));
        assertThat(hueApiFailure.getMessage(), is("resource, /groups/11/action, not available"));
    }

    @Test
    void putState_connectionFailure_exception() {
        assertResponseMatch = false;

        assertThrows(BridgeConnectionFailure.class, () -> putState(123, 100));
    }

    @Test
    void putState_emptyResponse_treatedAsSuccess() {
        setPutResponse("/lights/" + 123 + "/state", "{\"bri\":100}",
                "[\n" +
                        "]");

        boolean success = putState(123, 100);

        assertTrue(success, "Put did fail");
    }

    @Test
    void putState_fakedInvalidParameterValueResponse_exception() {
        setPutResponse("/lights/" + 777 + "/state", "{\"bri\":300}",
                "[\n" +
                        "{\"success\":{\"/lights/22/state/transitiontime\":2}}," +
                        "{\n" +
                        "\"error\": {\n" +
                        "\"type\": 7,\n" +
                        "\"address\": \"/lights/22/state/bri\",\n" +
                        "\"description\": \"invalid value, 300}, for parameter, bri\"\n" +
                        "}\n" +
                        "}\n" +
                        "]");

        HueApiFailure hueApiFailure = assertThrows(HueApiFailure.class, () -> putState(777, 300));
        assertThat(hueApiFailure.getMessage(), is("invalid value, 300}, for parameter, bri"));
    }

    @Test
    void putState_parameterNotModifiable_becauseLightIsOff_returnsFalse() {
        setPutResponse("/lights/" + 777 + "/state", "{\"bri\":200}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 201,\n" +
                "\"address\": \"/lights/777/state/bri\",\n" +
                "\"description\": \"parameter, bri, is not modifiable. Device is set to off.\"\n" +
                "}\n" +
                "}\n" +
                "]");

        boolean reachable = putState(777, 200);

        assertFalse(reachable, "Light is reachable");
    }
}
