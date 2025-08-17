# Docker Examples
    
## Docker Compose example

Below is a complete `docker-compose.yml` for a setup in **Vienna, Austria**, connecting to a **local Philips Hue Bridge**:
  
```yaml
services:
  hue-scheduler:
    container_name: hue-scheduler
    image: stefanvictora/hue-scheduler:0.12
    environment:
      - API_HOST=192.168.0.157
      - ACCESS_TOKEN=1234567890abcdefghijklmnopqrstuv
      - LAT=48.208731
      - LONG=16.372599
      - ELEVATION=165
      - TZ=Europe/Vienna
      - CONFIG_FILE=/config/input.txt # do not edit
      - ENABLE_SCENE_SYNC=true        # optional
      - log.level=TRACE
    volumes:
      - type: bind
        source: /home/user_name/.config/hue-scheduler/input.txt
        target: /config/input.txt
        read_only: true
    restart: unless-stopped
 ```

On Windows, adapt the source path, e.g. `C:\Users\user_name\.config\hue-scheduler\input.txt`. (If you use WSL, map from your Linux path instead.)


## `docker run` usage

If you prefer not to use Docker Compose, you can create and run the container directly. Replace placeholders and adjust `TZ`:

```bash
docker run -d --name hue-scheduler \
  -v "$(pwd)/input.txt:/config/input.txt:ro" \
  -e log.level=DEBUG \
  -e TZ=Europe/Vienna \
  --restart unless-stopped \
  stefanvictora/hue-scheduler:0.12 \
  <API_HOST> <ACCESS_TOKEN> \
  --lat <LATITUDE> --long <LONGITUDE> --elevation <ELEVATION> \
  --enable-scene-sync \
  /config/input.txt
```

Note for Windows:
- PowerShell: use `${PWD}` instead of `$(pwd)` in the `-v` mount.
- CMD: provide an absolute path (e.g., `C:\path\to\input.txt:C:\config\input.txt`) or run from WSL and keep the bash example.

**Stop / Start / Remove:**

```bash
docker stop hue-scheduler
docker start hue-scheduler
docker logs -f hue-scheduler
docker rm hue-scheduler
```

This mounts `input.txt` from your **current working directory** (`$(pwd)`) into the container at `/config/input.txt` (read-only). Adjust the file name and path to fit your setup. `CONFIG_FILE` is the **in-container** path; the bind mount wires it up.
