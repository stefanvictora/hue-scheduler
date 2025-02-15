# Changelog

## [0.12.4] - 2025-02-xx

### Added
- **Scene Sync Support for Home Assistant**: Implemented scene synchronization capabilities for Home Assistant integration.
  - Enable via `--enable-scene-sync` command line flag
  - Scenes follow the naming pattern: `scene.huescheduler_<group_or_area_name>`. Customize via `--scene-sync-name` (default: `HueScheduler`)
  - Supports HA automations with smart switches and motion sensors for direct light state control
  - **Known Home Assistant Limitations**:
    - Scenes are temporary and require recreation after Home Assistant restarts (handled automatically, may require a Hue Scheduler restart to immediately recreate them)
    - Dynamic scenes cannot be assigned to areas or given custom names beyond their entity ID
- **Manual Activation Mode**: Added new `--require-scene-activation` flag to schedule states only when a synced scene is activated. After which subsequent states continue normally until lights are turned off or manually overridden.
  - Designed to work with `--enable-scene-sync` for complete manual control over state activation

### Fixed
- **Scene Sync**: Fixed a sync issue for states that where not activated for a longer period of time.

## [0.12.3] - 2024-12-31

### Fixed
- **Scene Sync**: Resolved an issue where scene synchronization failed for states spanning multiple days (#16).
- **Scene Sync**: Removed unnecessary scene synchronization triggered during power-on in certain cases.

## [0.12.2] - 2024-12-20

### Added
- **Disable SSL Validation**: Introduced a new `--insecure` flag to disable SSL certificate validation, needed for bridges using self-signed certificates (#18). Refer to the [Philips Hue Developer Documentation](https://developers.meethue.com/develop/application-design-guidance/using-https/) (login required) for more details.

### Changed
- **Configuration File Handling**: Enhanced handling of tabs in the configuration file and improved error messages for invalid properties (#17).

## [0.12.1] - 2024-11-02

### Fixed
- **Scene Sync**: Fixed an issue where scene synchronization failed to handle interpolations across days, causing lights to sync as off (#16).

### Added
- **Enhanced CT Support**: Enabled support for setting 1000 Kelvin as color temperature (CT) for color lights. Removed the InvalidColorTemperatureValue error; now, if the specified CT is outside the supported range, it automatically adjusts to the nearest valid value (#15).

## [0.12.0] - 2024-10-13

### Added
- **Scene Sync Feature**: Hue Scheduler can now sync states to scenes, ensuring that smart switches and motion sensors always activate lights in the correct state.
  - To enable this feature, use the `--enable-scene-sync` command line flag or set `ENABLE_SCENE_SYNC=true` as an environment variable (default: `false`).
  - **Additional Configurable Properties**:
    - `--scene-sync-name`: Specifies the name for the synced scenes (default: `HueScheduler`).
    - `--scene-sync-interval`: Defines the interval for scene synchronization in minutes (default: `2`).
  - **Limitation**: Currently, this feature is supported only when connected to a Hue bridge.

- **APIv2 Light Effects**: Added support for scheduling all Hue APIv2 light effects.
  - Includes effects such as `candle`, `fire`, `prism`, `sparkle`, `opal`, `glisten`, etc.
  - **Note**: Supported effects vary based on the light model and are verified during startup.

### Changed

- **Hue APIv2 Migration**: Fully migrated to Hue APIv2 for enhanced functionality and compatibility.
- **Error Handling**: Improved error handling for API lookups to prevent the loss of schedules.
- **Color Temperature Comparison**: Enhanced the comparison of color temperature (CT) values using a defined threshold for better tolerance.
- **State Interpolation**: Improved state interpolation for scenarios where schedules modify only specific properties.

### Removed
- **Group Effects**: Applying effects to groups is no longer supported due to Hue APIv2 limitations.
    - The `colorloop` and `multi_colorloop` effects are no longer available. Use the `prism` effect for individual lights instead.
    - The `--multi-color-adjustment-delay` configuration parameter has been removed.

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
