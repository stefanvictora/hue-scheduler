# Docker Examples
    
## Docker Compose Example

Below you can find a full `docker-compose.yml` example file for someone located in Vienna, Austria connecting to his local Philips Hue Bridge:
  
~~~yml
services:
  hue-scheduler:
    container_name: hue-scheduler
    image: stefanvictora/hue-scheduler:0.12
    environment:
      - API_HOST=192.168.0.157
      - ACCESS_TOKEN=1028d66426293e821ecfd9ef1a0731df
      - LAT=48.208731
      - LONG=16.372599
      - ELEVATION=165
      - TZ=Europe/Vienna
      - CONFIG_FILE=/config/input.txt # do not edit
      - ENABLE_SCENE_SYNC=true
      - log.level=TRACE
    volumes:
      - type: bind
        source: /home/stefan/.config/hue-scheduler/input.txt
        target: /config/input.txt
        read_only: true
    restart: unless-stopped
~~~

If you are using Docker on Windows, make sure to adapt the source path of your input file. E.g. `C:\Users\Stefan\.config\hue-scheduler\input.txt`

## Docker Run Usage

If you don't want to use docker compose, you can also directly create and run the container for Hue Scheduler with ``docker run``. Make sure to replace the placeholder values and adapt the `TZ` time zone variable:

~~~shell
docker run -d --name hue-scheduler -v $(pwd)/input.txt:/config/input.txt:ro -e log.level=DEBUG -e TZ=Europe/Vienna --restart unless-stopped stefanvictora/hue-scheduler:0.12 <API_HOST> <ACCESS_TOKEN> --lat <LATITUDE> --long <LONGITUDE> --elevation <ELEVATION> --enable-scene-sync /config/input.txt
~~~

**Stop / Start / Remove container:**

~~~shell
docker stop hue-scheduler
docker start hue-scheduler
docker rm hue-scheduler
~~~

This mounts the ``input.txt`` file from the current working directory (see ``PWD`` variable) as read-only file to be used as input for Hue Scheduler. Feel free to change the input file and location to your setup.

