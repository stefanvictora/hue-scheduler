<img align="right" width="155" height="155" src="https://raw.githubusercontent.com/stefanvictora/hue-scheduler/main/logo.png">

# Hue Scheduler

[![build](https://github.com/stefanvictora/hue-scheduler/actions/workflows/maven.yml/badge.svg)](https://github.com/stefanvictora/hue-scheduler)
[![GitHub Downloads](https://img.shields.io/github/downloads/stefanvictora/hue-scheduler/total?logo=github&color=%235f87c4)](https://github.com/stefanvictora/hue-scheduler/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/stefanvictora/hue-scheduler?logo=docker&color=%235f87c4)](https://hub.docker.com/r/stefanvictora/hue-scheduler)

> Boost your daytime focus and unwind at night with Hue Scheduler. Easily fine-tune your Philips Hue or Home Assistant lights based on the time, sun's position, and the day of the week.

## Introduction

**New in Version 0.10.0**: **Full support for the Home Assistant REST API** :partying_face: Control even more devices in your home with Hue Scheduler.

Hue Scheduler goes beyond tools like Adaptive Lighting by providing extended control over brightness, color temperature, color, power state, and custom interpolations between solar and absolute times. Specifically designed to work with dumb wall switches, Hue Scheduler adjusts light states as soon as they're reachable, ensuring consistent results even when lights have been physically turned off.

## Demo

Hue Scheduler allows you to configure your smart lights with a simple, text-based configuration file. Below, you can see various examples demonstrating how to create daily routines, dynamic interpolations, power management schedules, and ambiance settings.
                                   
### Example Configurations

#### Daily Routines: Sun-Based Brightness & Color Temperature
~~~yacas
Office  sunrise     bri:100%  ct:6500  tr:5s                     
Office  sunrise+90            ct:5000  tr-before:20min           
Office  sunset      bri:80%   ct:3000  tr-before:20min
~~~

In this example, the lights in your office adjust dynamically based on the sun's position, each with a smooth transition:

- **Sunrise**: Lights turn to a bright, blue-white (6500K) for an energetic start.
- **90 Minutes After Sunrise**: Transition to a more neutral white (5000K).
- **Sunset**: Lights dim slightly and warm up to a cozier tone (3000K).

Note: Hue Scheduler does not automatically turn on your lights (unless explicitly specified with `on:true`), allowing you to maintain control while Hue Scheduler handles all the adjustments once they are turned on.

#### Dynamic Lighting: Advanced Transitions & Interpolations
    
~~~yacas
Home  sunrise     bri:100%  ct:6500             tr-before:civil_dawn
Home  noon        bri:100%  ct:5000             interpolate:true
Home  sunset      bri:80%   ct:3000             tr-before:golden_hour+10
Home  civil_dusk  bri:40%   ct:2000             interpolate:true
Home  00:00       bri:20%   x:0.6024  y:0.3433  interpolate:true
~~~

This configuration creates smooth and continuous transitions throughout the day:

- **Civil Dawn to Sunrise**: Lights transition to full brightness (100%) with a blue-white tone (6500K).
- **Sunrise to Noon**: Maintain brightness but continuously shift to a neutral white (5000K).
- **Golden Hour to Sunset**: Slightly reduce brightness and mimic golden hour (3000K).
- **Sunset to Civil Dusk**: Gradually dim and warm up (2000K).
- **Civil Dusk to Midnight**: Dim to a low brightness (20%) with a typ of red defined by x and y coordinates.

> [!NOTE]
> Any manual changes to your lights will temporarily suspend the schedule until they are turned off and on again.
> If lights are turned on midway through a transition, Hue Scheduler calculates the appropriate mid-transition state to continue the transition seamlessly. 

#### Power Management: Manage On/Off States on Specific Weekdays

~~~yacas
light.garden  civil_dusk  on:true   bri:100%  tr:1min     days:Mo-Fr
light.garden  01:00       on:false            tr:5min30s  days:Mo-Fr
~~~

This setup manages power states based on time and weekdays:

- **Civil Dusk (Monday to Friday)**: Turn garden lights on at full brightness (100%) with a 1-minute transition.
- **1:00 AM (Monday to Friday)**: Turn garden lights off with a smooth 5.5-minute transition.

#### Mood and Ambiance: Effects & Color

~~~yacas
Living room  22:00  bri:100%  sat:150  effect:multi_colorloop  days:Fr,Sa
Kitchen      22:00  color:#00835C                              days:Sa
~~~

Enhance mood and ambiance with color effects:

- **Living Room**: At 22:00, set brightness to 100%, saturation to 150, and enable a multicolor loop effect.
- **Kitchen**: At 22:00, change the color to a specific shade of green (#00835C).

### How It Works

Hue Scheduler uses a simple text-based configuration format to define the behavior of your lights. Here’s a summary of the three key parts:

~~~yacas
<Light/Group Name or ID>  <Start Time Expression>  [<Property>:<Value>]*
~~~

**Light/Group Name or ID**: Define which light or group to control. Use names or IDs (e.g. `Couch` or `light.couch`). Multiple lights can be combined with a comma (`,`).

**Start Time Expression**: Set either fixed times in 24-hour format (HH:mm:ss) (e.g. **`06:00`**, **`23:30:15`**) or solar times (e.g., **`sunrise`**, **`sunset`**). Adjust times relative to solar events in minutes (e.g., **`sunset-30`**).

**Properties**:

- **Basic**:
    - **`bri`** (brightness): e.g., **`bri:100%`**
    - **`ct`** (color temperature): e.g., **`ct:6500`**, **`ct:153`**
    - **`on`** (on/off state): e.g., **`on:true`**
    - **`days`** (specific days of the week): e.g., **`days:Mo-Fr`**, **`days:Tu,We`** 
- **Color**:
    - **`color`** (hex or rgb): e.g., **`color:#3CD0E2`**, **``color:60, 208, 226``**
    - **`hue`** (color value): e.g., **`hue:2000`**
    - **`sat`** (saturation): e.g., **`sat:150`**, **`sat:70%`** 
    - **`effect`** (color loop): e.g., **`effect:multi_colorloop`**, **`effect:colorloop`**, **`effect:none`**
- **Advanced**:
    - **`x`** and **`y`** (CIE color space coordinates): e.g., **`x:0.6024 y:0.3433`**
    - **`force`** (ignore user modifications): e.g., **`force:true`**
- **Transitions**:
  - **`tr`**: Transition time when a state starts. e.g., **`tr:10s`**
  - **`tr-before`**: Transition time before a state starts, allowing for smooth transitions. e.g., **`tr-before:30min`**, **`tr-before:06:00`**, **`tr-before:civil_dawn+5`**, 
  - **`interpolate`**: Automatic transitions from the previous state. e.g., **`interpolate:true`**

> [!TIP]
> Visit the [full documentation](docs/light_configuration.md) for more detailed information on how to configure your light schedules.

## Prerequisites

To run Hue Scheduler, you will need the following:

- An up-to-date Philips Hue Bridge or Home Assistant instance.
- A computer or device (e.g., a Raspberry Pi) running continuously on your network.
- Docker or Java 21 installed on your device.

## Quick Start Guide

You can run Hue Scheduler in two ways: using the official Docker image or by manually downloading and running the Java application. The configuration differs slightly for each method, as detailed below.

### Via Docker
       
To get started with Docker, follow these steps:

1. **Create a `docker-compose.yml` file** with the following content. Replace the placeholder values (`#<>`) with your actual information (see _Configuration_):

   ~~~yaml
   services:
     hue-scheduler:
       container_name: hue-scheduler
       image: stefanvictora/hue-scheduler:0.10
       environment:
         - API_HOST= #<HOST>
         - ACCESS_TOKEN= #<TOKEN>
         - LAT= #<LATITUDE>
         - LONG= #<LONGITUDE>
         - ELEVATION= #<ELEVATION>
         - CONFIG_FILE=/config/input.txt # do not edit
         - log.level=DEBUG
         - TZ=Europe/Vienna # replace with your time zone
       volumes:
         - type: bind
           source: #<CONFIG_FILE_PATH>
           target: /config/input.txt
           read_only: true
       restart: unless-stopped
   ~~~
   You can find a filled-out docker-compose example [here](docs/docker_examples.md).
  
2. **Run Docker Compose** commands to manage your container:

   ~~~shell
   # Create and run container:
   docker compose up -d
   
   # Stop and remove container:
   docker compose down
   
   # Just stop/start container:
   docker compose stop
   docker compose start
   ~~~

> Note: If your Raspberry Pi does not yet have Docker installed, check out this [short guide](docs/docker_on_raspberrypi.md).

### Manually

To run Hue Scheduler manually, follow these steps:

1. **Download the latest release** from GitHub [here](https://github.com/stefanvictora/hue-scheduler/releases/latest). Ensure you have at least Java 21 installed on your device.
2. **Run the application** using the following command. Replace the placeholders enclosed in `<>` with your actual values:
   ~~~shell
   java -jar hue-scheduler.jar <API_HOST> <ACCESS_TOKEN> --lat=<LATITUDE> --long=<LONGITUDE> --elevation=<ELEVATION> <CONFIG_FILE_PATH>
   ~~~

### Configuration

Hue Scheduler requires the following configuration parameters, which can be provided via command line arguments or environment variables.

#### API Host

Specify where Hue Scheduler can access your Philips Hue bridge or Home Assistant instance.

- **Philips Hue**: Provide the IP address of your bridge. Example: `192.168.0.157` or `hue.local`. To identify your bridge in the network, refer to the detailed [Hue authentication process](docs/philips_hue_authentication.md).
- **Home Assistant**: Provide the full origin, including the scheme, host, and port if needed. Example: `http://localhost:8123`, `http://ha.local:8123`, or `https://UNIQUE_ID.ui.nabu.casa`.

#### Access Token

Create or provide an authentication token for connecting to your Philips Hue bridge or Home Assistant instance.

- **Philips Hue**: Follow the [Hue authentication process](docs/philips_hue_authentication.md) to create an access token.
- **Home Assistant**: Create a [long-lived access token](https://www.home-assistant.io/docs/authentication/) via the Security tab in the user settings.

Write down the generated tokens and insert them into your configuration.

#### Latitude, Longitude & Elevation

Provide your approximate location and optional elevation to allow Hue Scheduler to calculate your local sunrise and sunset times. This calculation is performed locally using the [shred/commons-suncalc](https://github.com/shred/commons-suncalc) library, ensuring no data is collected or sent to the Internet.

#### Config File

Specify the configuration file that tells Hue Scheduler how to control the lights in your home. Use the text-based file format described in detail in the [configuration documentation](docs/light_configuration).

> [!NOTE]   
> For additional configuration options, see the [list of advanced command line options](docs/advanced_command_line_options).

## FAQ

### Why is there a short delay when physically turning lights on and the scheduler taking over?

This is a known limitation of the Hue Bridge. There is typically a delay of around 3–4 seconds until physically turned on lights are detected as available again. In contrast, lights turned on via smart switches or an app are detected almost instantly.

Another limitation with dumb wall switches is that the Hue Bridge can take up to ~2 minutes to detect lights being physically turned off, and therefore won't detect fast off and on switches. This means if you want to reset manual overrides with dumb wall switches, you have to wait at least 2 minutes before turning the lights back on. 

### How does Hue Scheduler compare to Adaptive Lighting?

Both Adaptive Lighting and Hue Scheduler aim to automate the state of your lights based on the time of day. However, there are key differences:

- **Control Over Light Properties**: Adaptive Lighting primarily adjusts the color temperature and brightness of your lights. In contrast, Hue Scheduler offers extended control, allowing you to also set specific colors and manage the power state of your lights.

- **Flexibility and Customization**: Adaptive Lighting operates with a continuous adjustment mechanism, offering limited manual intervention. Hue Scheduler, on the other hand, provides a more hands-on approach, empowering you with the flexibility to define your own schedule. You can even introduce multiple custom interpolations, all through a single, user-friendly configuration file.

### Does Hue Scheduler access the Internet?

Hue Scheduler does not access the Internet unless you explicitly connect to a cloud-hosted Home Assistant instance. You can see exactly which REST requests Hue Scheduler sends to your devices by setting the `-Dlog.level=TRACE` JVM parameter or `log.level=TRACE` environment variable. See [Advanced Command Line Options](docs/advanced_command_line_options). The dynamic solar times are calculated locally using the [shred/commons-suncalc](https://github.com/shred/commons-suncalc) library, with no location data ever leaving your device.

### Does Hue Scheduler work with motion sensors?

Yes, but you should probably use a third-party app like iConnectHue to configure your motion sensor to only adjust the brightness of your lights, not the color or color temperature. Otherwise, the color or color temperature of your lights would switch between the sensor set values and the ones scheduled by Hue Scheduler, causing some flicker every time the sensor is activated.

## Roadmap

- [x] **Conditional states** -- set light state only if it has not been manually changed since its previously scheduled state
- [x] **Enforce states** -- ensure that a state is always set; allow no manual override
- [x] **Interpolate between states** -- support more advanced interpolations between states when using `tr-before`
- [x] **Advanced state interpolations** -- easily create full-day state interpolations without explicitly using `tr-before`
- [x] **Docker support** -- provide a prebuilt docker images for easier setup
- [x] **Home Assistant API support** -- allow controlling lights via the Home Assistant API
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

