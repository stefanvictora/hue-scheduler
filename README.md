<img align="right" width="160" height="160" src="https://raw.githubusercontent.com/stefanvictora/hue-scheduler/main/logo.png">

# Hue Scheduler

[![build](https://github.com/stefanvictora/hue-scheduler/actions/workflows/maven.yml/badge.svg)](https://github.com/stefanvictora/hue-scheduler)

> Hue Scheduler lets you improve your alertness during the day and unwind in the evening by automatically adjusting your Philips Hue lights according to the time of day, the position of the sun, and the day of week.

Compared to other approaches, such as Apple's Adaptive Lighting, Hue Scheduler lets you additionally control the brightness, color, and even the on-state of your lights, with smooth transitions between each state. Furthermore, Hue Scheduler was designed to work well with dumb wall switches that physically turn off your lights, as it will keep trying to set their preferred state until they are reachable again. However, unlike Adaptive Lighting, Hue Scheduler does not continuously update the color temperature of your lights throughout the day, but instead follows your defined schedule, giving you more flexibility and control over your lights' behavior.

## Demo

~~~yacas
# Energize ⚡
Office       sunrise  bri:254  ct:6500  tr:10s  days:Mo-Fr
# Cocentrate 💪
Office	     sunrise+90  ct:5000  tr-before:20min  days:Mo-Fr
# Wind Down 💤
Office       sunset  bri:200  ct:3000  tr-before:20min  days:Mo-Fr
# Garden night lights 🌆
Garden        civil_dusk  on:true  bri:254  tr:1min
Garden        01:00       on:false tr:5min
# Party 🎉
Living room  22:00  bri:100  sat:150  effect:multi_colorloop  days:Fr,Sa
Kitchen      22:00  color:#00835C  days:Fr,Sa
~~~

In this example, the lights in your (home) office are set to a bright and blue white at sunrise for an energetic start to your day. Ninety minutes after sunrise, the color temperature is set to a more neutral white for prolonged concentrated work. Finally, to help your body rest and relax, the light is dimmed slightly to a warmer temperature as the sun sets. Each time with a smooth 20 minute transition.

Note: Hue Scheduler does not automatically turn on your lights (unless explicitly told with ``on:true`` for each state), so you retain control over the state of your lights, while Hue Scheduler does all the heavy lifting of adjusting them to your defined schedule.

## Prerequisites

You need at least:

- Java 8
- A Philips Hue Bridge
- A computer or device (e.g. a Raspberry Pi) running continuously on your network

## Setup

In order for Hue Scheduler to control your Philips Hue lights, you must first authenticate it with your Philips Hue Bridge.

If you do not yet know your bridges IP address, you can discover it by navigating to [https://discovery.meethue.com](https://discovery.meethue.com/) and copying the returned IP address. For example:

~~~json
[{"id":"<id>","internalipaddress":"192.168.0.59"}]
~~~

Next, to authenticate a new user on your bridge, navigate to `http://<BRIDGE_IP_ADDRESS>/debug/clip.html` in your browser and enter the following in the fields:

~~~
URL:	/api
Body:	{"devicetype":"hue_scheduler#<name>"}
~~~

You can choose any value for ``<name>``, but make sure it is less than 26 characters long.

Now press the large physical button on your bridge and within 30 seconds press the ``POST`` button on the web form.

You should get your new username as a response. For example:

~~~json
[{"success":{"username": "83b7780291a6ceffbe0bd049104df"}}]
~~~

Copy and save that username for further use with Hue Scheduler. You only need to perform this authentication process once.

If you get the  error message ``"link button not pressed"``, try to repeat the process of pressing the button on your bridge and the ``POST`` button in the web interface within 30 seconds.

## Using Hue Scheduler

You can download the latest release of Hue Scheduler from GitHub [here](https://github.com/stefanvictora/hue-scheduler/releases/latest).

To run Hue Scheduler, the following arguments are required:

```shell
java -jar hue-scheduler.jar <ip> <username> --lat=<latitude> --long=<longitude> [--elevation=<elevation>] FILE
```

#### IP & Username

The IP address and username of your Philips Hue Bridge that you obtained during the setup process.

#### Latitude, Longitude & Elevation

Your approximate location and optional elevation so Hue Scheduler can calculate your local sunrise and sunset using the [shred/commons-suncalc](https://github.com/shred/commons-suncalc) library. The calculation is performed locally and no data is sent to the Internet.

#### FILE

The file path to your configured light schedules. The format is described in detail below.

## Configuration File

To configure your light schedules, Hue Scheduler uses a simple text based file format, where each line corresponds to one light configuration. Comment lines can start with either `#` or `//`. Empty lines are ignored.

Each line contains the following parts, separated either by a tab character or at least two spaces:

~~~yacas
<Light/Group Name or ID>    <Start Time Expression>    [<Property>:<Value>]*
~~~

- **Light/Group Name or ID**: The light or group (i.e. room or zone) to control, defined either through its name or ID.

  To distinguish between light and group IDs, group IDs must be prefixed with the lowercase letter `g`. To simplify the definition for multiple groups or lights, you can combine their names or IDs with a comma (`,`). For example:

  ~~~yacas
  Kitchen, Living room, Desk lamp    civil_dusk  ct:2400
  # Is equal to:
  g1, g2, 1                          civil_dusk  ct:2400
  ~~~

  Note: If you have a group and light with the same name, Hue Scheduler prefers the group. Use IDs instead if you want to change this behavior.

  To **improve your Hue system performance**, it might be beneficial to control your lights individually instead of using groups, as groups require broadcast messages that can impact overall Hue performance. You can tell Hue Scheduler to automatically control groups as individual lights by enabling the experimental *--control-group-lights-individually* command line option.

- **Start Time Expression**: Either a fixed start time in the 24 hour format ``HH:mm:ss``, or a dynamic sun time expression.

  Hue Scheduler automatically sets the end time for each state based on the start time of the following state for the same light. This simplifies the configuration file and prevents gaps in your schedule. For example, the definition:

  ~~~yacas
  Hallway  07:00       bri:254
  Hallway  civil_dusk  bri:150
  ~~~

  results in the following dynamic intervals, which are adjusted each day based on the sun times:

  - `07:00`--`civil_dusk`: bri:254
  - `civil_dusk`--`07:00`: bri:150

  In order to create gaps in your schedule, you can set the end time of a state manually by simply defining a state with no properties. For example:

  ~~~yacas
  Hallway  07:00  bri:254
  Hallway  10:00
  ~~~

  In this case only the interval `07:00`--`10:00` is created. If you turn your lights on after `10:00` or before `7:00`, Hue Scheduler would not enforce any sate for the light.

  The following dynamic **sun time constants** are available, sorted in their chronological order:

  - ``astronomical_dawn``: e.g. `03:26`
  - ``nautical_dawn``: e.g. `04:17`
  - ``civil_dawn``: e.g. `05:00`
  - ``sunrise``: e.g. `05:33`
  - ``golden_hour``: e.g. `19:24`
  - ``sunset``: e.g. `20:11`
  - ``blue_hour``: e.g. `20:29`
  - ``civil_dusk``: e.g. `20:43`
  - ``night_hour``: e.g. `20:57`
  - ``nautical_dusk``: e.g. `21:28`
  - ``astronomical_dusk``: e.g. `22:19`

  The given examples vary greatly depending on your current location and the current time of year. To get the current times for your location, you can simply start Hue Scheduler with an empty input file.

  Additionally, you can adjust the start time relative to these sun times by using the following format:

  ~~~yacas
  <sun_constant>[+-]<adjustment_in_minutes>
  ~~~

  For example: ``sunset-30`` adjusts the start time to 30 minutes before sunset, and ``sunrise+60`` to one hour after sunrise. These expressions are dynamically updated each day to reflect the current sun times at your location.

  Further details about the different sun times can be found at [Twilight - Wikipedia](https://en.wikipedia.org/wiki/Twilight) and [Twilight - commons-suncalc](https://shredzone.org/maven/commons-suncalc/usage.html#twilight). 

- **Properties**: An arbitrary number of properties to control the lights state at the given time.

  - ``bri``: modifies the **brightness** of the light: [``1``-``254``]. From dim to bright.

  - ``ct``: modifies the **color temperature** of the light. Either in [Kelvin](https://en.wikipedia.org/wiki/Color_temperature) [``6500``-``2000``] or [Mired](https://en.wikipedia.org/wiki/Mired) [``153``-``500``]. Each from cool to warm white.

    Note: The exact supported range may slightly vary for older light bulb models. Hue Scheduler checks at startup if the given color temperature is actually supported by the light, throwing an error if not.

  - ``on``: modifies the **on state** of the light: [``true``|``false``].

    As already mentioned: Hue Scheduler does not modify a lights on state, unless explicitly told. If a light is off or unreachable, Hue Scheduler retries to set the desired state until the light is turned on or reachable again.

    Note: If you would like to smoothly turn on a light using a transition, be sure to also specify another property like brightness or color temperature. For example:

    ~~~yacas
    Living room  civil_dawn  on:true  bri:254  ct:2000  tr:20min
    ~~~

    Or else, the light will simply turn on immediately with the previous or default light state, ignoring the defined transition time. This is not an issue when turning lights off.

  - `days`: defines the **days of week** on which the defined state should be active. Supported values: [`Mo|Mon` | `Tu|Tue|Di` | `We|Wen|Mi` | `Th|Thu|Do` | `Fr|Fri` | `Sa|Sat` | `Su|Sun|So`]. You can either separate the desired days by `,` or use `-` to define a range. Or use a combination of both. For example: `days:Mo-We,Fr-Su`. Hue Scheduler also supports ranges crossing over the end of the week, for example: `Sa-Tu`, which is a shorthand for `Mo,Tu,Sa,Su`.

  - `confirm`: defines whether Hue Scheduler should **send the request multiple times** to make sure the light is actually reachable: [`true`|`false`]. This is only relevant in combination with dumb wall switches that physically turn your lights off. For more details see "Why Does Hue Scheduler Confirm Requests?" Default: `true`.

  - **Color**-related properties:

    Hue Scheduler offers several ways to define the color of support lights.

    - ``color``: modifies the color of the light either through **hex** (e.g. ``color:#3CD0E2``) or **rgb** (e.g. ``color:60, 208, 226``). Cannot be combined with other color properties.

    - ``hue``: modifies the **hue** color value of the light: [``0``-``65535``]. The value "wraps" around, i.e. both `0` and `65535` are red. Related to ``sat``.

    - ``sat``: modifies the **saturation** color value of the light: [``0``-``254``]. From white to fully colored. Related to ``hue``.

    - ``effect``: makes the light **loop through all their hues** with their current ``sat`` and ``bri`` values. The lights loop until they are turned off, or they receive ``none`` as effect. This means you can adjust the saturation and brightness, while the lights keep looping. Supported values: [`colorloop`|`multi_colorloop`|`none`].

      The `multi_colorloop` is only applicable to groups, as Hue Scheduler adjusts each light in the group to have a different starting hue value during the loop. This is achieved by turning each light in the group on and off, while waiting a set number of seconds in between. The exact wait time can be adjusted through the optional `--multi-color-adjustment-delay` command line argument, which results in different hue offsets between the lights.

    - ``x`` and ``y``: (advanced) modifies the **color** directly based on the x and y coordinates of a color in the **[CIE color space](https://en.wikipedia.org/wiki/CIE_1931_color_space)**: [``0.0``-``1.0``]. This is useful if you want to set the color to the exact value you got from the Hue API after setting the color manually via an app. For example: `x:0.1652  y=0.3103` Cannot be combined with other color properties.

  - **Transition time**-related properties:

    The transition time between two light states, defined as a multiple of 100ms: [``0``-``65535``]. For example: `tr:1` equals a transition time of 100ms. The maximum support value ``65535`` equals roughly 1 hour and 48 min. To simplify the definition, you can use the ``s`` (seconds) and ``min`` (minutes) units. For example: ``tr:10s`` or ``tr:2min``.

    Hue Scheduler offers two different transition time properties, which can be combined to create the desired transition behavior:

    - ``tr``: defines the transition time used at or *after* the defined start time. Default: `4` (400ms).

    - ``tr-before``: defines the transition time used *before* the defined start time. For example:

      ~~~yacas
      Office  sunrise  on:true  bri:254  tr-before:10min
      ~~~

      Here the transition starts 10 minutes before sunrise to smoothly turn on the light to full brightness until the sun has risen.

      What makes `tr-before` especially useful is that Hue Scheduler automatically adjusts the transition to the remaining time, if the light is turned on at a later point before the start. Consider for example:

      ~~~yacas
      Office  09:00  ct:6500  tr-before:30min  tr:10s
      ~~~

      1. If you turn on the lights at `08:30`, or if they are already on at that time, the lights will smoothly transition to the given color temperature over the course of 30 minutes.

      2. If you turn on the lights at `08:45`, Hue Scheduler automatically shortens the transition time to the remaining 15 minutes till start. Making sure the desired color temperature is reached on time.

      3. If you turn on the lights at any time after `09:00`, Hue Scheduler ignores `tr-before` and instead uses the transition time defined via ``tr`` instead.

    To summarize: `tr-before` allows you to define longer and smoother transitions when the lights are already on, without having to wait the same time if you would turn on your lights at a later time.

## Raspberry Pi Quick Start

To run Hue Scheduler automatically on your Raspberry Pi during startup, you can configure a **systemd service**, as described in the official [Raspberry Pi Documentation](https://www.raspberrypi.org/documentation/linux/usage/systemd.md).

The main steps are: 

1. Installing Java and Hue Scheduler

    ~~~shell
    # Verify your installed Java version
    java -version

    # If needed, install or update you Java version
    sudo apt update && sudo apt install default-jdk

    # Download the latest Hue Scheduler release to /opt/hue-scheduler
    wget https://github.com/stefanvictora/hue-scheduler/releases/latest/download/hue-scheduler.jar -O /opt/hue-scheduler/hue-scheduler.jar
    ~~~

1. Copy your Hue Scheduler configuration file to `/etc/hue-scheduler/input.txt`.

2. Create a new systemd service file `hue-scheduler.service` at `/etc/systemd/system` with the following contents (make sure to replace all placeholders like `<IP>` or `<USERNAME>` first):

    ~~~sh
    [Unit]
    Description=Hue Scheduler
    After=network-online.target
    
    [Service]
    Type=simple
    WorkingDirectory=/etc/hue-scheduler/
    ExecStart=/usr/bin/java -jar /opt/hue-scheduler/hue-scheduler.jar <IP> <USERNAME> --lat <LAT> --long <LONG> --elevation <ELEVATION> input.txt
    
    [Install]
    WantedBy=multi-user.target
    ~~~

    Note: If you would like to create the file directly from the command line, you can use the following shortcut:

    ~~~shell
    sudo systemctl edit --force hue-scheduler
    ~~~

    This will create the file in the right place and at the same time open an editor where you can paste your service configuration. Make sure to save the changes with `Strg+O` + `Enter`, and exit the editor again with `Strg+X`.

4. Configure the new service:

    ~~~shell
    # Reload configuration
    sudo systemctl daemon-reload
    
    # Start Hue Scheduler
    sudo systemctl start hue-scheduler
    
    # Start Hue Scheduler on boot
    sudo systemctl enable hue-scheduler
    ~~~

5. Finally, verify that the service is up and running:

   ~~~bash
   $ sudo systemctl status hue-scheduler
   ● hue-scheduler.service - Hue Scheduler
      Loaded: loaded (/etc/systemd/system/hue-scheduler.service; enabled; vendor preset: enabled)
      Active: active (running) ...
   ~~~

### Other helpful systemd commands

~~~shell
# Check the logs of Hue Scheduler
sudo journalctl -u hue-scheduler -e

# Restart Hue Scheduler, after modifying its configuration file (/etc/hue-scheduler/input.txt)
sudo systemctl restart hue-scheduler

# Stop Hue Scheduler from running on boo
sudo systemctl disable hue-scheduler
~~~

## Advanced Command Line Options

#### --retry-delay

The maximum number of seconds to wait before trying again to control a light that was unreachable. To distribute the retries for multiple unreachable lights, Hue Scheduler chooses a random value between ``1`` and the given seconds on each retry attempt.

**Default**: `5` seconds

#### --max-requests-per-second

The maximum number of PUT API requests Hue Scheduler is allowed to perform per second. The official Hue API Documentation recommends keeping this at 10 requests per second, or else the bridge might drop some requests.

Note: The bridge controls groups by using more computationally expensive broadcast messages, which is why the official recommendation is to limit group updates to one per second. Hue Scheduler automatically rate limits lights and groups updates accordingly.

> As a general guideline we always recommend to our developers to stay at roughly 10 commands per second to the /lights resource with a 100ms gap between each API call. For /groups commands you should keep to a maximum of 1 per second.
>
> > -- [Hue System Performance - Philips Hue Developer Program (meethue.com)](https://developers.meethue.com/develop/application-design-guidance/hue-system-performance/) (requires login)

To still benefit from the ease of use of groups, while improving overall system performance, you can use the experimental *--control-group-lights-individually* Option, as described below.

**Default and recommended**: `10` requests per second

### --control-group-lights-individually

*Experimental*

Toggle if Hue Scheduler should control lights found in a group individually instead of using broadcast messages. This might improve performance.

Note: At the moment, Hue Scheduler does not validate in such cases if all the lights inside the group support the given command. Furthermore, this option might not be suitable for groups with mixed capabilities, i.e., setting color for a group that also contains a color temperature only light. In such cases the unsupported light is not updated.

**Default**: false

#### --confirm-all

If Hue Scheduler should confirm all state changes, by globally setting the default value for the `confirm` state property. If you don't use dumb wall switches, you should set this to `false`. See "Why Does Hue Scheduler Confirm Requests?" for more details.

**Default**: `true`

#### --confirm-count

The number of confirmations to send.

**Default**: `20` confirmations

#### --confirm-delay

The delay in seconds between each confirmation.

**Default**: `6` seconds

#### --multi-color-adjustment-delay

The adjustment delay in seconds for each light in a group when using the multi_color effect. Adjust this parameter to change the hue values of 'neighboring' lights.

**Default**: `4` seconds

#### --bridge-failure-retry-delay

The delay in seconds for retrying an API call, if the bridge could not be reached due to network failure, or if it returned an API error code.

**Default**: `10` seconds

#### -Dlog.level

A JVM argument to configure the log level of Hue Scheduler. The following values are available:

- `ERROR`: Only logs if the API returned with an error code. This should most likely never occur.
- `WARN`: Additionally logs if the bridge is not reachable, and Hue Scheduler retries
- `INFO`: Logs when a light state has been set, as well as the current sun times for each day.
- `DEBUG` (default): Logs every scheduled state; if a state has already ended; or if it has been successfully confirmed.
- `TRACE`: Maximum logs, including all performed API requests and enforced wait times due to rate limiting.

Note: The JVM argument needs to be defined before the jar file. For example:

~~~bash
java -Dlog.level=TRACE -jar hue-scheduler.jar ...
~~~

## Developing

To build Hue Scheduler from its source, simply run:

~~~shell
git clone https://github.com/stefanvictora/hue-scheduler.git
cd hue-scheduler
mvnw clean install
~~~

This creates the runnable jar `target\hue-scheduler.jar`, as well as an unbundled version in `target\hue-scheduler`. Both are equivalent in functionality. Choose the format you like.

## FAQ

### Does Hue Scheduler Access The Internet?

No, Hue Scheduler does not access the Internet. It only communicates with your local Philips Hue Bridge. You can see exactly which REST HTTP requests Hue Scheduler sends to your local bridge by setting the `-Dlog.level=TRACE` JVM parameter. See "Advanced Command Line Options".

The dynamic sun times are also calculated locally using the [shred/commons-suncalc](https://github.com/shred/commons-suncalc) library, with no data ever leaving your device.

### How Can I Lookup The IDs Of My Lights?

If you don't want to use light or group names in your configuration file, you can lookup their respective IDs by sending a GET request to either the `/api/<username>/lights` or `/api/<username>/groups` endpoint of your Philips Hue Bridge. Either by navigating to `http://<BRIDGE_IP_ADDRESS>/debug/clip.html` in your browser, which conveniently formats the returned JSON automatically, or by simply accessing the URL directly, i.e. `http://<BRIDGE_IP_ADDRESS>/api/<username>/lights`.

### Why Does Hue Scheduler Confirm Requests?

In short, to fully support dumb wall switches that physically turn off your lights. In such cases the bridge might take up to two minutes to update a lights reachable status. If you would now turn off a light around the time a state change is scheduled, the update will be missed by the light because the bridge will report the light as still reachable, and therefore Hue Scheduler will not retry to set the state.

If you don't use dumb wall switches, or would like to set the confirmation behavior manually for each affected lights, you can configure the default behavior via the `--confirm-*` command line arguments and the `confirm` state property.

### Does Hue Scheduler Work With Motion Sensors?

Yes, but you need to use a third-party app like iConnectHue to configure your motion sensor to only adjust the brightness of your lights, not the color or color temperature. Otherwise, it would override these properties every time the sensor is activated.

### How Does Hue Scheduler Check If A Group Is Reachable?

Since the Hue API does not return the reachability state for a group, Hue Scheduler simply checks the first light of a group for its reachability state and uses that result for the whole group. This might result in false results and is a current limitation of Hue Scheduler.

##  Links

**Similar projects**:

- Kelvin - The hue bot: https://github.com/stefanwichmann/kelvin
- Adaptive Lighting custom component for Home Assistant: https://github.com/basnijholt/adaptive-lighting

## Roadmap

- [ ] **Conditional states** -- set light state only if it has not been manually changed since its previously scheduled state
- [ ] **Enforce states** -- ensure that a state is always set; allow no manual override
- [ ] **Min/Max for sunrise/sunset** -- ensure that a dynamic start time is not active before or past a certain time
- [ ] **GUI for configuring and updating light schedules** -- via a web interface

## License

```
Copyright 2021 Stefan Victora

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

