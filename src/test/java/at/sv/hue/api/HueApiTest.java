package at.sv.hue.api;

import at.sv.hue.LightState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

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
    private final String transitionTime = "\"transitiontime\":2";

    private void setGetResponse(String expectedUrl, String response) {
        this.expectedUrl = baseUrl + expectedUrl;
        this.response = response;
    }

    private void setPutResponse(String expectedUrl, String expectedBody, String response) {
        this.expectedUrl = baseUrl + expectedUrl;
        this.expectedBody = expectedBody;
        this.response = response;
    }

    private String createApiStateResponse(double x, double y, int ct, int bri, boolean reachable) {
        return "{\n" +
                "\"state\": {\n" +
                "\"on\": true,\n" +
                "\"bri\": " + bri + ",\n" +
                "\"hue\": 979,\n" +
                "\"sat\": 254,\n" +
                "\"xy\": [\n" +
                "" + x + ",\n" +
                "" + y + "\n" +
                "],\n" +
                "\"ct\": " + ct + ",\n" +
                "\"reachable\": " + reachable + "\n" +
                "}, \"name\": \"Name 2\"}";
    }

    private LightState getState(int lightId) {
        return api.getState(lightId);
    }

    private void assertLightState(LightState lightState, boolean reachable, Double x, Double y, Integer ct, int brightness) {
        assertNotNull(lightState, "Light state is null");
        assertThat("Reachable differs", lightState.isReachable(), is(reachable));
        assertThat("X differs", lightState.getX(), is(x));
        assertThat("Y differs", lightState.getY(), is(y));
        assertThat("Brightness differs", lightState.getBrightness(), is(brightness));
        assertThat("Color temperature differs", lightState.getColorTemperature(), is(ct));
    }

    private boolean putState(int id, int bri) {
        return api.putState(id, bri, null, null, null);
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
                    return response;
                }
                return null;
            }

            @Override
            public String putResource(URL url, String body) {
                if (assertResponseMatch) {
                    assertEquals(expectedUrl, url.toString(), "Url differs");
                    assertEquals(expectedBody, body, "Body differs");
                }
                if (url.toString().equals(expectedUrl) && body.equals(expectedBody)) {
                    return response;
                }
                return null;
            }
        };
        api = new HueApiImpl(resourceProvider, ip, username);
        baseUrl = "http://" + ip + "/api/" + username;
    }

    @Test
    void getState_returnsLightState_callsCorrectApiURL() {
        int lightId = 22;
        double x = 0.6715;
        double y = 0.3233;
        int ct = 153;
        int bri = 100;
        setGetResponse("/lights/" + lightId, createApiStateResponse(x, y, ct, bri, true));

        LightState lightState = getState(lightId);

        assertLightState(lightState, true, x, y, ct, bri);
    }

    @Test
    void getState_differentResult_correctState() {
        int lightId = 11;
        double x = 0.5111;
        double y = 0.1132;
        int ct = 100;
        int bri = 41;
        setGetResponse("/lights/" + lightId, createApiStateResponse(x, y, ct, bri, false));

        LightState lightState = getState(lightId);

        assertLightState(lightState, false, x, y, ct, bri);
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

        LightState lightState = getState(lightId);

        assertLightState(lightState, true, null, null, null, bri);
    }

    @Test
    void putState_brightness_success_callsCorrectUrl() {
        setPutResponse("/lights/" + 15 + "/state", "{\"bri\":200," + transitionTime + "}",
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
    void putState_ct_correctBody() {
        setPutResponse("/lights/" + 16 + "/state", "{\"ct\":100," + transitionTime + "}", "[success]");

        boolean success = api.putState(16, null, null, null, 100);

        assertTrue(success, "Put not successful");
    }

    @Test
    void putState_failure_noResponse() {
        assertResponseMatch = false;

        boolean success = putState(123, 100);

        assertFalse(success, "Put did not fail");
    }

    @Test
    void putState_failure_returnsCorrectResult() {
        setPutResponse("/lights/" + 777 + "/state", "{\"bri\":300," + transitionTime + "}",
                "[\n" +
                        "{\n" +
                        "\"error\": {\n" +
                        "\"type\": 7,\n" +
                        "\"address\": \"/lights/22/state/bri\",\n" +
                        "\"description\": \"invalid value, 300}, for parameter, bri\"\n" +
                        "}\n" +
                        "}\n" +
                        "]");

        boolean success = putState(777, 300);

        assertFalse(success, "Put did not fail");
    }
}
