# Changelog

## [Unreleased]

## [0.8.0] - 2023-07-29

### Added
- Added support for the new Hue API v2 event stream
- Switched to HTTPS for communicating with the Hue bridge (requires an up-to-date Hue bridge firmware)
- New **user modification tracking for lights**. If such changes are detected, any schedules for the affected light are automatically disabled until the light has been turned off and on again.
- New `--disable-user-modification-tracking` global configuration flag (default: `false`) to disable the new user modification tracking feature
- New `force:true` state configuration property to always enforce light states despite possible user modifications (default: `false`)
- Added advanced and internal `--event-stream-read-timeout` configuration flag, for fine-grained event stream controls.

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
