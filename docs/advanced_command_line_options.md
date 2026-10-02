# Command-line options

[Back to README](../README.md)

Use these options with Java or configure their environment-variable equivalents in Docker. For schedule syntax, see [text-file configuration](light_configuration.md) or [Hue scene schedules](scene_schedules.md). For complete container examples, see the [Docker guide](docker_examples.md).

The defaults below apply when neither a command-line value nor an environment variable is set. With Java, an explicit command-line value takes precedence over the environment variable. The Docker image reads its configuration from environment variables.

## Index

**Startup**

- [Connection, location, and configuration file](#connection-location-and-configuration-file) · [Environment variables](#environment-variables)

**Schedule Sources**

- [`--enable-auto-scene-states`](#--enable-auto-scene-states) · [`--migrate-input-to-scenes`](#--migrate-input-to-scenes)

**Scene Sync & Activation**

- [`--enable-scene-sync`](#--enable-scene-sync) · [`--require-scene-activation`](#--require-scene-activation) · [`--scene-sync-name`](#--scene-sync-name) · [`--scene-control-name`](#--scene-control-name) · [`--scene-activation-ignore-window`](#--scene-activation-ignore-window)

**Interpolation & Transitions**

- [`--interpolate-all`](#--interpolate-all) · [`--default-interpolation-transition-time`](#--default-interpolation-transition-time) · [`--min-tr-before-gap`](#--min-tr-before-gap)

**Manual Overrides & Sensitivity**

- [`--disable-user-modification-tracking`](#--disable-user-modification-tracking) · [`--color-override-threshold`](#--color-override-threshold) · [`--brightness-override-threshold`](#--brightness-override-threshold) · [`--ct-override-threshold`](#--ct-override-threshold) · [`--color-sync-threshold`](#--color-sync-threshold) · [`--brightness-sync-threshold`](#--brightness-sync-threshold) · [`--ct-sync-threshold`](#--ct-sync-threshold)

**Logging**

- [`-Dlog.level`](#-dloglevel-jvm)

**Reliability & Connectivity**

- [`--bridge-failure-retry-delay`](#--bridge-failure-retry-delay) · [`--power-on-reschedule-delay`](#--power-on-reschedule-delay) · [`--event-stream-read-timeout`](#--event-stream-read-timeout) · [`--api-cache-invalidation-interval`](#--api-cache-invalidation-interval) · [`--scene-update-sleep-delay`](#--scene-update-sleep-delay) · [`--fast-scene-update-sleep-delay`](#--fast-scene-update-sleep-delay)

**Performance & Rate Limiting**

- [`--max-requests-per-second`](#--max-requests-per-second) · [`--max-concurrent-requests`](#--max-concurrent-requests) · [`--control-group-lights-individually`](#--control-group-lights-individually-experimental)

**Security**

- [`--insecure`](#--insecure)

## Connection, location, and configuration file

```shell
java -jar hue-scheduler.jar <API_HOST> <ACCESS_TOKEN> [CONFIG_FILE] --lat=<LATITUDE> --long=<LONGITUDE> [OPTIONS]
```

Replace the angle-bracketed placeholders with your values. Square brackets indicate optional arguments; do not type the brackets.

| Java argument | Environment variable | Meaning |
|---|---|---|
| First positional argument | `API_HOST` | Hue Bridge IP address or host, or Home Assistant origin such as `http://homeassistant.local:8123`. Required. |
| Second positional argument | `ACCESS_TOKEN` | Hue Bridge application key or Home Assistant long-lived access token. Required. |
| Third positional argument | `CONFIG_FILE` | Path to the schedule file. Required unless automatic Hue scene discovery is enabled; always required for migration. In Docker, use the path **inside the container**. |
| `--lat` | `LAT` | Latitude in degrees, from `-90` to `90`. Required. |
| `--long` | `LONG` | Longitude in degrees, from `-180` to `180`. Required. |
| `--elevation` | `ELEVATION` | Elevation in meters for more accurate sunrise and sunset times. Default: `0`. |

Coordinates are required even if your schedule currently uses only clock times. Set Docker's `TZ` to your local time zone, such as `Europe/Vienna`; the image defaults to `UTC`. Java uses the system time zone unless overridden with `-Duser.timezone=Europe/Vienna` before `-jar`.

Use `java -jar hue-scheduler.jar --help` to see the installed version's command-line help, or `--version` to print its version.

## Environment variables

Application options map to uppercase names with underscores: `--some-option` becomes `SOME_OPTION`. For example:

| Java option | Docker environment setting |
|---|---|
| `--interpolate-all` | `INTERPOLATE_ALL=true` |
| `--max-concurrent-requests=2` | `MAX_CONCURRENT_REQUESTS=2` |
| `--scene-sync-name="My schedule"` | `SCENE_SYNC_NAME=My schedule` |

For boolean options, use `true` or `false`. In a Compose file, quote these values, for example `INTERPOLATE_ALL: "true"`. Logging uses the separate setting [`log.level`](#-dloglevel-jvm), with that exact spelling.

Restart the application after changing its options. For changes to a Compose file's environment variables, run `docker compose up -d` to recreate the container with the new settings; `docker compose restart` alone does not apply them.

## Schedule Sources

### `--enable-auto-scene-states`

Reads schedules from Hue scene names and saved light settings. Creating, editing, renaming, or deleting a scene updates the running schedule automatically. Requires a Philips Hue Bridge.

For example, `12:00 [Mo-Th,Su,i]` interpolates toward a scene at noon on Monday through Thursday and Sunday. See the [scene schedule guide](scene_schedules.md) for setup, day abbreviations, and all supported options.

When enabled, the `CONFIG_FILE` positional argument (or environment variable) is optional. If a configuration file is also provided, both sources are combined. An explicitly configured file must still exist and be readable.

**Default:** `false`

### `--migrate-input-to-scenes`

Creates Hue scenes from group definitions in the configuration file, then exits. Requires a Philips Hue Bridge and `CONFIG_FILE`, even when `--enable-auto-scene-states` is enabled. The input file is left unchanged.

Existing scenes with the generated name in the same group are updated. Individual-light definitions are skipped. Property-free definitions become `[gap]` scenes, and explicit `interpolate:false` becomes `[i:false]`. Read the [migration guide](scene_schedules.md#migrate-a-text-file-schedule) before switching over.

Generated names place days before other options, combine consecutive days into ranges, and omit days for daily schedules. For example, `days:Mo,Tu,We,Th,Su` with `interpolate:true` at `12:00` becomes `12:00 [Mo-Th,Su,i]`.

**Default:** `false`

## Scene Sync & Activation

### `--enable-scene-sync`

Creates and updates scenes with the current scheduled values for the rooms or groups containing scheduled lights. These scenes can be activated by a sensor, switch, or automation. The default scene name is `Hue Scheduler`.

Scene Sync publishes the schedule's current values. To **read a schedule from scene names**, enable [`--enable-auto-scene-states`](#--enable-auto-scene-states) separately. Either feature can be used without the other.

**Home Assistant notes:**

- Schedule the **real target entity** in `input.txt` (e.g., a Zigbee2MQTT group like `light.kitchen_lights`), not a Home Assistant helper group that only wraps another entity.
- Hue Scheduler creates a synced scene for the entity's **area** and for each **HA light group** whose `attributes.entity_id` contains the scheduled entity. To get separate scenes per subgroup, place the concrete entity inside the desired HA light groups.
- HA API-created scenes are temporary and disappear on HA restart. Hue Scheduler re-creates them automatically after receiving the HA started event.
- Dynamically created scenes can't be assigned to areas, but they remain usable in automations.

**Default:** `false`

### `--require-scene-activation`

Applies scheduled states **only after** a synced scene has been activated. After activation, the current and subsequent states apply until the lights are turned off or manually modified. Use this when you want to start scheduled control explicitly, for example via a smart switch or Home Assistant automation.

Requires `--enable-scene-sync`; the application will not start without it. Turning a light on normally does not start scheduled control in this mode. Use `force:true` in a file or `[f]` in a scene name to bypass the activation requirement for a particular definition.

**Default:** `false`

### `--scene-sync-name`

Sets the name of the synced scene (used with `--enable-scene-sync`).

**Default:** `Hue Scheduler`

### `--scene-control-name`

Name of the temporary Hue scene used internally to apply per-light settings together, both for the `scene:` property and for automatically discovered scene schedules. Hue Scheduler creates or updates this scene, then recalls it. You may need to change the name if it conflicts with an existing scene. This is separate from the user-facing synced scene named by `--scene-sync-name`.

**Default:** `HueTemp`

### `--scene-activation-ignore-window`

Relevant only when user-modification tracking is **enabled** (i.e., `--disable-user-modification-tracking` is **not** set).

Time **in seconds** during which a turn-on event is associated with the scene that caused it. Turning on lights through an ordinary scene pauses scheduled control, so Hue Scheduler does not immediately overwrite that scene. Activating a synced Hue Scheduler scene resumes scheduled control instead.

**Default:** `8` seconds

## Interpolation & Transitions

### `--interpolate-all`

Sets interpolation as the default for both text-file entries and automatically discovered Hue scene schedules. Override individual definitions with `interpolate:false` in a file or `[i:false]` in a scene name. Where interpolation is possible, it starts at the previous definition's scheduled time and reaches the current definition's values at its scheduled time. An explicit `tr-before` or `tr-b:` takes precedence; schedule gaps interrupt interpolation.

**Default:** `false`

### `--default-interpolation-transition-time`

Controls how quickly a light catches up when it turns on partway through a `tr-before` or interpolation. Hue Scheduler first calculates the values expected at that moment, then uses this short transition to reach them. If the previous schedule entry defines `tr`, that value is used instead.

This setting does not change the duration of the overall early transition or when the final values are due. It accepts either a multiple of 100 ms (e.g., `4`) or a duration string (e.g., `5s`, `1min`).

```text
# Uses the default catch-up transition:
Desk  06:00  bri:50%
Desk  07:00  bri:100%  tr-before:20min

# Uses the previous entry's tr when catching up:
Desk  06:00  bri:50%  tr:10s
Desk  07:00  bri:100%  tr-before:20min

# Catches up immediately:
Desk  06:00  bri:50%  tr:0
Desk  07:00  bri:100%  tr-before:20min
```

**Default:** `4` (= 400 ms)

### `--min-tr-before-gap`

Relevant only when user-modification tracking is **enabled**.

Minimum settling time **in minutes** between one transition finishing and the next schedule entry taking over. The schedule remains active during this time; Hue Scheduler shortens the earlier transition so the light holds its final values for the configured buffer.

This gives the Hue Bridge time to report the final values before the next update. Without that buffer, the scheduler can mistake the bridge's still-changing values for a manual override.

If overrides are still detected between adjacent entries with transitions, increase this value.

**Default:** `3` minutes

## Manual Overrides & Sensitivity

### `--disable-user-modification-tracking`

Disables the checks that normally pause scheduled adjustments after a manual change. With tracking enabled, turn the light off and on again, or activate a synced Hue Scheduler scene, to resume scheduled control. If `--require-scene-activation` is enabled, a synced scene activation is required.

To bypass manual changes for just one definition, use `force:true` in a file or `[f]` in a scene name instead of disabling tracking globally.

**Default:** `false`

### `--color-override-threshold`

Minimum OKLab color distance that counts as a **manual override**. Lower values detect smaller changes but may also detect differences during transitions; higher values tolerate those differences but may miss subtle adjustments.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `0.03–0.10`

**Default:** `0.06`

### `--brightness-override-threshold`

Minimum brightness difference, in percentage points, that counts as a **manual override**. For example, `10` detects a change from approximately `50%` to `60%` or more. Values are rounded to the bridge's brightness scale.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `5–20`

**Default:** `10` (percentage points)

### `--ct-override-threshold`

Minimum color temperature difference, in **Kelvin**, that counts as a **manual override**. For example, `350` detects a difference of about `350 K` or more. Bridge color temperatures are stored in mireds, so conversion can introduce small rounding differences.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `100–500`

**Default:** `350` K

### `--color-sync-threshold`

The OKLab color distance threshold above which a light’s color counts as **significantly changed** to schedule the next scene sync or background interpolation.

**Default:** `0.04`

### `--brightness-sync-threshold`

Brightness difference threshold (percentage points) above which a light's brightness counts as **significantly changed** to schedule the next scene sync or background interpolation.

**Default:** `5` (percentage points)

### `--ct-sync-threshold`

Color temperature difference threshold (**Kelvin**) above which a light's temperature counts as **significantly changed** to schedule the next scene sync or background interpolation.

**Default:** `150` K

## Logging

### `-Dlog.level` (JVM)

Sets the application log level. Each level includes messages from the less detailed levels above it:

- `ERROR` — Failed API calls, scene updates, and other errors
- `WARN` — Also logs bridge unreachability and retries
- `INFO` — Applied light states, manual overrides, daily solar times
- `DEBUG` *(default)* — Every scheduled state, state endings, on-events
- `TRACE` — Maximum detail, including all API requests and rate-limit waits

Note: JVM arguments must appear **before** `-jar`:

```bash
java -Dlog.level=TRACE -jar hue-scheduler.jar ...
```

With Docker, set the environment variable `log.level` (not `LOG_LEVEL`):

```bash
docker run -d --name hue-scheduler -e log.level=TRACE ...
```

In Compose, add `log.level: "TRACE"` under `environment`. The `...` in these commands stands for your remaining startup arguments or Docker options; see the [complete Docker examples](docker_examples.md).

## Performance & Rate Limiting

### `--max-requests-per-second`

Max number of **PUT** API requests per second. Philips Hue recommends ~**10** requests/sec overall; above that, the bridge may drop requests.

Note: Groups are controlled via broadcast messages, which are more expensive. Philips Hue recommends ≤ 1 group update/sec. Hue Scheduler automatically rate-limits light vs. group updates accordingly.

See [Hue system performance guidance](https://developers.meethue.com/develop/application-design-guidance/hue-system-performance/) (requires login).

To keep the convenience of groups while improving performance, you can try the experimental `--control-group-lights-individually` option below.

**Default & recommended:** `10`

### `--max-concurrent-requests`

Max number of **concurrent in-flight HTTP requests**. This limits parallel TLS handshakes and connections to the bridge, preventing connection resets when many states fire at once (e.g., morning schedules after an idle night).

Lower values are safer for bridge stability; higher values allow more throughput.

**Default:** `2`

### `--control-group-lights-individually` *(Experimental)*

Controls lights in a group **individually** instead of using group broadcasts. This can help in some setups but is **not recommended** anymore because it may interfere with manual-modification tracking.

Note: In this mode, Hue Scheduler does **not** validate whether **all** lights in the group support a given command. Mixed-capability groups (e.g., CT-only + color) may result in some lights not being updated.

**Default:** `false`

## Reliability & Connectivity

### `--bridge-failure-retry-delay`

Retry delay **in seconds** after a network failure or a Hue API error response.

**Default:** `10` seconds

### `--power-on-reschedule-delay`

Delay **in milliseconds** between receiving an **on-event** and (re)applying the current scheduled state.

**Default:** `150` ms

### `--event-stream-read-timeout`

Read timeout **in minutes** for the Hue Bridge's API v2 event stream (SSE). The connection is automatically restored after a timeout. This setting does not apply to Home Assistant's WebSocket connection.

**Default:** `120` minutes

### `--api-cache-invalidation-interval`

Refresh interval **in minutes** for cached API resources such as lights and groups. Hue resource events also update the caches as changes arrive; this interval provides a periodic refresh. Home Assistant resource caches are cleared at this interval and reloaded when needed.

This does **not** reload a text-file schedule. Restart Hue Scheduler after editing that file.

**Default:** `30` minutes; must be greater than `0`

### `--scene-update-sleep-delay`

Delay **in milliseconds** between creating or updating the temporary control scene and recalling it. Applies to Hue scene scheduling, including the `scene:` property and automatically discovered scene schedules. This gives the bridge time to process the changed settings before applying them; off lights especially take longer to process scene changes.

**Default:** `60000` ms

### `--fast-scene-update-sleep-delay`

Shorter delay **in milliseconds** used for Hue scene scheduling for 30 seconds after turn-on, or after live changes to a scene, its schedule, or group membership while the group is on. Live schedule changes include creating, renaming, and deleting scheduled scenes. Membership changes wait until the group's lights and scene actions agree and preserve manual overrides.

Every scene creation/update followed by a recall within that window uses this delay, including consecutive interpolation updates. A new qualifying event restarts the window; scene updates and recalls do not extend it. After the window expires, the normal `--scene-update-sleep-delay` applies again. Direct recalls and unchanged scenes do not need either delay.

**Default:** `2000` ms

## Security

### `--insecure`

Disables SSL certificate validation for the Hue Bridge. Required if your bridge still uses a self-signed certificate instead of one issued by Signify. See [Philips Hue Developer Documentation](https://developers.meethue.com/develop/application-design-guidance/using-https/) (login required).

**Default:** `false`
