# Docker Examples
    
## Docker Compose Example

Below you can find a full `docker-compose.yml` example file for someone located in Vienna, Austria connecting to his local Philips Hue Bridge:
  
~~~yml
services:
  hue-scheduler:
    container_name: hue-scheduler
    image: stefanvictora/hue-scheduler:0.10.0-SNAPSHOT
    environment:
      - HOST=192.168.0.157
      - ACCESS_TOKEN=1028d66426293e821ecfd9ef1a0731df
      - LAT=48.208731
      - LONG=16.372599
      - ELEVATION=165
      - CONFIG_FILE=/config/input.txt
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

If you don't want to use docker compose, you can also directly create and run the container for Hue Scheduler with ``docker run``. Make sure to replace the placeholder values:

**Using Powershell:**

~~~powershell
docker run -d `
  --name hue-scheduler `
  -v ${PWD}/input.txt:/config/input.txt:ro `
  -e log.level=DEBUG `
  stefanvictora/hue-scheduler:0.10.0 `
  <HOST> <ACCESS_TOKEN> `
  --lat <LATITUDE> `
  --long <LONGITUDE> `
  --elevation <ELEVATION> `
  /config/input.txt
~~~

**Using Bash:**

~~~shell
docker run -d \
  --name hue-scheduler \
  -v $(pwd)/input.txt:/config/input.txt:ro \
  -e log.level=DEBUG \
  stefanvictora/hue-scheduler:0.10.0 \
  <HOST> <ACCESS_TOKEN> \
  --lat <LATITUDE> \
  --long <LONGITUDE> \
  --elevation <ELEVATION> \
  /config/input.txt
~~~

**Stop / Start / Remove container:**

~~~shell
docker stop hue-scheduler
docker start hue-scheduler
docker rm hue-scheduler
~~~

This mounts the ``input.txt`` file from the current working directory (see ``PWD`` variable) as read-only file to be used as input for Hue Scheduler. Feel free to change the input file and location to your setup.

