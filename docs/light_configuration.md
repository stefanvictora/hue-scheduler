# Light Configuration

Hue Scheduler uses a simple **text-based** format. Each non-empty line is one schedule entry. Lines starting with `#` or `//` are comments; empty lines are ignored.

Each line has three parts separated by a tab **or** at least two spaces (recommended):

```yacas
<Light/Group Name or ID>  <Start Time Expression>  [<Property>:<Value>]*
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

## `<Start Time Expression>`

Each state has a start time, specified either as a fixed time (24-hour `HH:mm[:ss]`, e.g., `06:00`, `23:30:15`) or a **dynamic solar time**. Available solar constants, in chronological order:

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

You can also **offset** solar times:

```yacas
<sun_constant>[+-]<minutes>
```

Examples: `sunset-30` (30 minutes before sunset), `sunrise+60` (one hour after sunrise). Offsets update daily with the sun.

> Note: Background on twilight terms: [Twilight - Wikipedia](https://en.wikipedia.org/wiki/Twilight) and [Twilight - commons-suncalc](https://shredzone.org/maven/commons-suncalc/usage.html#twilight).

### FAQ: How is the end of a state determined?

Hue Scheduler ends a state at the **start time of the next state for the same target**.

Example:

  ```yacas
Hallway  07:00       bri:254
Hallway  civil_dusk  bri:150
  ```

This results in two **dynamic intervals** (adjusted daily):

- **07:00 → civil_dusk**: `bri:254`
- **civil_dusk → 07:00**: `bri:150`

To **create gaps**, define a state with no properties:

  ```yacas
Hallway  07:00  bri:254
Hallway  10:00
  ```

Only **07:00–10:00** is scheduled. If the light is turned on outside this window, Hue Scheduler does **not** enforce any state.

## `[<Property>:<Value>]*`

Properties define the state applied during the interval.

### Basic

- `bri` — **brightness** (`1–254` or `1%–100%`), from dim to bright.
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

- `color` — **hex** (e.g., `#3CD0E2`) or **RGB** (e.g., `60,208,226`). Cannot be combined with other color properties. If `bri` is omitted, Hue Scheduler derives a suitable brightness for the color.
- `hue` — **hue** (`0–65535`). Wraps around (`0` and `65535` are both red). **Requires** `sat`.
- `sat` — **saturation** (`0–254` or `0%–100%`), white → fully colored. **Requires** `hue`.
- `effect` — Activates a light/group effect. Cannot be combined with other color properties or `ct`. The effect persists until the light is turned off or `effect:none`. Brightness can still be adjusted. Supported effects vary by model. Examples (Hue color lights): `candle`, `fire`, `prism`, `sparkle`, `opal`, `glisten`.
- `x` / `y` — **[CIE xy](https://en.wikipedia.org/wiki/CIE_1931_color_space)** coordinates (`0.0–1.0`). Useful for exact colors read from the Hue API. Cannot be combined with other color properties.

Examples:
```yacas
Desk  10:00  color:#3CD0E2
Desk  11:00  color:60, 208, 226
Desk  12:00  hue:2000  sat:100
Desk  13:00  effect:candle  bri:50%
Desk  14:00  effect:none
Desk  15:00  x:0.1652  y:0.3103
```

### Transitions & Interpolations

> [!WARNING]
> Due to a known [firmware bug](https://www.reddit.com/r/tradfri/comments/au903n/firmware_bugs_in_ikea_bulbs/) (see https://github.com/stefanvictora/hue-scheduler/issues/5) with some **Ikea Tradfri**, setting **multiple properties** (e.g., `bri` + `ct`) with a **non-zero transition** may fail. Because the Hue Bridge applies a default 400 ms transition (`tr:4`) if none is given, explicitly set `tr:0` for Tradfri bulbs when changing multiple properties. Alternatively, change one property per state and offset the times.

- `tr` — **transition duration** *at* the state's start time. Base unit is 100 ms; default is `4` (= 400 ms). Max `60000` (= 100 min).

  > Tip: Units are supported and can be combined, e.g., `tr:10s`, `tr:2min`, `tr:1h20min5s`.

- `tr-before` — **pre-transition** that starts **before** the state's start time (Hue Scheduler feature). Practically capped at 24 h. Supports relative durations, absolute times, and solar times:

  ```yacas
  Office  sunrise  on:true  bri:254  tr-before:30min
  Office  sunrise  on:true  bri:254  tr-before:06:00
  Office  sunrise  on:true  bri:254  tr-before:civil_dawn+5
  ```

  In the first example, the transition starts 30 minutes before sunrise, while in the last example, it starts 5 minutes after ``civil_dawn`` to smoothly turn on the light to full brightness until sunrise.

  > Note: The `tr-before` reference must be **earlier** than the state's start, otherwise it's ignored. Values > 24 h are unsupported and produce undefined schedules.

    **Late turn-on behavior:** If lights are turned on after a `tr-before` has already started, Hue Scheduler shortens the remaining fade and **interpolates** from the previous state:

  ```yacas
  Office  06:00  ct:400  tr:2s
  Office  09:00  ct:200  tr-before:30min  tr:10s
  ```

    1. At **08:30**, the full 30-min fade runs from `ct:400` → `ct:200`.
    2. At **08:45**, the remaining 15 min run, starting near `ct:300`.
    3. After **09:00**, `tr-before` is ignored; `tr` (10 s, or the default) applies.

  > Note: Hue Scheduler uses the **previous state's** `tr` for the per-step interpolation calls. Set the previous state's `tr:0` to disable those transitions. If no `tr` is defined, the global default `--default-interpolation-transition-time` is used (default: `4` = 400 ms). If interpolation between two states is **not possible**, `tr-before` and `interpolate:true` are ignored.

- `interpolate:true` — Start transitions **automatically** from the previous state (a shorthand for common `tr-before` patterns):

  ```yacas
  # Instead of:
  Office  sunrise  bri:100
  Office  noon     bri:254  tr-before:sunrise
  Office  sunset   bri:50   tr-before:noon
  # You can write:
  Office  sunrise  bri:100
  Office  noon     bri:254  interpolate:true
  Office  sunset   bri:50   interpolate:true
  ```
   
    Interpolations also **span days**:
 
  ```yacas
  Office  sunrise  bri:100  interpolate:true
  Office  noon     bri:254  interpolate:true
  Office  sunset   bri:50   interpolate:true
  ```
   
    In this example, Hue Scheduler also interpolates from `sunset` → next day's `sunrise`.

  > Tip: Enable interpolation globally with `--interpolate-all`, then override per state using `interpolate:false` or a custom `tr-before` (which takes precedence).

### Advanced

- `force:true` — **enforce** the state even if the user manually changed the light since the last scheduled state (`true|false`, default `false`). Relevant only if user-modification tracking is enabled (default).

  ```yacas
  Office  09:00  bri:254  ct:6500
  Office  sunset bri:200  ct:3000  force:true
  ```

    Here, the sunset state is always applied—even if the user changed the light during the day.

    **Note**: `force:true` can also enforce `on:false`. In that case, lights **cannot** be turned on during the interval (they'll be turned off immediately).

    With `--require-scene-activation`, `force:true` still applies the state even if a synced scene wasn't activated.

