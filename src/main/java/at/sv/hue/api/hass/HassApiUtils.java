package at.sv.hue.api.hass;

import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

public final class HassApiUtils {
    private HassApiUtils() {
    }

    public static String getNormalizedSceneSyncName(String sceneName) {
        return normalize(sceneName);
    }

    public static boolean matchesSceneSyncName(String sceneName, String syncName) {
        return sceneName.contains(normalize(syncName) + "_");
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\W", "_");
    }

    /**
     * Checks if the provided accessToken is a JWT token. If yes, we can be sure we want to connect to a Home Assistant
     * instance.
     *
     * @return true, if the token can be used for connecting to a Home Assistant instance, false otherwise
     */
    public static boolean isHassConnection(String accessToken) {
        if (accessToken == null) {
            return false;
        }
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        return Arrays.stream(parts).allMatch(HassApiUtils::isBase64String);
    }

    private static boolean isBase64String(String str) {
        try {
            Base64.getUrlDecoder().decode(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the websocket origin based on the REST API origin, using wss:// for https:// and ws:// for http://.
     *
     * @param origin the REST api origin to get the websocket origin from
     * @return the origin to be used for a websocket connection.
     */
    public static String getHassWebsocketOrigin(String origin) {
        if (origin.startsWith("https://")) {
            return origin.replaceAll("^https://", "wss://");
        }
        return origin.replaceAll("^http://", "ws://");
    }
}
