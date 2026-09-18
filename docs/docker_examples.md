# Docker examples

[Back to README](../README.md)

These examples use Vienna, Austria and a local Hue Bridge. Replace the host, token, coordinates, and time zone with your own. For Home Assistant, use its origin (such as `http://homeassistant.local:8123`) and a long-lived access token.

Configure the container through **environment variables**. Command-line flags map to uppercase names with underscores: `--enable-scene-sync` becomes `ENABLE_SCENE_SYNC`.

## Compose with a text file

Create `input.txt` next to `docker-compose.yml` before starting the container:

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

This setup needs a Hue Bridge. It reads the schedule from your Hue scenes, so there is no configuration file to mount:

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

Run these commands from the directory containing `docker-compose.yml`:

```shell
# Download the image and start in the background
docker compose pull
docker compose up -d

# Follow logs (Ctrl+C stops following; the container keeps running)
docker compose logs -f

# Reload an edited input.txt
docker compose restart

# Stop and remove the container
docker compose down
```

To update, run `docker compose pull` followed by `docker compose up -d` again. Changes to environment variables in the Compose file also require `docker compose up -d`.

For more detailed logs, add `log.level: "TRACE"` under `environment`. See [logging options](advanced_command_line_options.md#-dloglevel-jvm).

## File permissions

The container runs as a non-root user with UID and GID `10001`. The mounted file must be readable by that user.

On Linux, you can instead run the container as your own user. Add this to the service:

```yaml
    user: "${HOST_UID}:${HOST_GID}"
```

Then start it with:

```bash
HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d
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
