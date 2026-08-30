# Light Configuration

Hue Scheduler uses a simple **text-based** format. Each non-empty line is one schedule entry. Lines starting with `#` or `//` are comments; empty lines are ignored.

Each line has three parts separated by a tab **or** at least two spaces (recommended):

```yacas
<Light/Group Name or ID>  <Scheduled Time>  [<Property>:<Value>]*
```

## `<Light/Group Name or ID>`

The light or group (room or zone) to control, given by name or ID. You can target multiple items by separating names/IDs with commas (`,`). Supported Home Assistant entity types: `light`, `input_boolean`, `switch`, `fan`.
         
**Philips Hue example:**
                        
```yacas
Kitchen, Living room, Desk lamp    civil_dusk  ct:2400

# Is equal to:
g1, g2, 1                          civil_dusk  ct:2400

# Is equal to:
g1                                 civil_dusk  ct:2400
g2                                 civil_dusk  ct:2400
1                                  civil_dusk  ct:2400
```

You can look up Hue IDs by sending `GET /api/<username>/lights` or `GET /api/<username>/groups` to your bridge. The response lists all lights and groups. To distinguish IDs, **prefix group IDs with `g`** (lowercase).

Note: If a group and a light share the same name, Hue Scheduler prefers the **group**. Use IDs to target the light explicitly.

**Home Assistant example:**

```yacas
Kitchen, Test Switch, TV Mute      civil_dusk  on:true

# Is equal to:
light.kitchen                      civil_dusk  on:true
input_boolean.test_switch          civil_dusk  on:true
switch.tv_mute                     civil_dusk  on:true
```

## `<Scheduled Time>`

The second column sets the entry's **scheduled time**. Normally, that is when its values begin to apply. With `tr-before` or `interpolate:true`, it is instead when the values should be fully reached—the change begins earlier.

The scheduled time can be a fixed time (24-hour `HH:mm[:ss]`, e.g., `06:00`, `23:30:15`) or a **dynamic solar time**. Available solar constants, in chronological order:

- `astronomical_dawn` (e.g., `03:26`)
- `nautical_dawn` (e.g., `04:17`)
- `civil_dawn` (e.g., `05:00`)
- `sunrise` (e.g., `05:33`)
- `noon` (e.g., `12:00`)
- `golden_hour` (e.g., `19:24`)
- `sunset` (e.g., `20:11`)
- `blue_hour` (e.g., `20:29`)
- `civil_dusk` (e.g., `20:43`)
- `night_hour` (e.g., `20:57`)
- `nautical_dusk` (e.g., `21:28`)
- `astronomical_dusk` (e.g., `22:19`)

These times vary by location and date. To see your current values, start Hue Scheduler with an empty input file.

