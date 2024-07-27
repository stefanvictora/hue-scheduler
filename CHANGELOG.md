# Changelog

## [Unreleased]

### Added
- **Scene Sync Feature**: Hue Scheduler can now sync the state to scenes
  - Those synced scenes allow turning on lights always in their correct state via smart switched and motion sensors
  - This feature has to be enabled via the new ``--enable-scene-sync`` command line flag or ``ENABLE_SCENE_SYNC=true`` environment variable (default: `false`).
  - Added additional `--scene-sync-name` (default: `HueScheduler`) and `--scene-sync-interpolation-interval` (default `2` minutes) properties to fine-tune this feature. See [Advanced Command Line Options](docs/advanced_command_line_options.md).
- **APIv2 Effects**: Now all supported Hue APIv2 light effects can be scheduled.
  - Include e.g. `candle`, `fire`, `prism`, `sparkle`, `opal`, `glisten`.
  - The exact supported values depend on the light model and is verified during startup.

### Changed
- Fully switched to Hue APIv2
- Improved error handling for lookups.

### Removed
- Due to Hue APIv2 limitations, effects can't be applied to groups anymore.
  - `colorloop` and `multip_colorloop` effects are not supported anymore. For single lights use `prism` instead.
  - Removed `--multi-color-adjustment-delay` configuration parameter.

## [0.11.0] - 2024-06-29

