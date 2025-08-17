# Philips Hue Authentication

If you don't know your bridge's IP address, visit [https://discovery.meethue.com](https://discovery.meethue.com/) and copy the `internalipaddress` value. Example:

```json
[{"id":"<id>","internalipaddress":"192.168.0.59"}]
```

## Create a Hue username (API key)

1. Open the Hue CLIP debug tool in your browser: `http://<BRIDGE_IP_ADDRESS>/debug/clip.html`

2. In the form, enter:
    ```
    URL:	/api
    Body:	{"devicetype":"hue_scheduler#<name>"}
    ```
    `<name>` can be any label up to **26 characters** so that the full `devicetype` (including the `hue_scheduler#` prefix) stays within the Hue limit.

3. Press the **link button** on the Hue Bridge (the big round button). Within **30 seconds**, click **POST** in the CLIP tool.
4. You should receive a success response containing your **username** (Hue API key). Example:
    
    ```json
    [{"success":{"username": "83b7780291a6ceffbe0bd049104df"}}]
    ```
    
    Copy and store this value securely. You’ll use it as your **Hue Scheduler access token** (e.g., `ACCESS_TOKEN` env var or the second CLI argument). You only need to do this once per bridge.

If you see `"link button not pressed"`, press the bridge button again and re-submit **POST** within 30 seconds.

### Alternative (curl)

If you prefer curl:

```bash
# 1) Press the bridge link button first
# 2) Then run within 30 seconds:
curl -X POST "http://<BRIDGE_IP_ADDRESS>/api" \
  -H "Content-Type: application/json" \
  -d '{"devicetype":"hue_scheduler#<name>"}'
```

The response will include the `"username"` as above.

> [!TIP]
> If your bridge still uses a self-signed certificate you will need to use the `--insecure` for running Hue Scheduler.
