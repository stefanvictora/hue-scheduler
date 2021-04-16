package at.sv.hue.api;

import java.net.URL;

public interface HttpResourceProvider {
    /**
     * @return the requested resource as string. Not null.
     * @throws BridgeConnectionFailure if an IOException occurred
     */
    String getResource(URL url);

    /**
     * @param body the json payload of the put request
     * @return the response of the server. Not null.
     * @throws BridgeConnectionFailure if an IOException occurred
     */
    String putResource(URL url, String body);
}
