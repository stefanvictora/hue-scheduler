package at.sv.hue.api;

import java.net.URL;

public interface HttpResourceProvider {
    /**
     * @return the requested resource as string. Not null.
     * @throws BridgeConnectionFailure if an IOException occurred
     * @throws BridgeAuthenticationFailure if the server rejected the response as unauthorized
     */
    String getResource(URL url);

    /**
     * @param body the json payload of the put request
     * @return the response of the server. Not null.
     * @throws BridgeConnectionFailure if an IOException occurred
     * @throws BridgeAuthenticationFailure if the server rejected the response as unauthorized
     */
    String putResource(URL url, String body);

    /**
     * @param body the json payload of the post request
     * @return the response of the server. Not null.
     * @throws BridgeConnectionFailure if an IOException occurred
     * @throws BridgeAuthenticationFailure if the server rejected the response as unauthorized
     */
    String postResource(URL url, String body);
}
