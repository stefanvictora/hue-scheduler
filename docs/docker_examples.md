# Docker examples

[Back to README](../README.md)

You need Docker with the Compose plugin. The image includes Java. For Raspberry Pi installation, see [Docker on Raspberry Pi](docker_on_raspberrypi.md).

These examples use Vienna, Austria and a local Hue Bridge. Replace the host, [access token](philips_hue_authentication.md), coordinates, and time zone with your own. For Home Assistant, use its origin (such as `http://homeassistant.local:8123`) and a long-lived access token. The address must be reachable from the container; `localhost` refers to the container itself.

Configure the container through **environment variables**. Command-line flags map to uppercase names with underscores: `--enable-scene-sync` becomes `ENABLE_SCENE_SYNC`. The image's entrypoint does not forward extra command-line arguments, so use `environment` in Compose or `-e` with `docker run`.

Set `TZ` to your local time zone so that fixed times and solar events use the right day and time. The container defaults to UTC if `TZ` is omitted.

## Compose with a text file

Create `input.txt` with your [text-file schedule](light_configuration.md). Save the following as `docker-compose.yml` in the same directory:

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
      ENABLE_SCENE_SYNC: "true"
    volumes:
      - type: bind
        source: ./input.txt
        target: /config/input.txt
        read_only: true
    restart: unless-stopped
```

`source` is the file on your computer; `CONFIG_FILE` is its path inside the container. You can use an absolute source path instead, including a Windows path such as `C:/Users/your_name/.config/hue-scheduler/input.txt`.

Scene Sync is optional. Remove `ENABLE_SCENE_SYNC` if you do not need scenes for sensors or switches. To also discover [Hue scene schedules](scene_schedules.md), add `ENABLE_AUTO_SCENE_STATES: "true"`.

## Compose with Hue scene schedules only

This setup requires a Hue Bridge. First [create schedule scenes in the Hue app](scene_schedules.md#create-your-first-schedule), then save the following as `docker-compose.yml`. The schedule comes from those scenes, so there is no configuration file to mount:

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
      ENABLE_AUTO_SCENE_STATES: "true"
      ENABLE_SCENE_SYNC: "true"
    restart: unless-stopped
```

## Start, update, and stop

Run these commands from the directory containing `docker-compose.yml` to download the image and start in the background:

```shell
docker compose pull
docker compose up -d
```

Check that the scheduler has connected and loaded your schedule:

```shell
docker compose logs -f
```

Press Ctrl+C to stop following logs; the container keeps running.

| Change | Apply it with |
|---|---|
| Edit `input.txt` | `docker compose restart` |
| Edit environment variables in `docker-compose.yml` | `docker compose up -d` |
| Update the image | `docker compose pull`, then `docker compose up -d` |
| Edit Hue schedule scenes | No restart needed |

To move to another release, first change the image tag in `docker-compose.yml`, then follow the update commands above. Restarting alone does not load a new image or changed environment variables.

To stop and remove the container:

```shell
docker compose down
```

Your host's `input.txt` and `docker-compose.yml` remain in place. Run `docker compose up -d` to start again.

For more detailed logs, add `log.level: "TRACE"` under `environment`. See [logging options](advanced_command_line_options.md#-dloglevel-jvm).

## File permissions

The container runs as a non-root user with UID and GID `10001`. The mounted file must be readable by that user.

On Linux, you can instead run the container as your own user. Add this to the service:

```yaml
    user: "${HOST_UID}:${HOST_GID}"
```

Export these values in your shell before running Compose commands:

```bash
export HOST_UID=$(id -u)
export HOST_GID=$(id -g)
docker compose up -d
```

## Using docker run

This example uses Bash. Create `input.txt` in the current directory first:

```bash
docker run -d --name hue-scheduler \
  --restart unless-stopped \
  -v "$(pwd)/input.txt:/config/input.txt:ro" \
  -e API_HOST=192.168.0.157 \
  -e ACCESS_TOKEN=YOUR_ACCESS_TOKEN \
  -e LAT=48.208731 \
  -e LONG=16.372599 \
  -e ELEVATION=165 \
  -e TZ=Europe/Vienna \
  -e CONFIG_FILE=/config/input.txt \
  -e ENABLE_SCENE_SYNC=true \
  stefanvictora/hue-scheduler:0.17
```

For scene schedules only, remove the volume (`-v`) and `CONFIG_FILE` arguments, and add `-e ENABLE_AUTO_SCENE_STATES=true` before the image name.

On Windows, use the Compose examples above or run this command in WSL. The backslash line continuations shown here are Bash syntax.

```shell
docker logs -f hue-scheduler
docker stop hue-scheduler
docker start hue-scheduler
```

After stopping the container, remove it with `docker rm hue-scheduler` if it is no longer needed.
