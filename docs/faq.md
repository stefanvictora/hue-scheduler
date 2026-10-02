# FAQ and troubleshooting

[Back to README](../README.md)

Start here for everyday behavior and common setup problems. For the exact meaning of a setting, see the [command-line reference](advanced_command_line_options.md).

## Do lights turn on automatically?

Only when you explicitly schedule `on:true` in a text file or `[on]` in a Hue scene name. Otherwise, you decide when lights turn on, and Hue Scheduler adjusts them to the current schedule.

With a Hue Bridge, Hue Scheduler also updates brightness and color while lights are off. Sensors or switches configured to use the last on state can then turn them on with those settings. For schedules that explicitly turn lights off, use [Scene Sync](advanced_command_line_options.md#--enable-scene-sync) to activate the complete scheduled state.

**Home Assistant limitation:** Light property updates use turn-on commands. A manually turned-off member of a scheduled group may therefore turn back on when that group is updated.

## What happens when I change a light manually?

A manual change pauses scheduled adjustments for that light. Turn it off and on again, or activate a synced Hue Scheduler scene, to resume. With `--require-scene-activation`, resuming requires a synced scene activation.

Use `force:true` in a text file or `[f]` in a scene name if a particular part of the schedule must apply despite manual changes. Combined with an explicit power setting, this also keeps the light on or off for that period.

If you see unexpected manual-override messages without having changed a light, check the [override thresholds](advanced_command_line_options.md#manual-overrides--sensitivity) and the [settling time between transitions](advanced_command_line_options.md#--min-tr-before-gap).

## Why is there a delay after using a wall switch?

After power is restored, the Hue Bridge typically takes about 3–4 seconds to detect a light. Turning lights on through the app or a smart switch is usually detected much sooner.

After power is cut, the bridge can take about 2 minutes to register that a light is unavailable. A quick off/on cycle may therefore go unnoticed. If you use a wall switch to clear a manual override, leave it off long enough for the bridge to detect the change.

## Do I need to restart after editing a schedule?

- **Text file:** Restart Hue Scheduler after saving the file. With Compose, use `docker compose restart`.
- **Hue scenes:** Scene creation, edits, renames, and deletion are detected automatically when scene schedules are enabled. Changes to a scene referenced through `scene:` are also loaded automatically.
- **Hue rooms and zones:** Changes to their lights refresh running schedules automatically. A group that becomes empty during operation stays unscheduled until lights are added again.
- **Application settings:** Restart after changing command-line options. If you change environment variables in Compose, use `docker compose up -d`; `docker compose restart` does not apply those changes.

## Why is my Hue scene not used as a schedule?

Check these points in order:

1. Connect directly to a **Hue Bridge**. Reading schedules from scenes is not supported through Home Assistant.
2. Enable `--enable-auto-scene-states`, or `ENABLE_AUTO_SCENE_STATES: "true"` in Compose. `--enable-scene-sync` creates scenes with the current scheduled values; it does not discover schedules.
3. Use a supported name such as `07:00`, `sunset [i]`, or `07:00 [Mo-Fr,on]`. The entire name must follow the [scene-name rules](scene_schedules.md#scene-names); `Morning 07:00` is not accepted. Unknown options or invalid values make the scene ineligible for scheduling.
4. Check the logs for loaded definitions and errors. If you also set `CONFIG_FILE`, that file must exist and be readable even when automatic scene discovery is enabled.

Remember that a scene schedule normally adjusts lights when you turn them on. Add `[on]` to schedule automatic turn-on. If `--require-scene-activation` is enabled, activate the synced Hue Scheduler scene to start scheduled control.

## Why do schedules run at the wrong time?

Check the scheduler's time zone and coordinates. Docker uses `UTC` unless you set `TZ`, for example `TZ: "Europe/Vienna"` in Compose. After changing it, run `docker compose up -d`.

When running the JAR directly, Java uses the system time zone. To override it, place `-Duser.timezone=Europe/Vienna` **before** `-jar`. Latitude and longitude determine the solar event times; both are required even for schedules that currently use only fixed times. The startup logs include the calculated solar times.

Also check whether the definition uses interpolation or an early transition. With `[i]`, `interpolate:true`, `tr-before`, or `tr-b:`, the named time is when the target values are reached, so changes start earlier. See [transitions and interpolation](light_configuration.md#transitions--interpolations).

## My Ikea TRÅDFRI bulbs ignore transitions

Some TRÅDFRI firmware versions fail when changing multiple properties with a non-zero transition. The Hue Bridge normally uses a 400 ms transition when none is specified. Set `tr:0` when changing several properties, or split the changes into separate entries. See [issue #5](https://github.com/stefanvictora/hue-scheduler/issues/5).

## How does this compare with Adaptive Lighting?

Hue Scheduler is built around explicit schedules: choose the times and desired values, then decide where to interpolate between them. You can schedule brightness, color temperature, colors, effects, and power, using fixed times or solar events. [Adaptive Lighting](https://github.com/basnijholt/adaptive-lighting) is another option for automatically adjusting Home Assistant lights throughout the day.

## Does Hue Scheduler access the internet?

It connects to the Hue Bridge or Home Assistant address you configure. With a local address, normal operation stays on your network; a cloud-hosted Home Assistant address uses the internet. Solar times are calculated locally using [commons-suncalc](https://github.com/shred/commons-suncalc).

To inspect outgoing API requests, enable [TRACE logging](advanced_command_line_options.md#-dloglevel-jvm).
