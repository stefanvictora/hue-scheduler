<img align="right" width="155" height="155" src="https://raw.githubusercontent.com/stefanvictora/hue-scheduler/main/logo.png" alt="Hue Scheduler logo">

# Hue Scheduler

[![build](https://github.com/stefanvictora/hue-scheduler/actions/workflows/maven.yml/badge.svg)](https://github.com/stefanvictora/hue-scheduler/actions/workflows/maven.yml)
[![GitHub Downloads](https://img.shields.io/github/downloads/stefanvictora/hue-scheduler/total?logo=github&color=%235f87c4)](https://github.com/stefanvictora/hue-scheduler/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/stefanvictora/hue-scheduler?logo=docker&color=%235f87c4)](https://hub.docker.com/r/stefanvictora/hue-scheduler)

Schedule your Philips Hue or Home Assistant lights by time of day, sun position, and weekday. Set brightness, color temperature, colors, and effects, with gradual transitions throughout the day.

Hue Scheduler adjusts lights when you turn them on and pauses after a manual change. With a Hue Bridge, it also keeps off lights ready with the scheduled settings. Use Scene Sync to give motion sensors and smart switches a scene that always matches your schedule.

**New in 0.17.0:** Create and edit schedules directly in the Hue app by naming scenes after times, such as `sunset` or `22:00 [i]`. Scene changes and changes to the lights in a room or zone update running schedules automatically. See the [scene schedule guide](docs/scene_schedules.md) and [changelog](CHANGELOG.md).

## Choose how to create your schedule

| Method | Works with | How it works |
|---|---|---|
| [Text file](docs/light_configuration.md) | Hue Bridge and Home Assistant | Write one line per time and target. Configure individual lights, groups, or supported Home Assistant entities. |
| [Hue scene schedules](docs/scene_schedules.md) | Hue Bridge | Save the desired light settings in a scene and put the time in its name. Enable `--enable-auto-scene-states`; no text file is needed. |

You can also combine both methods. A text-file schedule can use an existing Hue scene's colors and brightness with `scene:Relax`.

### Text-file example

Create `input.txt`, using a tab or **at least two spaces** between columns:

```text
# Bright in the morning, gradually warmer and dimmer in the evening
Living room  07:00   bri:100%  ct:5000
Living room  sunset  bri:60%   ct:3000  interpolate:true
Living room  23:00   bri:30%   ct:2200  interpolate:true

# Turn the porch light on at dusk and off at 23:00
Porch light  civil_dusk  on:true   bri:100%  tr:10s
Porch light  23:00       on:false  tr:5min
```

Replace the names with your own lights or groups. With Home Assistant, you can use entity IDs such as `light.living_room`.

`interpolate:true` means “gradually reach these values by this time, starting at the previous entry's time.” In this example, the living room changes from its morning settings to its evening settings between 07:00 and sunset.

### Hue scene example

Save three scenes in a Hue room or zone, each with the light settings you want:

| Scene name | Saved settings | Schedule |
|---|---|---|
| `07:00` | Bright, cool light | Apply from 07:00. |
| `sunset [i]` | Warm, softer light | Gradually reach these settings by sunset. |
| `23:00 [i]` | Dim, warm light | Gradually reach these settings by 23:00. |

Enable `ENABLE_AUTO_SCENE_STATES=true` in Docker or `--enable-auto-scene-states` with Java. Edit the scenes in the Hue app whenever you want to change the schedule. See [scene names and options](docs/scene_schedules.md#scene-names) for weekday restrictions, transitions, and power control.

> [!NOTE]
> By default, you decide when lights turn on. Schedule `on:true` in a text file or `[on]` in a scene name to turn them on automatically; see the [Home Assistant group limitation](docs/faq.md#do-lights-turn-on-automatically). A manual change pauses scheduled adjustments until the light is turned off and on again, or you activate a synced Hue Scheduler scene. Use `force:true` or `[f]` when a schedule must take precedence over manual changes.

## Quick start

You need a Hue Bridge or Home Assistant instance, a device that stays on, and either **Docker** or **Java 25**.

### 1. Get your connection details

- **Hue Bridge:** Find its IP address and [create an API key](docs/philips_hue_authentication.md).
- **Home Assistant:** Use its origin, such as `http://homeassistant.local:8123`, and a [long-lived access token](https://www.home-assistant.io/docs/authentication/).
- Have your latitude, longitude, and time zone ready. Solar times are calculated locally. Elevation is optional and defaults to 0 metres.

### 2. Run with Docker Compose

Save this as `docker-compose.yml`. Replace the connection details, location, time zone, and file path with your own:

```yaml
services:
  hue-scheduler:
    image: stefanvictora/hue-scheduler:0.17
    container_name: hue-scheduler
    environment:
      API_HOST: "192.168.0.157"
      ACCESS_TOKEN: "YOUR_ACCESS_TOKEN"
      LAT: "48.208731"
      LONG: "16.372599"
      ELEVATION: "165"
      TZ: "Europe/Vienna"
      CONFIG_FILE: /config/input.txt
    volumes:
      - type: bind
        source: ./input.txt
        target: /config/input.txt
        read_only: true
    restart: unless-stopped
```

Create `input.txt` before starting the container. The example mounts it from the same directory as the Compose file.

**Using only Hue scene schedules?** Add `ENABLE_AUTO_SCENE_STATES: "true"` under `environment`, then remove `CONFIG_FILE` and the entire `volumes` section. To combine scenes and a text file, keep both.

```shell
docker compose pull
docker compose up -d
docker compose logs -f
```

Stop with `docker compose down`. After editing `input.txt`, reload it with `docker compose restart`. Hue scene edits are picked up automatically.

See [Docker examples](docs/docker_examples.md) for a complete setup without an input file, `docker run`, and file permissions. For installation on a Raspberry Pi, see [Docker on Raspberry Pi](docs/docker_on_raspberrypi.md).

### Or run with Java

Download `hue-scheduler.jar` from the [releases page](https://github.com/stefanvictora/hue-scheduler/releases).

With a text file:

```shell
java -jar hue-scheduler.jar <API_HOST> <ACCESS_TOKEN> --lat=<LATITUDE> --long=<LONGITUDE> input.txt
```

With Hue scene schedules:

```shell
java -jar hue-scheduler.jar <API_HOST> <ACCESS_TOKEN> --lat=<LATITUDE> --long=<LONGITUDE> --enable-auto-scene-states
```

Java uses the system time zone. To choose one explicitly, put `-Duser.timezone=Europe/Vienna` before `-jar`. Add `--elevation=<METRES>` if needed. Restart after editing a text-file schedule.

## Motion sensors and smart switches

Enable **Scene Sync** with `ENABLE_SCENE_SYNC: "true"` in Docker or `--enable-scene-sync` with Java. Hue Scheduler creates scenes that follow your schedule; the default scene name on Hue is **Hue Scheduler**. Assign this scene to your motion sensor or switch so it activates the right settings immediately.

Scene Sync works with both Hue Bridge and Home Assistant, and with either schedule source. It is separate from scene schedules: **scene schedules define what should happen; synced scenes let you activate the current result.**

To apply schedules only after you activate a synced scene, also enable `--require-scene-activation`. See [Scene Sync and activation options](docs/advanced_command_line_options.md#scene-sync--activation) for details and Home Assistant setup notes.

## Documentation

| Guide | What you will find |
|---|---|
| [Text-file configuration](docs/light_configuration.md) | Targets, solar times, time functions, colors, effects, and transitions |
| [Hue scene schedules](docs/scene_schedules.md) | Scene-name syntax, examples, and migration from a text file |
| [Command-line options](docs/advanced_command_line_options.md) | Environment variables, Scene Sync, manual overrides, logging, and tuning |
| [Docker examples](docs/docker_examples.md) | Compose, scene-only setups, `docker run`, and permissions |
| [FAQ and troubleshooting](docs/faq.md) | Wall switches, manual changes, bulb limitations, and network access |
| [Changelog](CHANGELOG.md) | Release notes |

## Developing

Build and run the tests with Java 25:

```shell
git clone https://github.com/stefanvictora/hue-scheduler.git
cd hue-scheduler
./mvnw clean install
```

On Windows, use `./mvnw.cmd clean install`. The runnable JAR, including dependencies, is written to `target/hue-scheduler.jar`.

Build a local Docker image with `docker build -t hue-scheduler:local .`.

## Similar projects

- [Kelvin — The hue bot](https://github.com/stefanwichmann/kelvin)
- [Adaptive Lighting](https://github.com/basnijholt/adaptive-lighting)

## License

Copyright 2021–2026 Stefan Victora. Licensed under the [Apache License 2.0](LICENSE).

