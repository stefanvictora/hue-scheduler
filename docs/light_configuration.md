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

> Note: Background on twilight terms: [Twilight - Wikipedia](https://en.wikipedia.org/wiki/Twilight) and [Twilight - commons-suncalc](https://shredzone.org/maven/commons-suncalc/usage.html#twilight).

You can also **offset** solar times:

```yacas
<sun_constant>[+-]<minutes>
```

Examples: `sunset-30` (30 minutes before sunset), `sunrise+60` (one hour after sunrise). Offsets update daily with the sun.

### Constraint Functions

You can wrap any start time expression in a **constraint function** to bound dynamic solar times to fixed limits. This is useful when sunrise or sunset varies too much across seasons.

Available functions:

| Function                 | Args | Returns                                                                                     |
|--------------------------|------|---------------------------------------------------------------------------------------------|
| `notBefore(expr, limit)` | 2    | The **later** of `expr` and `limit` (ensures start is not before `limit`)                   |
| `notAfter(expr, limit)`  | 2    | The **earlier** of `expr` and `limit` (ensures start is not after `limit`)                  |
| `clamp(expr, min, max)`  | 3    | `expr` bounded to `[min, max]`; if `min > max`, logs a warning and returns `expr` unchanged |
| `max(a, b)`              | 2    | Alias for `notBefore` — returns the later of two times                                      |
| `min(a, b)`              | 2    | Alias for `notAfter` — returns the earlier of two times                                     |
| `mix(a, b, w)`           | 3    | **Experimental**: weighted blend. `mix(a,b,w)=a*w+b*(1-w)`, with `w` in `0..1` or `%`       |

Each argument can be a fixed time (`HH:mm[:ss]`), a solar keyword, a solar keyword with offset, or another nested function call.

Function names are **case-insensitive** (`notBefore`, `NotBefore`, `NOTBEFORE` all work). Whitespace inside arguments is trimmed.

#### Experimental: `mix(...)` for smoother seasonal changes

`mix(...)` helps reduce day-to-day schedule swings by blending two time expressions. Both `a` and `b` can be fixed times, solar times, solar times with offsets, or nested function expressions.

- `w = 1` → fully `a` (e.g. pure `sunrise`)
- `w = 0` → fully `b` (e.g. a fixed time, or another solar time such as `sunset`)
- lower `w` means less seasonal movement

In practice, many setups feel best with **mix first, clamp second**:

```yacas
clamp(mix(sunrise, 07:30, 0.35), 06:30, 08:00)
```

This keeps the schedule smooth around spring/autumn while still enforcing hard boundaries.

<details>
<summary>Examples</summary>

~~~
# Ensure lights don't turn on before 06:30 even in summer when sunrise is early
Kitchen  notBefore(sunrise, 06:30)  bri:254  ct:6500

# Cap sunset-based scheduling to no later than 21:00
Porch  notAfter(sunset+30, 21:00)  bri:200

# Keep sunrise between 06:30 and 08:00 year-round
Office  clamp(sunrise, 06:30, 08:00)  bri:254  ct:6000

# Equivalent using min/max aliases
Office  min(max(sunrise, 06:30), 08:00)  bri:254  ct:6000

# Nested functions
Hallway  notAfter(notBefore(sunrise, 06:30), 08:00)  bri:254

# Combine with offsets
Bedroom  clamp(sunrise-15, 06:30, 08:00)  bri:200  ct:3000

# Experimental: blend sunrise with a fixed anchor to reduce seasonal swings
Kitchen  mix(sunrise, 07:30, 0.35)  bri:40%

# Same as above, but with percent weight
Kitchen  mix(sunrise, 07:30, 35%)  bri:40%

# Blend two solar times directly
Living room  mix(golden_hour, sunset, 0.5)  bri:45%

# Real-world morning routine: smooth + bounded
Bedroom  clamp(mix(sunrise, 07:30, 0.35), 06:30, 08:00)  bri:30%  ct:3200

# Real-world evening routine: follow sunset, but dampened and bounded
Living room  clamp(mix(sunset+30, 22:30, 0.5), 19:00, 23:00)  bri:45%  ct:2600
~~~

</details>

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

  > **Note**: Hue Scheduler listens for scene modifications and automatically reloads the updated light states, re-applying and re-syncing the currently active state.

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

  **Interpolation:** Scenes support full interpolation (via `interpolate:true` or `tr-before`), including transitions between two different scenes or from a regular group state to a scene.

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

  In the first example, the transition starts 30 minutes before sunrise, while in the last example, it starts 5 minutes after `civil_dawn` to smoothly turn on the light to full brightness until sunrise.

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

- `interpolate:true` — Start transitions **automatically** from the start of the previous state (a shorthand for common `tr-before` patterns). Continuously transition between two defined states—effectively simulating what's often called *natural*, *adaptive* or *circadian lighting*:

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

  **`on:false` as interpolation target:** When the target state has `on:false`, interpolation treats it as brightness 0, producing a smooth fade-to-off transition. The same applies when `on:false` is the source: lights fade up from brightness 0. Additional properties like `ct` are interpolated normally alongside the brightness fade.

  ```yacas
  Office  06:00  bri:100  ct:4000
  Office  22:00  on:false  interpolate:true
  ```

  Here, lights smoothly dim from brightness 100 to 0 between 06:00 and 22:00, turning off when the target state is reached.

### Advanced

- `force:true` — **enforce** the state even if the user manually changed the light since the last scheduled state (`true|false`, default `false`). Relevant only if user-modification tracking is enabled (default).

  ```yacas
  Office  09:00  bri:254  ct:6500
  Office  sunset bri:200  ct:3000  force:true
  ```

    Here, the sunset state is always applied—even if the user changed the light during the day.

    **Note**: `force:true` can also enforce `on:false`. In that case, lights **cannot** be turned on during the interval (they'll be turned off immediately).

    From 0.14.0, setting `force:true` with `on:true` also forces the light to be **always on**.

    With `--require-scene-activation`, `force:true` still applies the state even if a synced scene wasn't activated.
