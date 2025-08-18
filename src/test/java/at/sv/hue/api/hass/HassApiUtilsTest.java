package at.sv.hue.api.hass;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HassApiUtilsTest {

    @Test
    void matchesSceneSyncName() {
        assertThat(HassApiUtils.matchesSceneSyncName("huescheduler_", "HueScheduler")).isTrue();
        assertThat(HassApiUtils.matchesSceneSyncName("huescheduler", "HueScheduler")).isFalse();
        assertThat(HassApiUtils.matchesSceneSyncName("something_else", "HueScheduler")).isFalse();
        assertThat(HassApiUtils.matchesSceneSyncName(null, "HueScheduler")).isFalse();
        assertThat(HassApiUtils.matchesSceneSyncName("_test__", "!Test!")).isTrue();
    }

    @Test
    void isHassConnection_null_false() {
        assertNoHassConnection(null);
    }

    @Test
    void isHassConnection_emptyString_false() {
        assertNoHassConnection("");
    }

    @Test
    void isHassConnection_simpleString_false() {
        assertNoHassConnection("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    }

    @Test
    void isHassConnection_twoParts_butNotBase64Encoded_false() {
        assertNoHassConnection("5555.56789");
    }

    @Test
    void isHassConnection_twoParts_butOnlyOneBase64Encoded_false() {
        assertNoHassConnection("SGVsbG8.56789");
    }

    @Test
    void isHassConnection_threeParts_butOnlyTwoBase64Encoded_false() {
        assertNoHassConnection("SGVsbG8.V29ybGQ.56789");
    }

    @Test
    void isHassConnection_twoParts_bothAreBase64Encoded_true() {
        assertValidHassConnection("SGVsbG8.V29ybGQ");
    }

    @Test
    void isHassConnection_threeParts_realWorldExample_true() {
        assertValidHassConnection("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                                  "eyJpc3MiOiJjZWVlMGJiOTVhZjg0N2EyYWMxMmRiYWI0NmU1Yjc4ZCIsImlhdCI6MTY5NTMyNDg3MiwiZXhwIjoyMDEwNjg0ODcyfQ." +
                                  "oS64W9CYlQW_KopRpR26giPIpn_Ju5w2Hasd5EhWgjQ");
    }

    @Test
    void getHassWebsocketOrigin_https_usesSecureWebsocketConnection() {
        assertWebsocketOrigin("https://test.example.com", "wss://test.example.com");
    }

    @Test
    void getHassWebsocketOrigin_http_usesDefaultWebsocketConnection() {
        assertWebsocketOrigin("http://localhost:8123", "ws://localhost:8123");
    }

    private static void assertNoHassConnection(String accessToken) {
        assertThat(HassApiUtils.isHassConnection(accessToken)).isFalse();
    }

    private static void assertValidHassConnection(String accessToken) {
        assertThat(HassApiUtils.isHassConnection(accessToken)).isTrue();
    }

    private static void assertWebsocketOrigin(String origin, String expected) {
        assertThat(HassApiUtils.getHassWebsocketOrigin(origin)).isEqualTo(expected);
    }
}
