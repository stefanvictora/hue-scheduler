# Docker on Raspberry Pi

In case you don't yet have Docker installed on your Raspberry Pi, you can simply run the official convenience script (also described [here](https://docs.docker.com/engine/install/raspberry-pi-os/#install-using-the-convenience-script)), and it will install all required dependencies for you:

```shell
# Download official Docker install script
curl -fsSL https://get.docker.com -o get-docker.sh
# Run as privileged user
sudo sh ./get-docker.sh

# Optional: Add current user to "docker" user group to avoid using sudo with each docker command
sudo usermod -aG docker $USER
# Reload group
newgrp docker
```

