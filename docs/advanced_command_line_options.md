# Advanced Configuration — CLI Flags & Tuning
 
## Index

**Scene Sync & Activation**

- [`--enable-scene-sync`](#--enable-scene-sync) · [`--require-scene-activation`](#--require-scene-activation) · [`--scene-sync-name`](#--scene-sync-name) · [`--scene-activation-ignore-window`](#--scene-activation-ignore-window)

**Interpolation & Transitions**

- [`--interpolate-all`](#--interpolate-all) · [`--default-interpolation-transition-time`](#--default-interpolation-transition-time) · [`--min-tr-before-gap`](#--min-tr-before-gap)

**Manual Overrides & Sensitivity**

- [`--disable-user-modification-tracking`](#--disable-user-modification-tracking) · [`--color-override-threshold`](#--color-override-threshold) · [`--brightness-override-threshold`](#--brightness-override-threshold) · [`--ct-override-threshold`](#--ct-override-threshold) · [`--color-sync-threshold`](#--color-sync-threshold) · [`--brightness-sync-threshold`](#--brightness-sync-threshold) · [`--ct-sync-threshold`](#--ct-sync-threshold)

**Logging**

- [`-Dlog.level`](#-dloglevel-jvm)

**Reliability & Connectivity**

- [`--bridge-failure-retry-delay`](#--bridge-failure-retry-delay) · [`--power-on-reschedule-delay`](#--power-on-reschedule-delay) · [`--event-stream-read-timeout`](#--event-stream-read-timeout)

**Performance & Rate Limiting**

- [`--max-requests-per-second`](#--max-requests-per-second) · [`--max-concurrent-requests`](#--max-concurrent-requests) · [`--control-group-lights-individually`](#--control-group-lights-individually-experimental)

**Security**

- [`--insecure`](#--insecure)

> [!NOTE]
> Every CLI option can also be set via an environment variable. Example: `--interpolate-all` ⇢ `INTERPOLATE_ALL=true`.
>
> **Mapping:** `--some-option` → `SOME_OPTION` (uppercase, hyphens → underscores).

## Scene Sync & Activation

### `--enable-scene-sync`

*New in 0.12.0* — **Home Assistant support added in 0.13.0**

Creates synced scenes that always reflect the scheduled state of a light, room, or zone.

Note: In Home Assistant, dynamically created scenes can’t be assigned to areas, but they remain usable in automations. Hue Scheduler creates one scene **per group** and **per area** that a scheduled entity belongs to.

**Default:** `false`

### `--require-scene-activation`

*New in 0.13.0*

Applies scheduled states **only after** a synced scene has been activated. After activation, the current and subsequent states apply until the lights are turned off or manually modified. Use together with `--enable-scene-sync` when you want explicit, manual opt-in (e.g., via a smart switch or HA automation). If no synced scene has been activated since the last "off", no states are applied (except those with `force:true`).

Use `force:true` to override this behavior for specific states.

**Default:** `false`

### `--scene-sync-name`

*New in 0.12.0*

Sets the name of the synced scene (used with `--enable-scene-sync`).

**Default:** `HueScheduler`

### `--scene-activation-ignore-window`

Relevant only when user-modification tracking is **enabled** (i.e., `--disable-user-modification-tracking` is **not** set).

Delay **in seconds** after detecting a scene activation during which **turn-on events** for affected lights/groups are ignored. Prevents Hue Scheduler from immediately taking over after you turned lights on via a scene.

**Default:** `8` seconds

## Interpolation & Transitions

### `--interpolate-all`

Globally sets `interpolate:true` for all states unless a state explicitly uses `interpolate:false`. Useful when you want interpolation between most or all states.

**Default:** `false`

### `--default-interpolation-transition-time`

Sets the default transition time used for **interpolated calls** when a light is turned on during a `tr-before` window. Accepts either a multiple of 100 ms (e.g., `4`) or duration strings (e.g., `5s`, `1min`). If the **previous state** defines `tr`, that value is reused instead.

```yacas
# Uses the default:
Desk  06:00  bri:50%
Desk  07:00  bri:100%  tr-before:20min

# Reuses previous state's tr:
Desk  06:00  bri:50%  tr:10s
Desk  07:00  bri:100%  tr-before:20min

# Disables interpolation transitions:
Desk  06:00  bri:50%  tr:0
Desk  07:00  bri:100%  tr-before:20min
```

**Default:** `4` (= 400 ms)

### `--min-tr-before-gap`

Relevant only when user-modification tracking is **enabled**.

Minimum gap **in minutes** enforced between **back-to-back** states that use transitions. Without a gap, the Hue Bridge may not recognize the final target value of the first transition yet and could flag the light as "manually overridden." Hue Scheduler ensures this gap by shortening overlapping transitions as needed.

If overrides are still detected between adjacent transitioning states, increase this value.

**Default:** `3` minutes

## Manual Overrides & Sensitivity

### `--disable-user-modification-tracking`

Disables tracking of manual changes. By default, Hue Scheduler compares the previously seen state with the current state and only applies the scheduled state if the user hasn’t modified the light since then. To enforce a state regardless of user changes, use the per-state property `force:true`.

**Default:** `false`

### `--color-override-threshold`

*New in 0.13.0*

Color difference threshold (ΔE CIE76) above which a light’s color counts as **manually overridden**. Lower values catch smaller changes but may trigger during transitions; higher values ignore transition noise but might miss subtle tweaks.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `5–15`

**Default:** `6.0`

### `--brightness-override-threshold`

*New in 0.13.0*

Brightness difference threshold (percentage points) above which a light's brightness counts as **manually overridden**. Example: `10` means a change from `50%` → `60%` triggers detection.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `5–20`

**Default:** `10` (percentage points)

### `--ct-override-threshold`

*New in 0.13.0*

Color temperature difference threshold (**Kelvin**) above which a light's temperature counts as **manually overridden**. Example: `350` means `3000 K` → `3350 K` triggers detection.

Relevant only when user-modification tracking is **enabled**.

**Recommended range:** `100–500`

**Default:** `350` K

### `--color-sync-threshold`

*New in 0.14.0*

Color difference threshold (ΔE CIE76) above which a light’s color counts as **significantly changed** to schedule the next scene sync or background interpolation.

**Default:** `3.0`

### `--brightness-sync-threshold`

*New in 0.14.0*

Brightness difference threshold (percentage points) above which a light's brightness counts as **significantly changed** to schedule the next scene sync or background interpolation.

**Default:** `5` (percentage points)

### `--ct-sync-threshold`

*New in 0.14.0*

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

> As a general guideline we always recommend to our developers to stay at roughly 10 commands per second to the /lights resource with a 100ms gap between each API call. For /groups commands you should keep to a maximum of 1 per second.
>
> > -- [Hue System Performance — Philips Hue Developer Program (meethue.com)](https://developers.meethue.com/develop/application-design-guidance/hue-system-performance/) (requires login)

To keep the convenience of groups while improving performance, you can try the experimental `--control-group-lights-individually` option below.

**Default & recommended:** `10`

### `--max-concurrent-requests`

*New in 0.14.3*

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

## Security

### `--insecure`

*New in 0.12.2*

Disables SSL certificate validation for the Hue Bridge. Required if your bridge still uses a self-signed certificate instead of one issued by Signify. See [Philips Hue Developer Documentation](https://developers.meethue.com/develop/application-design-guidance/using-https/) (login required).

**Default:** `false`
