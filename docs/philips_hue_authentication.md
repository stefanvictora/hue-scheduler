# Philips Hue authentication

[Back to README](../README.md)

Hue Scheduler connects directly to your Hue Bridge using a local API key. Hue calls this key a **username**; Hue Scheduler calls it an **access token**. It is separate from your Hue account password.

## Find the bridge address

Connect your computer to the same network as the bridge. Visit [Hue Bridge discovery](https://discovery.meethue.com/) and copy the `internalipaddress` value for your bridge. Example:

```json
[{"id":"<id>","internalipaddress":"192.168.0.59"}]
```

If discovery returns an empty list or several bridges, find your bridge's IP address in the Hue app's bridge settings or your router's connected-device list. These methods are also described in the [official Hue setup guide](https://developers.meethue.com/develop/get-started-2/).

## Create a Hue username (API key)

1. Open the Hue CLIP debug tool in your browser: `https://<BRIDGE_IP_ADDRESS>/debug/clip.html`. Replace `<BRIDGE_IP_ADDRESS>` with the address you found. Your browser may show a certificate warning because it does not trust the bridge's certificate; check that the address is your local bridge before continuing.

2. In the form, enter:
    ```text
    URL: /api
    Body: {"devicetype":"hue_scheduler#scheduler"}
    ```
   The `devicetype` is a label identifying this installation; you can use the example unchanged.

3. Press the **link button** on the Hue Bridge. Within **30 seconds**, click **POST** in the CLIP tool.
4. You should receive a success response containing your **username** (Hue API key). Example:
    
    ```json
    [{"success":{"username":"YOUR_GENERATED_KEY"}}]
    ```
    
    Copy the `username` value and keep it private. Use it as **`ACCESS_TOKEN`** in Docker or as the **second argument** after the bridge address when running the JAR. Keep using the same key across restarts; create a new one only if it has been removed or the bridge has been reset.

If you see `"link button not pressed"`, press the bridge button again and re-submit **POST** within 30 seconds.

### Alternative (curl)

If the debug tool is unavailable or you prefer a terminal, run this in Bash after replacing `<BRIDGE_IP_ADDRESS>`:

```bash
# 1) Press the bridge link button first
# 2) Then run within 30 seconds:
curl --insecure -X POST "https://<BRIDGE_IP_ADDRESS>/api" \
  -H "Content-Type: application/json" \
  -d '{"devicetype":"hue_scheduler#scheduler"}'
```

The response includes the `username` as above. Here, curl's `--insecure` skips certificate validation for this one local setup request; verify that the address belongs to your bridge. On Windows, use the browser steps above or run the Bash example in WSL.

## Connect Hue Scheduler

For Hue, use the bare IP address as `API_HOST` (for example, `192.168.0.59`), without `https://` or `/api`. Continue with the [Docker examples](docker_examples.md) or [Java setup](../README.md#or-run-with-java).

Hue Scheduler includes the Hue certificate and normally validates the connection without extra options. If an older bridge still uses a self-signed certificate and the scheduler reports a certificate validation error, use `--insecure` (Docker: `INSECURE: "true"`). This disables certificate validation in the scheduler; it is separate from the curl option used above.
