# Advanced Command Line Options
 
The following advanced command line options are available.

> [!HINT]
> You can set all command line options also via environment variables. E.g. instead of `--interpolate-all` you can also set `INTERPOLATE_ALL=true` as an environment variable.

### `--interpolate-all`

Flag to globally set ``interpolate:true`` for all states, unless explicitly disabled with ``interpolate:false``. This is useful if the primary goal is to have interpolations between all or most of the states.

**Default**: false

### `--disable-user-modification-tracking`

Flag to globally disable tracking of user modifications of lights. Per default Hue Scheduler compares the previously seen state with the current state of the light and only sets the expected scheduled state if no manual modifications have been made in between. To enforce just a single state, you can use the state-based configuration property of `force:true`.

**Default**: false

### `--default-interpolation-transition-time`

Flag to configure the default transition time used for the interpolated call when turning a light on during a `tr-before` transition. Defined either as a multiple of 100ms or with the already mentioned shorthands e.g. `5s`. If the previous state already contains a ``tr`` property, Hue Scheduler reuses the custom value instead.

~~~yacas
# Default value used:
Desk  06:00  bri:50%
Desk  07:00  bri:100%  tr-before:20min

# Defined tr reused:
Desk  06:00  bri:50%  tr:10s
Desk  07:00  bri:100%  tr-before:20min

# Effectively disabling the interpolation transition:
Desk  06:00  bri:50%  tr:0
Desk  07:00  bri:100%  tr-before:20min
~~~

**Default**: `4` (= 400 ms)

### `--min-tr-before-gap`

Only relevant and active if user modification tracking is not disabled (see `--disable-user-modification-tracking`). When using transitions, this defines the minimum gap between multiple back-to-back states in minutes. This is needed as otherwise the hue bridge may not yet recognize the target value of the transition and may incorrectly mark the light as manually overridden.
This gap is ensured by automatically shortening transitions between back-to-back states.

If Hue Scheduler still detects manual overrides between back-to-back states using transitions, try increasing the default value.

**Default**: `3` minutes

### `--max-requests-per-second`

The maximum number of PUT API requests Hue Scheduler is allowed to perform per second. The official Hue API Documentation recommends keeping this at 10 requests per second, or else the bridge might drop some requests.

Note: The bridge controls groups by using more computationally expensive broadcast messages, which is why the official recommendation is to limit group updates to one per second. Hue Scheduler automatically rate-limits lights and groups updates accordingly.

> As a general guideline we always recommend to our developers to stay at roughly 10 commands per second to the /lights resource with a 100ms gap between each API call. For /groups commands you should keep to a maximum of 1 per second.
>
> > -- [Hue System Performance — Philips Hue Developer Program (meethue.com)](https://developers.meethue.com/develop/application-design-guidance/hue-system-performance/) (requires login)

To still benefit from the ease-of-use of groups, while improving overall system performance, you can use the experimental *--control-group-lights-individually* Option, as described below.

**Default and recommended**: `10` requests per second

### `--control-group-lights-individually`

*Experimental*

Toggle if Hue Scheduler should control lights found in a group individually instead of using broadcast messages. This might improve performance but is not recommended any more, as it may impact manual modification tracking.

Note: Hue Scheduler does not validate in such cases if all the lights inside the group support the given command. Furthermore, this option might not be suitable for groups with mixed capabilities, i.e. setting color for a group that also contains a color temperature only light. In such cases, the unsupported light is not updated.

**Default**: false

### `--multi-color-adjustment-delay`

The adjustment delay in seconds for each light in a group when using the multi_color effect. Adjust this parameter to change the hue values of 'neighboring' lights.

**Default**: `4` seconds

### `--bridge-failure-retry-delay`

The delay in seconds for retrying an API call, if the bridge could not be reached due to network failure, or if it returned an API error code.

**Default**: `10` seconds

### `--power-on-reschedule-delay`

The delay in ms after the light on-event was received and the current state should be rescheduled.

**Default**: `150` ms

### `--event-stream-read-timeout`

Configures the read timeout of the API v2 SSE event stream in minutes. The connection is automatically restored after a timeout. The default of 2 hours may change in the future after more empirical results of the new event stream.

**Default**: `120` minutes

### `-Dlog.level`

A JVM argument to configure the log level of Hue Scheduler. The following values are available:

- `ERROR`: Only logs if the API returned with an error code. This should most likely never occur.
- `WARN`: Additionally logs if the bridge is not reachable, and Hue Scheduler retries
- `INFO`: Logs when a light state has been set, a manual override has been detected, as well as the current solar times for each day.
- `DEBUG` (default): Logs every scheduled state; if a state has already ended, and if an on-event for a light has been received.
- `TRACE`: Maximum logs, including all performed API requests and enforced wait times due to rate limiting.

Note: The JVM argument needs to be defined before the jar file. For example:

~~~bash
java -Dlog.level=TRACE -jar hue-scheduler.jar ...
~~~

If you are using Docker, you can provide the property via environment variables:

~~~bash
docker run -d --name hue-scheduler -e log.level=TRACE ...
~~~
