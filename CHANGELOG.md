# Changelog

## [Unreleased]

## [0.9.0] - tbd

### Added
- Added **interpolations for tr-before** states (#4): For any states using `tr-before`, if the light is turned on in the middle of the transition, Hue Scheduler now calculates the mid-transition state based on the previous state and elapsed time, and continues the transition from this point. To transition between multiple color modes, Hue Scheduler is now capable of converting values among all color modes, including CT, XY, and Hue/Sat.
- Added new `--interpolation-transition-time` global configuration flag (default `4` = 400 ms) to configure the transition time as a multiple of 100ms. This is used for the interpolated calls mentioned above.
- Added group capability validations: During startup, group states are now validated for their capabilities based on their contained lights

### Changed
- Improved **manual modification tracking for groups**: Rather than only comparing with the state of the first light in the group, Hue Scheduler now uses the group state provided by the API
- Improved **turn-on tracking for groups**: Hue Scheduler now uses group-on events instead of listening for the first contained light being turned on. To detect groups being physically turned on, every physically turned-on light inside a group now also triggers a group-on event. This is necessary, as the API does not generate any group-specific events in such cases.
- Improved support for 'On/Off plug-in unit' type of lights

### Removed
- Removed obsolete reachable checks after each state update: Those became obsolete after switching to the Hue API v2 event stream and some further optimizations implemented in v0.9.0

## [0.8.0.1] - 2023-08-06

### Added
- Added new `noon` dynamic sun time constant

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
