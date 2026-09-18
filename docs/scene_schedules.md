# Hue scene schedules

[Back to README](../README.md)

Create a schedule directly in the Hue app: save the light settings you want in a scene, then use its name to specify when they apply. Hue Scheduler reads the scenes and keeps the schedule up to date as you edit them.

This feature requires a **Philips Hue Bridge** and `--enable-auto-scene-states` (Docker: `ENABLE_AUTO_SCENE_STATES=true`). It is disabled by default and is not available with Home Assistant.

## Create your first schedule

1. Choose a room or zone in the Hue app.
2. Set its lights to the brightness and colors you want in the morning. Save a scene named `07:00`.
3. Set warmer, dimmer values and save another scene named `sunset [i]`.
4. Save a dim nighttime scene named `23:00 [i]`.
5. Start Hue Scheduler with automatic scene discovery enabled. Use the [Docker setup without an input file](docker_examples.md#compose-with-hue-scene-schedules-only), or run:

   ```shell
   java -jar hue-scheduler.jar <BRIDGE_IP> <ACCESS_TOKEN> --lat=<LATITUDE> --long=<LONGITUDE> --enable-auto-scene-states
   ```

The room uses the morning scene from 07:00 and gradually changes toward the sunset scene during the day. It then gradually reaches the nighttime scene by 23:00. The `[i]` option belongs to the scene you want to **reach** at the named time.

You still decide when lights turn on. Add `[on]` to a scene name if the schedule should turn them on automatically; for example, `07:00 [on]`.

## Scene names

Use the entire scene name for a time and optional bracketed options:

```text
<time> [<option>,<option>,...]
```

The brackets are optional. `sunset` is a complete schedule name; `Evening sunset` is an ordinary scene name and is not scheduled. The scene's room or zone determines which group is controlled. Its saved settings supply each light's brightness, color temperature, color, effect, and gradient.

### Times

| Kind | Examples |
|---|---|
| Fixed time | `07:00`, `23:30:15` |
| Alternative clock notation | `7:00`, `7.30`, `7 pm`, `7:30 pm`, `19h30`, `19 Uhr` |
| Solar time | `sunrise`, `sunset`, `civil_dusk`, `golden_hour` |
| Solar time with offset | `sunset-30`, `sunrise+1h`, `sunset+1h15min` |
| Time function | `max(sunrise,07:00)`, `min(sunset,21:00)` |

Offsets without a unit are minutes. Offsets also accept `h`, `min` (or `m`), and `s`, including combinations. Solar times use the location and time zone configured for Hue Scheduler.

Scene names also accept friendly solar names such as `golden hour`, `Sonnenaufgang`, `Sonnenuntergang`, and `Goldene Stunde`, case-insensitively. Inside time functions, use canonical names such as `sunrise` or `golden_hour` and 24-hour clock times.

The [time reference](light_configuration.md#scheduled-time) lists all solar times, and the [function reference](light_configuration.md#constraint-functions) explains `min`, `max`, `notBefore`, `notAfter`, `clamp`, `mix`, and `smooth`. Keep scene names short enough to save in the Hue app; compact aliases such as `max` help with longer expressions.

### Options

Separate options with commas. Write the option names below in lowercase; day abbreviations are case-insensitive.

| Option | Meaning | Example scene name |
|---|---|---|
| `i` | Interpolate from the previous scheduled scene's time to this one. | `22:00 [i]` |
| `i:false` | Disable interpolation for this definition, including when `--interpolate-all` is enabled. | `22:00 [i:false]` |
| `gap` | Leave this group's schedule without prescribed light settings until its next definition. | `12:00 [gap]` |
| `tr:<duration>` | Transition after this scene's scheduled time, or when it is reapplied later. | `sunset [tr:10s]` |
| `tr-b:<duration or time>` | Start earlier and reach the saved settings at the scene's scheduled time. | `07:00 [tr-b:30min]` |
| Days or day ranges | Restrict the schedule to those weekdays. | `07:00 [Mo-Fr]` |
| `on` | Turn on scene lights, except those explicitly saved as off. | `sunset [on]` |
| `off` | Turn off all scene lights. | `23:00 [off,tr:5min]` |
| `f` | Apply despite manual changes or a missing required synced-scene activation. | `23:00 [off,f]` |

Options can be combined: `07:00 [Mo-Fr,on,tr:10s]` turns on the scene on weekday mornings with a ten-second transition. `12:00 [Mo-Th,Su,i]` interpolates toward the scene on Monday through Thursday and Sunday.

For days, use `Mo`, `Tu`, `We`, `Th`, `Fr`, `Sa`, and `Su`. Three-letter English forms and German `Di`, `Mi`, `Do`, and `So` also work. Ranges include both endpoints and can cross the weekend: `Fr-Mo` means Friday through Monday. Day entries can appear anywhere in the option list; omitting them means every day.

`tr` and `tr-b` use duration strings such as `10s`, `30min`, or `1h20min`. A bare duration number is in **100 ms units**, so `tr:4` is 400 ms. For a specific early start, use a canonical clock or solar time, such as `07:00 [tr-b:06:30]` or `sunrise [tr-b:civil_dawn]`.

Interpolation and early transitions need consecutive settings that can change gradually. See [transitions and interpolation](light_configuration.md#transitions--interpolations) for timing and turn-on behavior. `--interpolate-all` supplies the default for both scene schedules and text-file definitions; `[i:false]` disables it for an individual scene. An explicit `tr-b:` still takes precedence over automatic interpolation.

Unknown options or invalid values make the whole scene name ineligible for scheduling. For example, `[days:Mo-Fr]`, `[d:Mo-Fr]`, `[interpolate:true]`, and semicolon-separated options are not supported. Time functions also work inside `tr-b:`, including nested expressions such as `20:00 [tr-b:max(min(sunset,19:00),18:00),f]`; commas inside parentheses belong to the function.

### Schedule gaps

Use `08:00` followed by `12:00 [gap]` to stop prescribing this group's light settings at noon. A gap does not switch lights off and interrupts interpolation. Schedules for other overlapping groups or individual lights still apply. Days work normally, for example `12:00 [Mo-Fr,gap]`. Combining `gap` with `on` or `off` is invalid; transition and interpolation options do not give a gap any light settings.

The scheduler ignores a gap scene's saved light actions, including later edits to them. Manually activating the scene in Hue still executes those actions. Migration initializes gap scenes with all lights off, so manually recalling a migrated gap scene switches its lights off. The Hue app may require at least one light saved as on before allowing you to edit such a scene, including renaming it; you can change its saved actions without affecting the scheduled gap.

## Editing a running schedule

- **Change saved light settings:** The schedule reloads the scene's values. If that scene is active and the group is on, the updated settings are reapplied. Gap scenes continue to ignore their saved actions.
- **Rename a scene:** Its schedule changes to the new time and options. Renaming it to an ordinary name removes it from automatic scheduling.
- **Create or delete a scene:** The corresponding schedule is added or removed automatically.
- **Add or remove room or zone lights:** Running schedules refresh their membership. Scene schedules wait for the bridge's scene actions to match the updated group. Recorded manual overrides remain in effect during membership changes.

No restart is needed for these changes. If a running group becomes empty, its schedules remain dormant until a light is added again. A deleted group has its running definitions removed, while other groups continue updating; any file-based configuration remains unchanged. Ordinary scene edits can restore scheduled control; membership updates preserve already-recorded manual overrides.

## Scene schedules, scene references, and Scene Sync

| Feature | What it does | Enable it with |
|---|---|---|
| Scene schedule | Uses a scene's name for the time and its saved settings for the light values. | `--enable-auto-scene-states` |
| Scene reference in a text file | Uses a named scene's light values at a time written in the file, such as `Living room  sunset  scene:Relax`. | The [`scene:` property](light_configuration.md#scene-scheduling) |
| Scene Sync | Maintains a separate scene with the current scheduled values, ready for a sensor or switch to activate. | [`--enable-scene-sync`](advanced_command_line_options.md#--enable-scene-sync) |

The first two features require Hue Bridge. Scene Sync also works with Home Assistant. Enabling automatic scene discovery does not enable Scene Sync, and vice versa.

## Combine scenes and a text file

Pass a file as well as `--enable-auto-scene-states`, or keep `CONFIG_FILE` and its volume mount in Docker. Both sources are loaded. An explicitly configured file must exist and be readable.

This lets you keep individual-light schedules in a file while managing room scenes in the app. [Unscheduled periods](light_configuration.md#faq-how-long-does-a-schedule-entry-apply) can be declared in either source, using property-free file entries or `[gap]` scenes. Avoid defining the same group's schedule twice at the same times.

## Migrate a text-file schedule

`--migrate-input-to-scenes` creates Hue scenes from group definitions in a configuration file, then exits. It requires a Hue Bridge and a readable input file. The input file is left unchanged.

**Migration writes to the bridge. An existing scene with the generated name in the same group is updated.** Keep a copy of your input file and check for naming conflicts first.

```shell
java -jar hue-scheduler.jar <BRIDGE_IP> <ACCESS_TOKEN> --lat=<LATITUDE> --long=<LONGITUDE> input.txt --migrate-input-to-scenes
```

With the text-file Compose setup, run it once in a separate container:

```shell
docker compose run --rm -e MIGRATE_INPUT_TO_SCENES=true hue-scheduler
```

Generated names retain the time and supported options. Days come first, consecutive days become ranges, and daily schedules omit days. For example:

```text
# Input file
Living room  12:00  bri:60%  ct:3000  days:Mo,Tu,We,Th,Su  interpolate:true

# Generated scene name
12:00 [Mo-Th,Su,i]
```

Check the generated scenes before switching over:

- Only group definitions are exported; individual-light definitions are skipped.
- Scenes store resolved per-light settings. They do not retain a live `scene:` reference or its brightness multiplier from the input file.
- Empty definitions become `[gap]` scenes and preserve unscheduled periods. Their saved off actions are used only when the scene is manually recalled, as described under [schedule gaps](#schedule-gaps).
- Explicit `interpolate:false` becomes `[i:false]`, preserving the choice even with `--interpolate-all` enabled.
- Long time expressions and option lists may exceed the scene-name length accepted by the bridge.

To use the result, start normally with `--enable-auto-scene-states` and without the migration flag. Remove migrated definitions from the file, or omit the file entirely if everything you need is now in scenes. Keep the original file as a backup.
