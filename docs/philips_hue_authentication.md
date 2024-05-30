# Philips Hue Authentication

If you do not yet know your bridge's IP address, you can discover it by navigating to [https://discovery.meethue.com](https://discovery.meethue.com/) and copying the returned IP address. For example:

~~~json
[{"id":"<id>","internalipaddress":"192.168.0.59"}]
~~~

Next, to authenticate a new user on your bridge, navigate to `http://<BRIDGE_IP_ADDRESS>/debug/clip.html` in your browser and enter the following in the corresponding fields, replacing ``<name>`` with any value of 26 characters or fewer:

~~~
URL:	/api
Body:	{"devicetype":"hue_scheduler#<name>"}
~~~

Now press the large physical button on your bridge and within 30 seconds press the ``POST`` button on the web form.

You should get your new username as a response. For example:

~~~json
[{"success":{"username": "83b7780291a6ceffbe0bd049104df"}}]
~~~

Copy and save that username for further use with Hue Scheduler. You only need to perform this authentication process once.

If you get the error message ``"link button not pressed"``, try to repeat the process of pressing the button on your bridge and the ``POST`` button in the web interface within 30 seconds.
