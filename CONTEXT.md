# Hue Scheduler

Hue Scheduler applies time-based lighting states, including schedules discovered from Hue scene names.

## Language

**Schedule Expression**:
A declaration of when a lighting state begins, using a fixed time, solar event, offset, or supported time function. The same expression language applies regardless of where the schedule is declared.
_Avoid_: Time string, start string

**Day Restriction**:
An optional constraint limiting the days of the week on which a schedule is eligible to run.
_Avoid_: Weekday flag, days flag

**Scene Schedule**:
A Hue scene whose name contains a schedule expression that the scheduler can evaluate. A scene name with an invalid expression is not a Scene Schedule.
_Avoid_: Scheduled scene, parseable scene

**Scene Schedule Candidate**:
A Hue scene whose name has the recognizable form of a Scene Schedule but contains an invalid schedule expression or flag. It is rejected with a diagnostic rather than treated as an ordinary scene.
_Avoid_: Broken schedule, malformed scene

**Scene Recall**:
Applying the complete stored action set of a Hue scene. A Scene Recall is equivalent to a requested state only when their action targets and payloads match, regardless of ordering.
_Avoid_: Scene update, scene application
