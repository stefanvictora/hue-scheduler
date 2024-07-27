package at.sv.hue.api;

import java.net.URL;

public interface HttpResourceProvider {
    /**
     * @return the requested resource as string. Not null.
     * @throws BridgeAuthenticationFailure if the server rejected the response as unauthorized (401, 403)
     * @throws BridgeConnectionFailure     if an IOException occurred
     * @throws ResourceNotFoundException   if the response code is 404
     * @throws ApiFailure                  if the response code is 5xx or 429
     */
    String getResource(URL url);

    /**
     * @param body the json payload of the put request
     * @return the response of the server. Not null.
     * @throws BridgeAuthenticationFailure if the server rejected the response as unauthorized (401, 403)
     * @throws BridgeConnectionFailure     if an IOException occurred
     * @throws ResourceNotFoundException   if the response code is 404
     * @throws ApiFailure                  if the response code is 5xx or 429
     */
    String putResource(URL url, String body);

    /**
     * @param body the json payload of the post request
     * @return the response of the server. Not null.
     * @throws BridgeAuthenticationFailure if the server rejected the response as unauthorized (401, 403)
     * @throws BridgeConnectionFailure     if an IOException occurred
     * @throws ResourceNotFoundException   if the response code is 404
     * @throws ApiFailure                  if the response code is 5xx or 429
     */
    String postResource(URL url, String body);
}