> Note: Background on twilight terms: [Twilight - Wikipedia](https://en.wikipedia.org/wiki/Twilight) and [Twilight - commons-suncalc](https://shredzone.org/maven/commons-suncalc/usage.html#twilight).

You can also **offset** solar times:

```yacas
<sun_constant>[+-]<minutes>
```

Examples: `sunset-30` (30 minutes before sunset), `sunrise+60` (one hour after sunrise). Offsets update daily with the sun.

### Constraint Functions

You can wrap any scheduled time in a **constraint function** to bound dynamic solar times to fixed limits. This is useful when sunrise or sunset varies too much across seasons.

Available functions:

| Function                 | Args | Returns                                                                                                                          |
|--------------------------|------|----------------------------------------------------------------------------------------------------------------------------------|
| `notBefore(expr, limit)` | 2    | The **later** of `expr` and `limit` (ensures the scheduled time is not before `limit`). E.g. `notBefore(sunrise, 06:30)`         |
| `notAfter(expr, limit)`  | 2    | The **earlier** of `expr` and `limit` (ensures the scheduled time is not after `limit`). E.g. `notAfter(sunset+30, 21:00)`       |
| `clamp(expr, min, max)`  | 3    | `expr` bounded to `[min, max]`; if `min > max`, logs a warning and returns `expr` unchanged. E.g. `clamp(sunrise, 06:30, 08:00)` |
| `max(a, b)`              | 2    | Alias for `notBefore` — returns the later of two times                                                                           |
| `min(a, b)`              | 2    | Alias for `notAfter` — returns the earlier of two times                                                                          |
| `mix(a, b, w)`           | 3    | **Experimental**: Places the time between `a` and `b` using weight `w` (`0..1` or `%`). E.g. `mix(sunrise, 07:30, 35%)`          |
| `smooth(expr, halfLife)` | 2    | **Experimental**: Smooths `expr` by averaging it over past days. E.g. `smooth(sunrise, 14d)`                                     |

Each argument can be a fixed time (`HH:mm[:ss]`), a solar keyword with optional offset, or another nested function call.

Function names are **case-insensitive**. Whitespace inside arguments is trimmed.

Further examples:

```
# Ensure lights don't turn on before 06:30 even in summer when sunrise is early
Kitchen  notBefore(sunrise, 06:30)  bri:100%

# Cap sunset-based scheduling to no later than 21:00
Porch  notAfter(sunset+30, 21:00)  bri:80%

# Keep sunrise between 06:30 and 08:00 year-round
Office  clamp(sunrise, 06:30, 08:00)  bri:100%

# Equivalent using min/max aliases
Office  min(max(sunrise, 06:30), 08:00)  bri:100%

# Nested functions
Hallway  notAfter(notBefore(sunrise, 06:30), 08:00)  bri:100%

# Experimental: blend sunrise with a fixed anchor to reduce seasonal swings
Kitchen  mix(sunrise, 07:30, 0.35)  bri:40%

# Blend two solar times directly
Living room  mix(golden_hour, sunset, 0.5)  bri:45%

# Morning routine: smooth + bounded
Bedroom  clamp(mix(sunrise, 07:30, 0.35), 06:30, 08:00)  bri:30%

# Evening routine: follow sunset, but dampened and bounded
Living room  clamp(mix(sunset+30, 22:30, 0.5), 19:00, 23:00)  bri:45%

# Pure-solar smoothing (no fixed anchor), then practical bounds
Bedroom  clamp(smooth(sunrise, 14d), 06:30, 08:00)  bri:30%
```

#### Experimental: `mix(...)` — blend two time expressions

`mix(a, b, w)` places the scheduled time between two time expressions `a` and `b`. The weight `w` controls how close the result is to `b`:

- `w = 0` → exactly `a`
- `w = 1` → exactly `b`
- `w = 0.5` → midpoint between `a` and `b`

A common use is blending a solar time with a fixed clock time: `mix(sunrise, 07:30, 0.35)` takes sunrise but pulls it 35% toward `07:30` — so the schedule still moves with the seasons, but much more gently. The result is always between the two endpoints. You can also blend two solar times directly: `mix(golden_hour, sunset, 0.5)`.

**Why use it:** If you like solar-based schedules but want them to behave more like a stable routine (e.g., "around 07:30, but still season-aware"), `mix` **narrows the seasonal range**. However, since both values are recalculated fresh each day, day-to-day jumps are not smoothed out.

#### Experimental: `smooth(...)` — keep it fully solar, but slow down seasonal swings

`smooth(expr, halfLife)` keeps the schedule 100% solar-based, but smooths out rapid seasonal changes by averaging the solar time over past days — more recent days count more, older days fade out.

- `halfLife` is in days (e.g., `14d`, `14`) and controls how "inert" the time is. With `halfLife = 14d`, the value from ~14 days ago still contributes about half as much as today's; older days fade out quickly
- Larger half-life → smoother, slower movement; smaller → more responsive

**Why use it:** If sunrise/sunset schedules feel like they shift too quickly in spring and autumn, `smooth` addresses that directly — without introducing a fixed routine time. The schedule still tracks the seasons, just more gradually. Over time, it will still reach the full seasonal extreme. To add hard limits, wrap in `clamp`: `clamp(smooth(sunrise, 14d), 06:30, 08:00)`.

#### Choosing between `mix` and `smooth`

|                                   | `mix(a, b, w)`                           | `smooth(expr, halfLife)`                 |
|-----------------------------------|------------------------------------------|------------------------------------------|
| **How it works**                  | Blends two expressions evaluated *today* | Averages one expression over past days   |
| **Requires a second expression?** | Yes (`b`)                                | No                                       |
| **Reduces seasonal range?**       | Yes — pulls toward `b`                   | No — eventually reaches the true extreme |
| **Reduces day-to-day jumps?**     | No — recalculated fresh each day         | Yes — changes gradually                  |

**Rule of thumb:** Use `mix` to narrow the seasonal range; use `smooth` to slow day-to-day changes. Combine them — or add `clamp` — for maximum control.

### FAQ: How long does a schedule entry apply?

An entry normally applies until the next entry for the same light or group takes over. Without an early fade, that happens at the next entry's scheduled time. With `tr-before` or `interpolate:true`, the next entry takes over when its early fade begins.

Example:

  ```yacas
Hallway  07:00       bri:254
Hallway  civil_dusk  bri:150
  ```

This results in two periods that adjust daily:

- **07:00 → civil_dusk**: `bri:254`
- **civil_dusk → 07:00**: `bri:150`

To deliberately leave part of the day unscheduled, add an entry with no properties:

  ```yacas
Hallway  07:00  bri:254
Hallway  10:00
  ```

Only **07:00–10:00** is scheduled. If the light is turned on outside this window, Hue Scheduler does not apply scheduled values.

## `[<Property>:<Value>]*`

Properties describe the values and behavior for a schedule entry.

### Basic

- `bri` — **brightness** (`1–254` or `1%–100%`), from dim to bright. When used with `scene:`, values above `100%` proportionally boost brightness (individual lights capped at `254`).
- `ct` — **color temperature** in **[Kelvin](https://en.wikipedia.org/wiki/Color_temperature)** (`6500–1000`) or **[Mired](https://en.wikipedia.org/wiki/Mired)** (`153–500`), cool → warm. Ranges can vary by bulb model. At startup, Hue Scheduler validates and clamps unsupported values. Note: Only color-capable lights support Kelvin values below 2000 K.

- `on` — **power state** (`true|false`). Hue Scheduler does not change power unless `on:` is specified. If a light is off or unreachable, it waits until the light becomes reachable.

  > Note: To *smoothly* turn a light **on**, include another property (e.g., `bri` or `ct`) with a transition. Otherwise, turning on uses the previous/default state immediately (transition ignored). This does **not** apply when turning lights **off**.

- `days` — **days of week**. Supported aliases:
    - `Mo|Mon`, `Tu|Tue|Di`, `We|Wed|Mi`, `Th|Thu|Do`, `Fr|Fri`, `Sa|Sat`, `Su|Sun|So`

      Separate with `,` or use a range with `-`. Ranges **wrap** across the week.

    - Examples: `days:Mo-We,Fr-Su`, `days:Sa-Tu` (i.e., `Sa,Su,Mo,Tu`)

    ```yacas
    Office        sunrise     bri:254  ct:6500  tr:10s  days:Mo-Fr
    Office        sunset      bri:200  ct:3000  tr-before:20min  days:Mo-Fr

    Living room   22:00       bri:100   effect:prism  days:Fr,Sa
    Living room   23:59       days:Fr,Sa
    ```

### Color

Hue Scheduler supports several ways to set color:

- `color` — **hex** (e.g., `#3CD0E2`), **RGB** (e.g., `rgb(60 208 226)`), **XY** (e.g., `xy(0.6024 0.3433)`), or **OKLCH** (e.g., `oklch(0.7 0.15 180)`). Cannot be combined with other color properties. If `bri` is omitted, Hue Scheduler derives a suitable brightness for the color.

  **OKLCH syntax:** `oklch(L C h)` where **L** is lightness (`0.0–1.0` or percentage, e.g., `50%`), **C** is chroma (≥ 0), and **h** is hue in degrees. Angle units `deg`, `grad`, `rad`, `turn` are supported. Brightness is derived from the L component when `bri` is not explicitly set.

- `effect` — Activates a light effect. The effect persists until the light is turned off or `effect:none`. Brightness can still be adjusted. Supported effects vary by model. Examples (Hue color lights): `candle`, `fire`, `prism`, `sparkle`, `opal`, `glisten`.
  
  **Speed parameter:** Append `@<speed>` to control effect speed, where speed is `0.0–1.0` (e.g., `effect:candle@0.5`, `effect:fire@1.0`).
  
  **Parameterized effects:** Effects can be combined with `color`, `ct`, or `x`/`y` to set the effect's color parameter. When an effect is active, these color properties become parameters of the effect rather than direct light state properties. For example, `effect:candle  ct:350` creates a candle effect with a warm color temperature, and `effect:opal  color:#FF5500` sets the effect's color. With `effect:none`, color properties behave as regular light state properties.

- `gradient` — Multi-color gradient for compatible lights. Syntax: `gradient:[<color>, <color>, ...]` with 2–5 color points. Colors can be in any supported format: `#hex`, `rgb(r g b)`, `xy(x y)`, `oklch(L C h)`. Cannot be combined with other color properties or `effect`.
  
  **Mode suffix:** Optionally append `@<mode>` (e.g., `gradient:[#FF0000, #0000FF]@interpolated_palette`). Available modes depend on the device. Current known values: `interpolated_palette`, `interpolated_palette_mirrored`, `random_pixelated`, `segmented_palette`.
  
  **Auto-fill:** When exactly 2 color points are provided, intermediate points are automatically generated using perceptual OKLab interpolation up to the device's maximum gradient point count (typically 5), creating smoother gradients.

  > Note: The Hue bridge currently supports gradients only for individual lights and not groups.

- `x` / `y` — **[CIE xy](https://en.wikipedia.org/wiki/CIE_1931_color_space)** coordinates (`0.0–1.0`). Useful for exact colors read from the Hue API. Cannot be combined with other color properties. Deprecated, use `color:xy(x y)` instead.

Examples:
```
Desk  10:00  color:#3CD0E2
Desk  11:00  color:rgb(60 208 226)
Desk  11:30  color:xy(0.1652 0.3103)
Desk  12:00  color:oklch(0.7 0.15 180)
Desk  13:00  effect:candle  bri:50%
Desk  13:30  effect:fire@0.1825  bri:40%
Desk  13:45  effect:candle  ct:350            # candle with warm color temperature
Desk  14:00  effect:opal  color:#FF5500       # opal with custom color
Desk  15:00  effect:none
Desk  16:00  gradient:[#FF0000, #0000FF]
Desk  17:00  gradient:[oklch(0.7 0.2 30), #00FF00, oklch(0.5 0.15 270)]@random_pixelated
```

### Scene Scheduling

*New in 0.15.0. Hue bridge only.*

- `scene` — **Load per-light states** from an existing Hue scene and schedule them for a group. Each light retains its individual brightness, color temperature, color, effect, and gradient settings from the scene.

  > **Note**: Hue Scheduler listens for scene changes and automatically reloads the updated per-light values. If that scene is currently scheduled and the group is on, the updated values are applied immediately.

  ```
  Living room  sunset  scene:Relax
  Living room  22:00   scene:Nightlight   bri:50%   interpolate:true
  ```

  **Proportional brightness scaling:** When `bri` is specified alongside `scene:`, each light's brightness is scaled proportionally. For example, `bri:50%` dims all lights to half their scene-defined brightness. Values above `100%` proportionally boost brightness — useful for making a scene brighter than its original definition. Individual lights are capped at their maximum.

  ```
  Living room  sunset   scene:Relax  bri:50%   # dim to half
  Living room  22:00    scene:Relax  bri:200%  # boost to double (capped per light)
  ```

  **Power control:** Use `on:true` with `scene:` to ensure all scene lights are turned on. `on:false` cannot be combined with `scene:`.

  **Allowed combinations:** Only `on`, `bri`, `tr`, `tr-before`, `days`, `force`, and `interpolate` can be used alongside `scene:`. Color properties (`ct`, `color`, `x`/`y`, `effect`, `gradient`) cannot be combined with `scene:`, since they are defined by the scene itself.

  **Parameterized effects:** Scenes that use Hue API v2 effects (e.g., `candle`, `fire`) fully preserve their effect parameters, including color temperature, color, and speed.

  **Early changes:** Scene entries support both `tr-before` and `interpolate:true`, including gradual changes between two scenes or from regular group values to a scene.

### Transitions & Interpolations

The scheduled time is the reference point for every change. The important difference is whether the change begins before or at that time:

| Property           | The change begins                                               | The configured values are reached     |
|--------------------|-----------------------------------------------------------------|---------------------------------------|
| `tr`               | At the scheduled time, or whenever the entry is later reapplied | After the given duration              |
| `tr-before`        | At the configured earlier time                                  | At the scheduled time                 |
| `interpolate:true` | At the previous entry's scheduled time                          | At the current entry's scheduled time |

Without an explicit transition option, the entry is applied at its scheduled time and the Hue Bridge normally uses its default 400 ms transition.

> [!WARNING]
> Due to a known [firmware bug](https://www.reddit.com/r/tradfri/comments/au903n/firmware_bugs_in_ikea_bulbs/) (see https://github.com/stefanvictora/hue-scheduler/issues/5) with some **Ikea Tradfri** bulbs, changing **multiple properties** (e.g., `bri` + `ct`) with a **non-zero transition** may fail. The Hue Bridge uses a default 400 ms transition when `tr` is omitted, so set `tr:0` when changing multiple properties—or split the changes into separate entries.

#### `tr` — change after the scheduled time

`tr` sets how long the light takes to reach the entry's values after the entry is applied. Its base unit is 100 ms; the default is `4` (= 400 ms), and the maximum is `60000` (= 100 min).

If the light is off at the scheduled time and turns on later, the entry is applied then and uses the same transition again.

Units can be combined for readability, for example `tr:10s`, `tr:2min`, or `tr:1h20min5s`.

#### `tr-before` — finish at the scheduled time

`tr-before` starts the change early so the entry's values are reached at its scheduled time. It supports a relative duration, an absolute time, or a solar time:

```yacas
Office  sunrise  on:true  bri:254  tr-before:30min
Office  sunrise  on:true  bri:254  tr-before:06:00
Office  sunrise  on:true  bri:254  tr-before:civil_dawn+5
```

The first entry starts changing 30 minutes before sunrise. The last starts 5 minutes after `civil_dawn`. Both finish at sunrise.

The `tr-before` time must be earlier than the scheduled time; otherwise it is ignored. Durations longer than 24 hours are unsupported and may produce unexpected schedules.

**When a light turns on partway through:** Hue Scheduler calculates where the light should be at that moment, catches it up to those values, and then continues for the remaining time.

```yacas
Office  06:00  ct:400  tr:2s
Office  09:00  ct:200  tr-before:30min  tr:10s
```

1. At **08:30**, the full 30-minute change runs from `ct:400` to `ct:200`.
2. If the light turns on at **08:45**, it first catches up to about `ct:300`, then continues to `ct:200` over the remaining 15 minutes.
3. At or after **09:00**, the early change is over. The second entry's normal `tr:10s` transition applies instead.

The initial catch-up uses the previous entry's `tr`—`2s` in this example. If the previous entry has no `tr`, `--default-interpolation-transition-time` is used (400 ms by default). Setting the previous entry's `tr:0` makes the catch-up immediate. This short catch-up transition does not change when the final values are due.

If the two entries have no property that can change gradually between them, or an empty entry creates a gap between them, the early change is skipped.

#### `interpolate:true` — start at the previous scheduled time

`interpolate:true` automatically starts the change at the previous entry's scheduled time and reaches the current entry's values at its scheduled time. It is a convenient alternative to specifying that earlier time with `tr-before`:

```yacas
# Explicit start times:
Office  sunrise  bri:100
Office  noon     bri:254  tr-before:sunrise
Office  sunset   bri:50   tr-before:noon

# Equivalent automatic interpolation:
Office  sunrise  bri:100
Office  noon     bri:254  interpolate:true
Office  sunset   bri:50   interpolate:true
```

Interpolation works only when consecutive entries share at least one property that can change gradually and the desired values differ. An empty entry between them prevents interpolation.

Interpolation can also span days:

```yacas
Office  sunrise  bri:100  interpolate:true
Office  noon     bri:254  interpolate:true
Office  sunset   bri:50   interpolate:true
```

In this example, Hue Scheduler also interpolates from `sunset` to the next day's `sunrise`.

Enable interpolation globally with `--interpolate-all`, then override individual entries with `interpolate:false`. A custom `tr-before` takes precedence and sets the earlier start explicitly.

**Fading to or from off:** When an entry has `on:false`, interpolation treats it as brightness 0. Other properties, such as `ct`, continue changing normally alongside the brightness.

```yacas
Office  06:00  bri:100  ct:4000
Office  22:00  on:false  interpolate:true
```

Here, the light dims from brightness 100 to 0 between 06:00 and 22:00, then turns off at 22:00.

### Advanced

- `force:true` — Apply this entry even after the user manually changes the light (`true|false`, default `false`). This matters only while user-modification tracking is enabled (the default).

  ```yacas
  Office  09:00  bri:254  ct:6500
  Office  sunset bri:200  ct:3000  force:true
  ```

    Here, the sunset entry is always applied—even if the user changed the light during the day.

    **Note**: `force:true` can also enforce `on:false`. In that case, the light cannot remain on while this entry applies; it will be turned off again immediately.

    From 0.14.0, setting `force:true` with `on:true` also forces the light to be **always on**.

    With `--require-scene-activation`, `force:true` still applies the entry even if a synced scene was not activated.
