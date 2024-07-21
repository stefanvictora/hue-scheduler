# Light Configuration

Hue Scheduler uses a simple **text-based file format** for configuring your light schedules. Each line corresponds to one light configuration. Comments can start with either `#` or `//`, and empty lines are ignored.

Each line contains the following parts, separated either by a tab character or at least two spaces (recommended):

~~~yacas
<Light/Group Name or ID>  <Start Time Expression>  [<Property>:<Value>]*
~~~

## `<Light/Group Name or ID>`

The light or group (i.e., room or zone) to control, defined either through its name or ID. To simplify the configuration for multiple groups or lights, you can combine their names or IDs with a comma (`,`). The following Home Assistant entity types are currently supported: `light`, `input_boolean`, `switch`, `fan`.
         
**Philips Hue example:**
                        
~~~yacas
Kitchen, Living room, Desk lamp    civil_dusk  ct:2400

# Is equal to:
g1, g2, 1                          civil_dusk  ct:2400

# Is equal to:
g1                                 civil_dusk  ct:2400
g2                                 civil_dusk  ct:2400
1                                  civil_dusk  ct:2400
~~~

You can look up Philips Hue IDs by manually sending a GET request to either the `/api/<username>/lights` or `/api/<username>/groups` endpoint of your bridge. The response will contain a list of all your lights and groups in your system. For Hue Scheduler to distinguish between light and group IDs, group IDs must be prefixed with the lowercase letter `g`.

Note: If you have a group and light with the same name, Hue Scheduler prefers the group. Use IDs instead if you want to change this behavior.

**Home Assistant example:**

~~~yacas
Kitchen, Test Switch, TV Mute      civil_dusk  on:true

# Is equal to:
light.kitchen                      civil_dusk  on:true
input_boolean.test_switch          civil_dusk  on:true
switch.tv_mute                     civil_dusk  on:true
~~~

## `<Start Time Expression>`

Each state requires a start time, specified either as a fixed time in the 24-hour format (``HH:mm:ss``) or a dynamic solar time expression. The following dynamic **solar time constants** are available, in chronological order:

- ``astronomical_dawn``: e.g. `03:26`
- ``nautical_dawn``: e.g. `04:17`
- ``civil_dawn``: e.g. `05:00`
- ``sunrise``: e.g. `05:33`
- ``noon``: e.g. `12:00`
- ``golden_hour``: e.g. `19:24`
- ``sunset``: e.g. `20:11`
- ``blue_hour``: e.g. `20:29`
- ``civil_dusk``: e.g. `20:43`
- ``night_hour``: e.g. `20:57`
- ``nautical_dusk``: e.g. `21:28`
- ``astronomical_dusk``: e.g. `22:19`

These examples vary greatly depending on your current location and the current time of year. To get the current times for your location, start Hue Scheduler with an empty input file.

Additionally, you can **adjust the start time relative to these solar times** using the format:

~~~yacas
<sun_constant>[+-]<adjustment_in_minutes>
~~~

For example: ``sunset-30`` starts 30 minutes before sunset, and ``sunrise+60`` starts one hour after sunrise. These expressions are dynamically updated each day to reflect the current solar times at your location.

