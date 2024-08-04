<img align="right" width="155" height="155" src="https://raw.githubusercontent.com/stefanvictora/hue-scheduler/main/logo.png">

# Hue Scheduler

[![build](https://github.com/stefanvictora/hue-scheduler/actions/workflows/maven.yml/badge.svg)](https://github.com/stefanvictora/hue-scheduler)
[![GitHub Downloads](https://img.shields.io/github/downloads/stefanvictora/hue-scheduler/total?logo=github&color=%235f87c4)](https://github.com/stefanvictora/hue-scheduler/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/stefanvictora/hue-scheduler?logo=docker&color=%235f87c4)](https://hub.docker.com/r/stefanvictora/hue-scheduler)

> Boost your daytime focus and unwind at night with Hue Scheduler. Easily fine-tune your Philips Hue or Home Assistant lights based on the time, sun's position, and the day of the week.

## Introduction

**New in Version 0.10.0**: **Full support for the Home Assistant REST API** :partying_face: Control even more devices in your home with Hue Scheduler.

Hue Scheduler goes beyond tools like Adaptive Lighting by providing extended control over brightness, color temperature, color, power state, and custom interpolations between solar and absolute times. Specifically designed to work with dumb wall switches, it adjusts light states as soon as they're reachable, ensuring consistent results even when lights have been physically turned off.

## Demo

Hue Scheduler allows you to configure your smart lights with a simple, text-based configuration file. Below, you can see various examples demonstrating how to create daily routines, dynamic interpolations, power management schedules, and ambiance settings.
                     
~~~
# Living Room
light.living_room  sunrise      bri:80%    ct:6000         tr:10s
light.living_room  sunrise+60   bri:100%   ct:5000         interpolate:true
light.living_room  sunset       bri:60%    ct:3000         tr-before:golden_hour-20
light.living_room  23:00        bri:40%    color:#FE275D   tr-before:1h

# Porch Light: Control power state
Porch Light  civil_dusk   on:true    bri:100%   tr:1min
Porch Light  23:00        on:false              tr:5min

# Motion Sensor: Inactive at night on weekdays
switch.sensor_hallway_activated   08:00   on:true
switch.sensor_hallway_activated   22:00   on:false   days:Mo-Fr
~~~

> [!TIP]
> Hue Scheduler does not automatically turn on your lights (unless explicitly specified with `on:true`), allowing you to maintain control while it handles all the adjustments once they are turned on.

> [!NOTE]
> Any manual changes to your lights will temporarily suspend the schedule until they are turned off and on again.
> If lights are turned on midway through a transition, Hue Scheduler calculates the appropriate mid-transition state to continue the transition seamlessly.

## How It Works

Each configuration line has three parts:

~~~yacas
<Light/Group Name or ID>  <Start Time Expression>  [<Property>:<Value>]*
~~~

**Light/Group Name or ID:**
- Define which light or group to control. Use names or IDs (e.g. `Couch` or `light.couch`). Multiple lights can be combined with a comma (`,`). The following Home Assistant entity types are currently supported: `light`, `input_boolean`, `switch`, `fan`.

**Start Time Expression:** 
- Set either fixed times in 24-hour format (HH:mm:ss) (e.g. `06:00`, `23:30:15`) or solar times (e.g., `sunrise`, `sunset`). Adjust times relative to solar events in minutes (e.g., `sunset-30`). The following dynamic solar time constants are available, in chronological order: `astronomical_dawn`, `nautical_dawn`, `civil_dawn`, `sunrise`, `noon`, `golden_hour`, `sunset`, `blue_hour`, `civil_dusk`, `night_hour`, `nautical_dusk`, `astronomical_dusk`.

**Properties:**

- **Basic**:
    - **`bri`** (brightness): e.g., `bri:100%`
    - **`ct`** (color temperature) [``6500``-``2000``|``153``-``500``]: e.g., `ct:6500`, `ct:153`
    - **`on`** (on/off state): e.g., `on:true`
    - **`days`** (specific days of the week): e.g., `days:Mo-Fr`, `days:Tu,We`
- **Color**:
    - **`color`** (hex or rgb): e.g., `color:#3CD0E2`, `color:60, 208, 226`
    - **`hue`** (color value) [``0``-``65535``]: e.g., `hue:2000`
    - **`sat`** (saturation) [``0``-``254``] or [``0%``-``100%``]: e.g., `sat:150`, `sat:70%`
    - **`effect`**: e.g., `effect:prism`, `effect:fire`, `effect:none`
- **Advanced**:
    - **`x`** and **`y`** (CIE color space coordinates): e.g., `x:0.6024  y:0.3433`
    - **`force`** (ignore user modifications): e.g., `force:true`
- **Transitions**:
    - **`tr`**: Transition time when a state starts. e.g., `tr:10s`, `tr:1h5min`
    - **`tr-before`**: Transition time before a state starts, allowing for smooth transitions. e.g., `tr-before:30min`, `tr-before:06:00`, `tr-before:civil_dawn+5`
    - **`interpolate`**: Automatic transitions from the previous state. e.g., `interpolate:true`

> [!TIP]
> Visit the [full documentation](docs/light_configuration.md) for more detailed information on how to configure your light schedules.

## Quick Start Guide

You can run Hue Scheduler in two ways: using the official Docker image or by manually downloading and running the Java application. The configuration differs slightly for each method, as detailed below.

### Prerequisites

To run Hue Scheduler, you will need the following:

- An up-to-date Philips Hue Bridge or Home Assistant instance.
- A computer or device (e.g., a Raspberry Pi) running continuously on your network.
- Docker or Java 21 installed on your device.

### Via Docker
       
To get started with Docker, follow these steps:

1. **Create a `docker-compose.yml` template:**

   ~~~yaml
   services:
     hue-scheduler:
       container_name: hue-scheduler
       image: stefanvictora/hue-scheduler:0.11
       environment:
         - API_HOST=
         - ACCESS_TOKEN=
         - LAT=
         - LONG=
         - ELEVATION=
         - TZ=
         - CONFIG_FILE=/config/input.txt # do not edit
       volumes:
         - type: bind
           source: # <- Insert your config file path
           target: /config/input.txt
           read_only: true
       restart: unless-stopped
   ~~~
   You can find a filled-out docker-compose example [here](docs/docker_examples.md).

2. **Provide the required parameters:**
    - `API_HOST`: IP address or host of your Philips Hue bridge or Home Assistant instance, e.g., `192.168.0.157`, `http://ha.local:8123`, or `https://UNIQUE_ID.ui.nabu.casa` (untested)
    - `ACCESS_TOKEN`: A [Philips Hue bridge username](https://github.com/stefanvictora/hue-scheduler/blob/main/docs/philips_hue_authentication.md) or [Home Assistant access token](https://www.home-assistant.io/docs/authentication/).
    - `LAT`, `LONG` & `ELEVATION`: Location details to calculate local solar times.
    - `TZ`: Your time zone.
    - `SOURCE`: Path to the [configuration file](docs/light_configuration.md) containing the light schedules.
    
    For additional configuration options, see the [list of advanced command line options](docs/advanced_command_line_options.md).
  
3. **Run Docker Compose commands:**

   ~~~shell
   # Create and run container:
   docker compose up -d
   
   # Stop and remove container:
   docker compose down
   ~~~

If your Raspberry Pi does not yet have Docker installed, check out this [short guide](docs/docker_on_raspberrypi.md).

### Manually

To run Hue Scheduler manually, follow these steps:

1. **Download the latest release** from GitHub [here](https://github.com/stefanvictora/hue-scheduler/releases/latest).
2. **Run the application** using the following command. Replace the placeholders enclosed in `<>` with your actual values:
   ~~~shell
   java -jar hue-scheduler.jar <API_HOST> <ACCESS_TOKEN> --lat=<LATITUDE> --long=<LONGITUDE> --elevation=<ELEVATION> <CONFIG_FILE_PATH>
   ~~~

## FAQ

### Why is there a short delay when physically turning lights on and the scheduler taking over?

This is a known limitation of the Hue Bridge. There is typically a delay of around 3–4 seconds until physically turned on lights are detected as available again. In contrast, lights turned on via smart switches or an app are detected almost instantly.

Another limitation with dumb wall switches is that the Hue Bridge can take up to ~2 minutes to detect lights being physically turned off, and therefore won't detect fast off and on switches. This means if you want to reset manual overrides with dumb wall switches, you have to wait at least 2 minutes before turning the lights back on. 

### How does Hue Scheduler compare to Adaptive Lighting?

Both Adaptive Lighting and Hue Scheduler aim to automate the state of your lights based on the time of day. However, there are key differences:

- **Control Over Light Properties**: Adaptive Lighting primarily adjusts the color temperature and brightness of your lights. In contrast, Hue Scheduler offers extended control, allowing you to also set specific colors and manage the power state of your lights.

- **Flexibility and Customization**: Adaptive Lighting operates with a continuous adjustment mechanism, offering limited manual intervention. Hue Scheduler, on the other hand, provides a more hands-on approach, empowering you with the flexibility to define your own schedule. You can even introduce multiple custom interpolations, all through a single, user-friendly configuration file.

### Does Hue Scheduler access the Internet?

Hue Scheduler does not access the Internet unless you explicitly connect to a cloud-hosted Home Assistant instance. You can see exactly which REST requests Hue Scheduler sends to your devices by setting the `-Dlog.level=TRACE` JVM parameter or `log.level=TRACE` environment variable. See [Advanced Command Line Options](docs/advanced_command_line_options.md). The dynamic solar times are calculated locally using the [shred/commons-suncalc](https://github.com/shred/commons-suncalc) library, with no location data ever leaving your device.

### Does Hue Scheduler work with motion sensors?

Yes, but you should probably use a third-party app like iConnectHue to configure your motion sensor to only adjust the brightness of your lights, not the color or color temperature. Otherwise, the color or color temperature of your lights would switch between the sensor set values and the ones scheduled by Hue Scheduler, causing some flicker every time the sensor is activated.

## Roadmap

- [x] **Detect manual overrides** -- set light state only if it has not been manually changed since its previously scheduled state
- [x] **Enforce states** -- ensure that a state is always set; allow no manual override
- [x] **Interpolate between states** -- support more advanced interpolations between states when using `tr-before`
- [x] **Advanced state interpolations** -- easily create full-day state interpolations without explicitly using `tr-before`
- [x] **Docker support** -- provide a prebuilt docker images for easier setup
- [x] **Home Assistant API support** -- allow controlling lights via the Home Assistant API
- [ ] **Conditional states** -- set light state only if the given conditions are satisfied
- [ ] **Date-based scheduling** -- schedule state only during a specific date range
- [ ] **Support for gradients** -- support setting gradients to supported lights
- [ ] **Support for scenes** -- support scheduling scenes for groups
- [ ] **Support for Hue APIv2 effects** -- support for more effects
- [ ] **Min/Max for sunrise/sunset** -- ensure that a dynamic start time is not active before or past a certain time
- [ ] **GUI for configuring and updating light schedules** -- via a web interface
- [ ] **Home Assistant Addon support** -- package Hue Scheduler as an easy to install Home Assistant addon

## Developing

To build Hue Scheduler from source, run:

~~~shell
# Clone the repository:
git clone https://github.com/stefanvictora/hue-scheduler.git
# Navigate to project root:
cd hue-scheduler
# Build using maven
mvnw clean install
~~~

This process creates the runnable JAR file `target/hue-scheduler.jar` containing all dependencies.

### Docker Image

Alternatively, you can use the provided Dockerfile to build and run a new Docker image of Hue Scheduler. Replace `<VERSION>` with the desired version number:

~~~shell
docker build -t stefanvictora/hue-scheduler:<VERSION> -f Dockerfile .
~~~

The usage of the image is shown above in the _Getting Started_ section. Other useful Docker commands for development include:

~~~shell
# Rebuild and Run:
docker-compose up -d --build

# Remove container on exit:
docker run --rm -e "log.level=TRACE" --name hue-scheduler ...
~~~

##  Similar Projects

- [Kelvin — The hue bot](https://github.com/stefanwichmann/kelvin): A tool that helps manage your Philips Hue lights with a focus on automating color temperature and brightness based on the time of day.
- [Adaptive Lighting](https://github.com/basnijholt/adaptive-lighting): A custom component for Home Assistant that adjusts the color temperature and brightness of your lights throughout the day.

## License

```
Copyright 2021-2024 Stefan Victora

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

