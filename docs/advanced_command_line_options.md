# Command-line options

[Back to README](../README.md)

Use these options with Java or configure their environment-variable equivalents in Docker. For schedule syntax, see [text-file configuration](light_configuration.md) or [Hue scene schedules](scene_schedules.md).

## Index

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

- [`--bridge-failure-retry-delay`](#--bridge-failure-retry-delay) · [`--power-on-reschedule-delay`](#--power-on-reschedule-delay) · [`--event-stream-read-timeout`](#--event-stream-read-timeout) · [`--scene-update-sleep-delay`](#--scene-update-sleep-delay) · [`--fast-scene-update-sleep-delay`](#--fast-scene-update-sleep-delay)

**Performance & Rate Limiting**

- [`--max-requests-per-second`](#--max-requests-per-second) · [`--max-concurrent-requests`](#--max-concurrent-requests) · [`--control-group-lights-individually`](#--control-group-lights-individually-experimental)

**Security**

- [`--insecure`](#--insecure)

> [!NOTE]
> Every CLI option can also be set via an environment variable. Example: `--interpolate-all` ⇢ `INTERPOLATE_ALL=true`.
>
> **Mapping:** `--some-option` → `SOME_OPTION` (uppercase, hyphens → underscores).

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

Creates synced scenes that always reflect the scheduled state of a light, room, or zone.

**Home Assistant notes:**

- Schedule the **real target entity** in `input.txt` (e.g., a Zigbee2MQTT group like `light.kitchen_lights`), not a Home Assistant helper group that only wraps another entity.
- Hue Scheduler creates a synced scene for the entity's **area** and for each **HA light group** whose `attributes.entity_id` contains the scheduled entity. To get separate scenes per subgroup, place the concrete entity inside the desired HA light groups.
- HA API-created scenes are temporary and disappear on HA restart. Hue Scheduler re-creates them automatically after receiving the HA started event.
- Dynamically created scenes can't be assigned to areas, but they remain usable in automations.

**Default:** `false`

### `--require-scene-activation`

Applies scheduled states **only after** a synced scene has been activated. After activation, the current and subsequent states apply until the lights are turned off or manually modified. Use together with `--enable-scene-sync` when you want explicit, manual opt-in (e.g., via a smart switch or HA automation). If no synced scene has been activated since the last "off", no states are applied (except those with `force:true`).

Use `force:true` to override this behavior for specific states.

**Default:** `false`

### `--scene-sync-name`

Sets the name of the synced scene (used with `--enable-scene-sync`).

**Default:** `Hue Scheduler`

### `--scene-control-name`

Name of the temporary Hue scene created internally for scene scheduling (the `scene:` property). This scene is created/updated and then recalled to apply per-light states synchronously. You may need to change this if the default name conflicts with an existing scene.

**Default:** `HueTemp`

### `--scene-activation-ignore-window`

Relevant only when user-modification tracking is **enabled** (i.e., `--disable-user-modification-tracking` is **not** set).

Delay **in seconds** after detecting a scene activation during which **turn-on events** for affected lights/groups are ignored. Prevents Hue Scheduler from immediately taking over after you turned lights on via a scene.

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

Disables tracking of manual changes. By default, Hue Scheduler compares the previously seen state with the current state and only applies the scheduled state if the user hasn’t modified the light since then. To enforce a state regardless of user changes, use the per-state property `force:true`.

**Default:** `false`

### `--color-override-threshold`

OKLab color distance threshold above which a light’s color counts as **manually overridden**. Lower values catch smaller changes but may trigger during transitions; higher values ignore transition noise but might miss subtle tweaks.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `0.03–0.10`

**Default:** `0.06`

### `--brightness-override-threshold`

Brightness difference threshold (percentage points) above which a light's brightness counts as **manually overridden**. Example: `10` means a change from `50%` → `60%` triggers detection.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `5–20`

**Default:** `10` (percentage points)

### `--ct-override-threshold`

Color temperature difference threshold (**Kelvin**) above which a light's temperature counts as **manually overridden**. Example: `350` means `3000 K` → `3350 K` triggers detection.

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

Sets the application log level:

- `ERROR` — Only API error responses (should rarely occur)
- `WARN` — Also logs bridge unreachability and retries
- `INFO` — Applied light states, manual overrides, daily solar times
- `DEBUG` *(default)* — Every scheduled state, state endings, on-events
- `TRACE` — Maximum detail, including all API requests and rate-limit waits

Note: JVM arguments must appear **before** `-jar`:

```bash
java -Dlog.level=TRACE -jar hue-scheduler.jar ...
```

With Docker, set via env var:

```bash
docker run -d --name hue-scheduler -e log.level=TRACE ...
```

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

Read timeout **in minutes** for the API v2 SSE event stream. The connection is automatically restored after a timeout. The default (2 hours) may be adjusted in future releases based on further observations.

**Default:** `120` minutes

### `--scene-update-sleep-delay`

Delay **in milliseconds** between scene creation/update and scene recall during scene scheduling (`scene:` property). This ensures the bridge has processed the scene changes before recalling them. Off lights especially take longer to process scene changes.

**Default:** `13000` ms

### `--fast-scene-update-sleep-delay`

Shorter delay **in milliseconds** used for scene scheduling when the target light was recently turned on.

**Default:** `2000` ms

## Security

### `--insecure`

Disables SSL certificate validation for the Hue Bridge. Required if your bridge still uses a self-signed certificate instead of one issued by Signify. See [Philips Hue Developer Documentation](https://developers.meethue.com/develop/application-design-guidance/using-https/) (login required).

**Default:** `false`