> Note: Further background info about the different solar times can be found at [Twilight - Wikipedia](https://en.wikipedia.org/wiki/Twilight) and [Twilight - commons-suncalc](https://shredzone.org/maven/commons-suncalc/usage.html#twilight).

#### FAQ: How Does Hue Scheduler Determine the End of States?

Hue Scheduler automatically sets the end time based on the start time of the next state for the same light.

Consider the following example:

  ~~~yacas
Hallway  07:00       bri:254
Hallway  civil_dusk  bri:150
  ~~~

This results in the following **dynamic intervals**, adjusted daily:

- **07:00–civil_dusk**: bri:254
- **civil_dusk–07:00**: bri:150

To **create gaps** in your schedule, define a state with no properties:

  ~~~yacas
Hallway  07:00  bri:254
Hallway  10:00
  ~~~

In this case, only the interval **07:00–10:00** is created. If you turn your lights outside this interval, Hue Scheduler does not enforce any state for the light.

## `[<Property>:<Value>]*`

Specify properties to control the state of your lights during the given interval.

### Basic Properties

- ``bri``: Modifies the **brightness** of the light: [``1``-``254``] or [``1%``-``100%``]. From dim to bright.

- ``ct``: Modifies the **color temperature** of the light, either in [Kelvin](https://en.wikipedia.org/wiki/Color_temperature) [``6500``-``2000``] or [Mired](https://en.wikipedia.org/wiki/Mired) [``153``-``500``], from cool to warm white. The supported range may vary for older light bulb models. Hue Scheduler checks at startup if the given color temperature is supported by the light, throwing an error if not.

- ``on``: Modifies the **on state** of the light: [``true``|``false``]. Hue Scheduler does not modify a light's on state unless explicitly specified. If a light is off or unreachable, Hue Scheduler waits until the light is turned on or reachable again.

  > Note: To smoothly turn on a light using a transition, specify another property like brightness or color temperature. Otherwise, the light will turn on immediately with the previous or default light state, ignoring the defined transition time. This is not an issue when turning the lights off.

- `days`: Defines the **days of the week** when the state is active. Supported values: [`Mo|Mon` | `Tu|Tue|Di` | `We|Wen|Mi` | `Th|Thu|Do` | `Fr|Fri` | `Sa|Sat` | `Su|Sun|So`]. Separate days with `,`, or use `-` for a range. For example: `days:Mo-We,Fr-Su`, `days:Sa-Tu` (shorthand for `Mo,Tu,Sa,Su`).

    ~~~yacas
    Office        sunrise     bri:254  ct:6500  tr:10s  days:Mo-Fr
    Office        sunset      bri:200  ct:3000  tr-before:20min  days:Mo-Fr

    Living room   22:00       bri:100   effect:prism  days:Fr,Sa
    Living room   23:59       days:Fr,Sa
    ~~~

### Color-Related Properties

Hue Scheduler offers several ways to define the color of supported lights:

- ``color``: Modifies the color of the light use **hex** (e.g. ``color:#3CD0E2``) or **RGB** (e.g. ``color:60, 208, 226``). Cannot be combined with other color properties. If no brightness (``bri``) is specified, Hue Scheduler calculates an appropriate brightness level for the given color.

- ``hue``: Defines the **hue** color value of the light: [``0``-``65535``]. The value "wraps" around, so both `0` and `65535` are red. Requires ``sat``.

- ``sat``: Defines the **saturation** color value of the light: [``0``-``254``] or [``0%``-``100%``], from white to fully colored. Requires ``hue``.

- ``effect``: Activates the given effect for the light or group. Can't be combined with other color properties or `ct`. The effect is active until the light is turned off or the effect set to `none`. This means you can adjust the brightness, while the effect remains active. The supported effects depend on the light model. Some supported values for Philips Hue color lights are `candle`, `fire`, `prism`, `sparkle`, `opal`, `glisten`.

- ``x`` and ``y``: (advanced) Modifies the **color** using x and y coordinates in the [CIE color space](https://en.wikipedia.org/wiki/CIE_1931_color_space): [``0.0``-``1.0``]. Useful for setting an exact color value obtained from the Hue API. For example: `x:0.1652  y=0.3103`. Cannot be combined with other color properties.

**Examples**:
~~~yacas
Desk  10:00  color:#3CD0E2
Desk  11:00  color:60, 208, 226
Desk  12:00  hue:2000  sat:100
Desk  13:00  effect:candle  bri:50%
Desk  14:00  effect:none
Desk  15:00  x:0.1652  y=0.3103
~~~

### Transition-Related Properties

> [!WARNING]
> Due to a [firmware bug](https://www.reddit.com/r/tradfri/comments/au903n/firmware_bugs_in_ikea_bulbs/) (see https://github.com/stefanvictora/hue-scheduler/issues/5),
> Ikea Tradfri light bulbs may not support setting multiple properties (e.g., `bri` and `ct`) with a transition time > 0.
> Since the Hue bridge applies a default transition time of 400ms (`tr:4`)
> if not specified otherwise, you have to explicitly set `tr:0` for Tradfri light bulbs, when setting multiple properties.
> Another workaround is to set only one property per state and offset the state changes accordingly.

Hue Scheduler offers various transition-related properties to create the desired transition and interpolation behavior:

- ``tr``: Defines the transition time when the start time of a state is reached [``0``-``60000``]. Default: `4` (400ms). The value is a multiple of 100ms. For example, `tr:1` equals 100ms. The maximum value ``60000`` equals 100 minutes.

  > Tip: Use `s` (seconds), `min` (minutes) and `h` (hours) units. Examples: `tr:10s`, `tr:2min`, `tr:1h`. Combinations are also possible (e.g., `1h20min5s10`).

- ``tr-before``: Defines the transition time *before* the start time. The additional transition type provided by Hue Scheduler. Realistically, the maximum value is 24 hours. Supports the same units but also absolute times, and dynamic solar times (see _Start Time Expression_):

  ~~~yacas
  Office  sunrise  on:true  bri:254  tr-before:30min
  Office  sunrise  on:true  bri:254  tr-before:06:00
  Office  sunrise  on:true  bri:254  tr-before:civil_dawn+5
  ~~~

  In the first example, the transition starts 30 minutes before sunrise, while in the last example, it starts 5 minutes after ``civil_dawn`` to smoothly turn on the light to full brightness until sunrise.

  > Note: The start time expression for ``tr-before`` must be before the defined state start, otherwise the property is ignored. Setting ``tr-before`` to more than 24 hours is not supported and leads to unexpected scheduling results.

  `tr-before` adjusts the transition time to the remaining duration if the light is turned on later. It calculates the mid-transition state based on the elapsed time, effectively interpolating from the previous state. For example:

  ~~~yacas
  Office  06:00  ct:400  tr:2s
  Office  09:00  ct:200  tr-before:30min  tr:10s
  ~~~

    1. If you turn on the lights at `08:30` (exact start of `tr-before`), or if they are already on, they will transition from the previous `ct:400` to the given `ct:200` over 30 minutes.

    2. If you turn on the lights at `08:45`, Hue Scheduler shortens the transition to the remaining 15 minutes and interpolates the ``ct`` value to `300`, making sure the lights always have the desired color temperature regardless when they are turned on during the transition.

    3. If you turn on the lights after `09:00`, `tr-before` is ignored and `tr` is used (default: 400ms if not specified).

  > Note: Hue Scheduler uses the previous state's ``tr`` property to determine the transition time for the interpolated calls. Set `tr:0` for the previous state to disable transitions for interpolated calls. If no ``tr`` property is defined, Hue Scheduler uses the default value defined via the ``--default-interpolation-transition-time`` (default: `4` = 400ms) global command line option.
  > If the previous and current states have incompatible to interpolate between, Hue Scheduler ignores any ``tr-before`` and ``interpolate:true`` properties.

  **To summarize**: `tr-before` allows longer, smoother transitions that match the desired state, regardless of when they are turned on. Interpolations are available for all color modes (CT, XY/RGB, Hue/Sat), and Hue Scheduler converts between them for smooth transitions (e.g., from color temperature to a color value).

- ``interpolate:true``: Extends ``tr-before`` by automatically starting transitions from the previous state:
  ~~~yacas
  # Instead of:
  Office  sunrise  bri:100
  Office  noon     bri:254  tr-before:sunrise
  Office  sunset   bri:50   tr-before:noon
  # You can write:
  Office  sunrise  bri:100
  Office  noon     bri:254  interpolate:true
  Office  sunset   bri:50   interpolate:true
  ~~~ 
  It also allows interpolations between states across days:
  ~~~yacas
  Office  sunrise  bri:100  interpolate:true
  Office  noon     bri:254  interpolate:true
  Office  sunset   bri:50   interpolate:true
  ~~~ 
  In this example, Hue Scheduler also interpolates between `sunset` and `sunrise`.

  > Tip: Enable ``interpolate:true`` for all states by using the ``--interpolate-all`` command line flag. Customize this behavior by explicitly setting ``interpolate:false`` for individual states or by defining a custom ``tr-before`` which takes precedence over the ``interpolate`` property.

### Advanced Properties

- `force`: Defines whether Hue Scheduler should **enforce the state despite user modifications**: [`true`|`false`]. This is only relevant if user modification tracking is not disabled (see `--disable-user-modification-tracking`). Default: `false`.

  ~~~yacas
  Office  09:00  bri:254  ct:6500
  Office  sunset bri:200  ct:3000  force:true
  ~~~

  In this example, the sunset state would always be set, even if the light has been manually adjusted since the morning.

  **Note**: The `force` property can also enforce the ``on:false`` state, which otherwise is not rescheduled when turning a light off and on again. Warning: This means such lights can't be turned on at all during these schedules, as they will immediately turned off again.
