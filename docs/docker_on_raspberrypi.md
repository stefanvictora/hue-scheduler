# Docker on Raspberry Pi

[Back to README](../README.md)

Use **64-bit Raspberry Pi OS** on a compatible Raspberry Pi. Hue Scheduler's current Docker runtime is based on Java 25 for 64-bit ARM; a 32-bit OS cannot run this image natively. Check your OS architecture:

```shell
dpkg --print-architecture
```

The expected result is `arm64`. If it is `armhf`, you are using a 32-bit OS.

## Install Docker and Compose

Follow Docker's official [Debian installation instructions](https://docs.docker.com/engine/install/debian/#install-using-the-apt-repository), which also apply to [64-bit Raspberry Pi OS](https://docs.docker.com/engine/install/raspberry-pi-os/). Install the **Compose plugin** (`docker-compose-plugin`) along with Docker Engine.

Verify both are available:

```shell
sudo docker run --rm hello-world
sudo docker compose version
```

If Docker is already installed and both commands work, continue to the setup below. Java does not need to be installed on the Pi because it is included in the image.

## Run Docker without sudo (optional)

You can keep using `sudo docker ...`, or add your user to the `docker` group. Membership grants root-level access through Docker; see Docker's [post-installation instructions](https://docs.docker.com/engine/install/linux-postinstall/).

```shell
sudo usermod -aG docker "$USER"
```

Log out and back in, then check `docker compose version` without `sudo`.

## Start Hue Scheduler

Choose a [Compose example](docker_examples.md): a text-file schedule works with Hue Bridge or Home Assistant; Hue scene schedules require a Hue Bridge. Use your own bridge or Home Assistant address, token, coordinates, and time zone.

The examples include `restart: unless-stopped`, so Docker restarts the container after a reboot unless you explicitly stopped it. Keep the Pi powered on for the scheduler to run.