### Added
- **Scene Activation Detection**: Hue Scheduler now temporarily disables turn-on event tracking for affected lights and groups when scenes are activated. This prevents it from taking over control when lights are turned on via scenes (#10).
  - A new `--scene-activation-ignore-window` command line option has been added to fine-tune this behavior, with a default value of `5` seconds.
  - To disable this behavior, you can either disable user modification tracking entirely (see `--disable-user-modification-tracking`) or set the `force:true` property on a state-by-state basis.
  - Limitations: When connected to Home Assistant, scenes turned on via the Hue bridge (e.g., via apps, smart switches, etc.) cannot be detected due to Home Assistant limitations. However, Hue-based scenes turned on via Home Assistant can still be detected.

### Changed
- **Color Accuracy**: Enhanced algorithms for RGB to XY and XY to CT conversions.
- **Color Comparison**: Implemented similarity thresholds for detecting manual overrides in color and brightness, replacing exact matches.

### Fixed
- **Home Assistant**: Resolved an issue with using `effect:colorloop`. More effects will be added in upcoming releases.

### Removed
- **Hue and Saturation**: Removed support for setting `hue` and `sat` properties independently, as this feature was only available in Hue API v1.

## [0.10.0] - 2024-05-30

### Added
- **Added support for Home Assistant (HA) Rest and WebSocket APIs** 🥳: Hue Scheduler can now control lights and devices through HA, allowing you to manage a wider range of smart home devices.
  - **Setup**: Simply point Hue Scheduler to your HA instance (e.g., `http://ha.local:8123`) and provide a [long-lived access token](https://www.home-assistant.io/docs/authentication/), and you are good to go.
  - **Supported Entity Types**: `light`, `input_boolean`, `switch`, `fan`.
  - **Limitation**: HA always appends `on:true` to all state changes in API calls, which can cause manually turned-off lights in groups to turn back on, unlike the Hue API.
- **Official Docker Image**: Published on Docker Hub at [stefanvictora/hue-scheduler](https://hub.docker.com/r/stefanvictora/hue-scheduler). Check the updated README for detailed usage instructions.

### Changed
- **Java Version Requirement**: Updated the minimum Java version to 21 to accommodate new features and enhancements.
- **Virtual Threads**: Improved scheduling performance by adopting **virtual threads**.
- **Scheduling Optimization**: Enhanced "off" detection for lights, reducing unnecessary API calls. Improved start time calculation performance, reducing overall start-up time.
- **Documentation Update**: Restructured the README for better readability. Extracted detailed documentation into separate files:
  - [Light Configuration](docs/light_configuration.md)
  - [Philips Hue Authentication](docs/philips_hue_authentication.md)
  - [Advanced Command Line Options](docs/advanced_command_line_options.md)
  - [Docker on Raspberry Pi](docs/docker_on_raspberrypi.md)
  - [Docker Examples](docs/docker_examples.md)


## [0.9.0] - 2023-09-18

### Added
- **Added interpolations for tr-before states (#4)**: For states using `tr-before`, if the light is turned on mid-transition, Hue Scheduler now calculates the mid-transition point based on the previous state and time elapsed, then continues the transition from there. Additionally, Hue Scheduler now supports transitioning between all color modes by converting values among CT, XY, and Hue/Sat.
- Added ``interpolate:true`` state property that enables **dynamic transitions from the previous state**, without having to manually set ``tr-before``
- Added ``--interpolate-all`` command line flag to globally set ``interpolate:true`` for all states, unless a state is explicitly marked otherwise
- ``tr-before`` transition times can now utilize both absolute and solar time references, such as ``tr-before:12:00`` or ``tr-before:sunset+10``
- Added `--interpolation-transition-time` command line option: Allows configuration of the default transition time for interpolated calls in 100ms increments. Used when the previous state does not have an explicit transition time set via the ``tr`` property. Default: `4` (= 400 ms).
- Added capability validations to groups: On startup, group states are now validated against the capabilities of the contained lights.
- Added ability to specify brightness (`bri`) [``1%``-``100%``] and saturation (`sat`) [``0%``-``100%``] values in percentages 
- Added a ``h`` shorthand for ``tr`` and ``tr-before`` properties. Additionally, combinations like ``1h20min5s3`` are now possible for more fine-grained control.
- Added automatic gaps for consecutive states with transitions: To prevent the Hue bridge from potentially misreporting target light states, which would result in false manual modification detections, short gaps are automatically added. This feature is active unless manual modification tracking is disabled. Added ``--min-tr-before-gap`` command line property to configure the minimal gap enforced by Hue Scheduler. Default: ``3`` minutes.

### Changed
- Extended ``tr-before`` max value to 24 hours by **splitting up long-running transitions into multiple calls** and interpolating between them.
- **Enhanced manual modification tracking for groups**: Hue Scheduler now compares the state of every light in a group, instead of just the first one. Special cases for groups with different light capabilities are automatically handled, as, e.g., we can't expect color temperature lights to display color.
- Enhanced modification tracking for color states: Comparisons now factor in the light's color gamut.
- **Optimized turn-on tracking for groups**: Hue Scheduler now leverages group-on events rather than monitoring the first light inside a group. Moreover, every physically turned-on group light now triggers a group-on event, as the Hue bridge does not create any events in such cases.
- Extended the functionality of the ``force:true`` property: It can now reschedule ``on:false`` states upon power-on, ensuring lights remain off during a specified time periods.
- Reduced max value for ``tr`` to ``60000``, equivalent to 100min, to conform with the max value supported by the API v2.
- Improved support for ``tr-before`` transitions that span between two days
- Improved support for 'On/Off plug-in unit' type lights
- Improved log messages

### Removed
- Removed obsolete reachable checks post-state update: Those became redundant following the transition to the Hue API v2 event stream and optimizations introduced in v0.9.0.

## [0.8.0.1] - 2023-08-06

### Added
- Added new `noon` dynamic solar time constant

### Fixed
- Fixed incorrect manual change detection for brightness only states

## [0.8.0] - 2023-07-29

### Added
- Added support for the new Hue API v2 event stream
- Switched to HTTPS for communicating with the Hue bridge (requires an up-to-date Hue bridge firmware)
- New **user modification tracking for lights**. If such changes are detected, any schedules for the affected light are automatically disabled until the light has been turned off and on again.
- New `--disable-user-modification-tracking` global configuration flag (default: `false`) to disable the new user modification tracking feature
- New `force:true` state configuration property to always enforce light states despite possible user modifications (default: `false`)
- Added advanced and internal `--event-stream-read-timeout` configuration flag, for fine-grained event stream controls.
- Added --power-on-reschedule-delay global configuration flag, to fine tune the rescheduling after a power-on event was detected

### Changed
- Retry logic for unreachable lights has been replaced with light-on event tracking
- Light states are now always rescheduled after lights are turned on

### Removed
- Confirmations for scheduled states have been removed, as this workaround is not needed anymore with the new event stream
- Removed `--retry-delay`, `--confirm-all`, `--confirm-count`, `--confirm-delay` command line settings
- Removed `confirm:true|false` state configuration property
   
## [0.7.1.1] - 2022-12-19

- Fixes NullPointerException in SunTimesProvider
- Updates dependencies

## [0.7.0] - 2021-05-14

- First public release
