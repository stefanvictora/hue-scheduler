<img align="right" width="155" height="155" src="https://raw.githubusercontent.com/stefanvictora/hue-scheduler/main/logo.png">

# Hue Scheduler

[![build](https://github.com/stefanvictora/hue-scheduler/actions/workflows/maven.yml/badge.svg)](https://github.com/stefanvictora/hue-scheduler)
[![GitHub Downloads](https://img.shields.io/github/downloads/stefanvictora/hue-scheduler/total?logo=github&color=%235f87c4)](https://github.com/stefanvictora/hue-scheduler/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/stefanvictora/hue-scheduler?logo=docker&color=%235f87c4)](https://hub.docker.com/r/stefanvictora/hue-scheduler)

> Boost your daytime focus and unwind at night. Hue Scheduler fine-tunes your Philips Hue or Home Assistant lights by time, sun position, and weekday.

## Introduction

**New in 0.12.0** — **Sync schedules to scenes** so lights turn on in the desired state instantly (opt-in via ``--enable-scene-sync``). Now also with Home Assistant support.

Hue Scheduler goes beyond tools like Adaptive Lighting by giving you precise control over brightness, color temperature, color, power state, and custom interpolations between solar and absolute times. It's designed to work with dumb wall switches: as soon as lights become available, Hue Scheduler applies the correct results consistent even after physical on/off toggles.

## Demo

Configure your lights with a simple, text-based file (fields separated by a tab or at least two spaces). Below are examples for daily routines, interpolations, power control, and ambiance.
                     
~~~
# Living Room
light.living_room  sunrise      bri:80%    ct:6000         tr:10s  force:true
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
> Hue Scheduler does **not** automatically turn on lights (unless you specify `on:true`). You stay in control; it handles adjustments once lights are on.

> [!NOTE]
> Manual changes temporarily suspend the schedule until lights are turned off and back on.
> If lights turn on mid-transition, Hue Scheduler computes the correct mid-transition state and continues seamlessly.

## How It Works

Each line has three parts (separated by a tab or ≥2 spaces):

~~~yacas
<Light/Group Name or ID>  <Start Time Expression>  [<Property>:<Value>]*
~~~

**Light/Group Name or ID**

Which light or group to control. Use names or IDs (e.g., `Couch` or `light.couch`). Combine multiple targets with commas. Supported Home Assistant entities: `light`, `input_boolean`, `switch`, `fan`.

**Start Time Expression**

Use fixed times (24-hour `HH:mm[:ss]`, e.g., `06:00`, `23:30:15`) or solar times (`sunrise`, `sunset`, etc.). You can offset solar times with +- minutes (e.g., `sunset-30`, `sunrise+60`). Available solar constants (chronological): `astronomical_dawn`, `nautical_dawn`, `civil_dawn`, `sunrise`, `noon`, `golden_hour`, `sunset`, `blue_hour`, `civil_dusk`, `night_hour`, `nautical_dusk`, `astronomical_dusk`.

**Properties**

- **Basic**
    - **`bri`** — brightness `1–254` or `1%–100%`
    - **`ct`** — color temperature in **Kelvin** `6500–1000` or **Mired** `153–500` (cool → warm)
    - **`on`** — power state (`true|false`)
    - **`days`** — active days (e.g., `days:Mo-Fr`, `days:Tu,We`)
- **Color**
    - **`color`** (hex or RGB), e.g., `#3CD0E2` or `60,208,226`
    - **`hue`** `0–65535` (wraps), requires `sat`
    - **`sat`** `0–254` or `0%–100%`, requires `hue`
    - **`effect`** (e.g., `prism`, `fire`, `none`)
- **Advanced**
    - **`x`** / **`y`** — CIE xy (e.g., `x:0.6024  y:0.3433`)
    - **`force:true`** — enforce state even after user changes
- **Transitions**
    - **`tr`** — transition at start (e.g., `tr:10s`, `tr:1h5min`)
    - **`tr-before`** — pre-transition starting before the state (relative, absolute, or solar, e.g., `tr-before:30min`, `tr-before:06:00`, `tr-before:civil_dawn+5`)
    - **`interpolate:true`** — auto-transition from the previous state; also spans across days

> [!TIP]
> Full syntax and edge cases: see the [full configuration guide](docs/light_configuration.md).

## Quick Start

Run Hue Scheduler via Docker (recommended) or manually with Java. Configuration differs slightly for each method.

### Prerequisites

- A Philips Hue Bridge (up-to-date) or a Home Assistant instance
- A device that runs continuously on your network (e.g., Raspberry Pi)
- Docker **or** Java 21

### Docker

1. **Create `docker-compose.yml`:**

   ~~~yaml
   services:
     hue-scheduler:
       container_name: hue-scheduler
       image: stefanvictora/hue-scheduler:0.13
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
           source: /path/to/your/input.txt  # <- set your file path
           target: /config/input.txt
           read_only: true
       restart: unless-stopped
   ~~~
   A filled-out example is available in [docs/docker_examples.md](docs/docker_examples.md).

2. **Provide parameters:**

   Environment variables:
    - `API_HOST` — Hue Bridge or Home Assistant origin (e.g., `192.168.0.157`, `http://ha.local:8123`, `https://UNIQUE_ID.ui.nabu.casa`)
    - `ACCESS_TOKEN` — [Hue bridge username](https://github.com/stefanvictora/hue-scheduler/blob/main/docs/philips_hue_authentication.md) or [Home Assistant long-lived access token](https://www.home-assistant.io/docs/authentication/).
    - `LAT`, `LONG`, `ELEVATION` — location for solar times
    - `TZ` — your time zone
   
   Volume configuration:
    - `source` — local path to your [configuration file](docs/light_configuration.md) containing the light schedules.
    
    Advanced options: see [Advanced Command-Line Options ](docs/advanced_command_line_options.md). From 0.12.0 onward, enable Scene Sync via `ENABLE_SCENE_SYNC=true` (env) or `--enable-scene-sync` (CLI).
  
3. **Start/stop with Docker Compose:**

   ~~~shell
   # Start:
   docker compose up -d
   
   # Stop & remove:
   docker compose down
   ~~~

If your Raspberry Pi doesn't have Docker yet, see [docs/docker_on_raspberrypi.md](docs/docker_on_raspberrypi.md).

### Manual (Java)

1. **Download the latest release**: [releases/latest](https://github.com/stefanvictora/hue-scheduler/releases/latest).
2. **Run the JAR** (replace placeholders):
   ~~~shell
   java -jar hue-scheduler.jar <API_HOST> <ACCESS_TOKEN> --lat=<LATITUDE> --long=<LONGITUDE> --elevation=<ELEVATION> <CONFIG_FILE_PATH>
   ~~~

## FAQ

### Does Hue Scheduler work with motion sensors and smart switches?

Yes. From **0.12.0**, with Scene Sync enabled (``--enable-scene-sync``), Hue Scheduler creates a synced scene (default: `HueScheduler`) that mirrors the current scheduled state of a room or zone. Select this scene in your motion sensor or smart switch so lights turn on in the desired state instantly.

### Why is there a delay after physically switching lights on?

It's a Hue Bridge limitation. Physically powered-on lights are typically detected after ~3–4 seconds; app/switch activations are near-instant. Also note: after turning lights **off** via a dumb wall switch, the bridge can take up to ~2 minutes to register the change. Fast off / on cycles may be missed; to clear manual overrides with dumb switches, wait ~2 minutes before turning lights back on.

### My Ikea TRÅDFRI bulbs don’t respect transitions when changing multiple properties.

Some TRÅDFRI firmware versions fail when applying **multiple properties with a non-zero transition**. Because the Hue Bridge defaults to 400 ms (`tr:4`) if not specified, set `tr:0` when changing multiple properties on those bulbs—or split changes into separate states. See issue https://github.com/stefanvictora/hue-scheduler/issues/5 for details.

### How does Hue Scheduler compare to Adaptive Lighting?

Both automate light state across the day. Differences:

- **Control over properties:** Adaptive Lighting mostly adjusts color temperature and brightness. Hue Scheduler also controls color and power.
- **Flexibility:** Adaptive Lighting continuously adjusts with limited manual scheduling. Hue Scheduler is schedule-driven and highly customizable—define multiple custom interpolations in a single, human-readable file.

### Does Hue Scheduler access the Internet?

No, unless you explicitly connect to a cloud-hosted Home Assistant instance. You can inspect outbound REST requests by setting `-Dlog.level=TRACE` (JVM) or `log.level=TRACE` (env). See [Advanced Command-Line Options](docs/advanced_command_line_options.md). Solar times are computed locally via [shred/commons-suncalc](https://github.com/shred/commons-suncalc); your location data never leaves the device.

## Roadmap

- [x] **Detect manual overrides** — set state only if not changed by the user since the last scheduled state
- [x] **Enforce states** — always set state; disallow manual overrides
- [x] **Interpolate between states** — advanced transitions with `tr-before`
- [x] **Advanced state interpolations** — all-day interpolations without explicitly using `tr-before`
- [x] **Docker support** — prebuilt Docker images
- [x] **Home Assistant API support** — control lights via HA
- [x] **Hue API v2 effects** — support additional effects
- [x] **Scene Sync** — scenes that mirror the scheduled state of a room/zone
- [ ] **Define schedules via scenes** — update schedules without restarting
- [ ] **Conditional states** — apply only if conditions are met
- [ ] **Date-based scheduling** — restrict by date ranges
- [ ] **Gradients** — support gradient-capable lights
- [ ] **Scene scheduling** — schedule scenes for groups
- [ ] **Sunrise/sunset min/max** — bound dynamic times to a window
- [ ] **Web GUI** — configure/update schedules in a browser
- [ ] **Home Assistant Add-on** — package as an easy install

## Developing

~~~shell
# Clone:
git clone https://github.com/stefanvictora/hue-scheduler.git
cd hue-scheduler

# Build with Maven:
mvnw clean install
~~~

The runnable JAR is created at `target/hue-scheduler.jar` (with all dependencies).

### Docker Image

Build and run your own image (replace `<VERSION>`):

~~~shell
docker build -t stefanvictora/hue-scheduler:<VERSION> -f Dockerfile .
~~~

Usage is shown in **Quick Start → Docker**. Useful commands:

~~~shell
# Rebuild and run:
docker compose up -d --build

# Remove container on exit:
docker run --rm -e "log.level=TRACE" --name hue-scheduler ...
~~~

##  Similar Projects

- [Kelvin — The hue bot](https://github.com/stefanwichmann/kelvin) — automates color temperature and brightness over the day
- [Adaptive Lighting](https://github.com/basnijholt/adaptive-lighting) — Home Assistant custom component for adaptive CT and brightness

## License

```
Copyright 2021-2025 Stefan Victora

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

