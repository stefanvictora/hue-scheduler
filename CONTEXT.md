# Hue Scheduler

Hue Scheduler governs lighting Targets through time-based State Definitions, including definitions discovered from Hue scene names.

## Language

### Scheduling

**Target**:
A light, group, or other supported entity governed by one or more State Definitions.
_Avoid_: Resource, entity, ID

**State Definition**:
A declaration associating a Target and Target Time with zero or more desired light properties, optionally subject to a Day Restriction. A definition with no desired properties creates a Schedule Gap.
_Avoid_: Scheduled state, schedule row, schedule entry

**Schedule Expression**:
An expression that resolves a Target Time using a fixed time, solar event, offset, or supported time function. The same expression language applies regardless of where the State Definition is declared.
_Avoid_: Time string, start string

**Target Time**:
The time at which a State Definition's desired properties are intended to be reached. A Pre-Transition may begin before it, while a Transition may continue after it.
_Avoid_: Defined start, trigger time, start time

**Day Restriction**:
An optional constraint limiting the days of the week on which a State Definition is eligible to govern its Target.
_Avoid_: Weekday flag, days flag

**State Interval**:
The period during which a State Definition governs a Target. It begins at the start of a Pre-Transition when present, otherwise at the Target Time, and ends when the next applicable State Definition begins or its Day Restriction ceases to permit it.
_Avoid_: Active state, dynamic interval

**Schedule Gap**:
An interval deliberately created by a State Definition with no desired properties, during which the scheduler prescribes no properties for its Target.
_Avoid_: Null state, empty state, pause state

**Effective Properties**:
The desired properties applicable to an individual light at a particular moment after resolving State Definitions, Interpolation, and overlapping Targets.
_Avoid_: Full picture, payload, resolved state

**Schedule Precedence**:
The rule that conflicting properties from a more specific Target take priority: individual lights over groups, and smaller groups over larger groups. Precedence between equally specific overlapping groups is undefined and such conflicts should be avoided.
_Avoid_: Override order, group ordering

### Transitions

**Transition**:
A progression toward a State Definition's desired properties that begins whenever the definition is applied at or after its Target Time. It is used again whenever the definition is reapplied, including after a later turn-on.
_Avoid_: Fade, interpolation

**Pre-Transition**:
A progression from the preceding Effective Properties toward a State Definition's desired properties that begins before and completes at its Target Time. It is available only before the Target Time and never resumes afterward.
_Avoid_: tr-before, fade-before, early transition

**Interpolation**:
A Pre-Transition spanning from the preceding State Definition's Target Time to the current State Definition's Target Time. It occurs only when the definitions meet Interpolation Eligibility and no Schedule Gap separates them, and it may span days.
_Avoid_: Adaptive lighting, circadian transition, fade

**Interpolation Eligibility**:
The condition that consecutive State Definitions share at least one continuously variable property with different desired values. Eligibility permits a Pre-Transition and can move the beginning of the latter State Interval before its Target Time.
_Avoid_: Property overlap, payload-only difference

### Scenes and control

**Scene Schedule**:
A Hue scene whose name contains a valid Schedule Expression and optional schedule flags. A name that cannot be parsed is treated as an ordinary scene name.
_Avoid_: Scheduled scene, parseable scene

**Scene-Backed Definition**:
A State Definition whose per-light desired properties come from a Hue scene's current stored actions. Later changes to those actions change the definition.
_Avoid_: Scene scheduling, scene state

**Scene Recall**:
Applying the complete stored action set of a Hue scene. A Scene Recall is equivalent to applying the same actions directly when their targets and payloads match, regardless of ordering.
_Avoid_: Scene update, scene application

**Synced Scene**:
A scene kept aligned with a Target's Effective Properties so external controls can activate them immediately.
_Avoid_: Scheduler scene, mirrored scene, sync scene

**Synced Scene Turn-On**:
Activation of a Synced Scene whose stored actions include an `on` action for a Target. It restores Scheduler Control for that Target immediately, without waiting for the bridge to confirm the light state.
_Avoid_: Synced scene engagement, confirmed light-on

**Scheduler Control**:
The condition in which ordinary State Definitions may govern a Target. A Manual Override suspends control; turning the Target off and on normally restores it, while configurations requiring scene activation require a qualifying Synced Scene Turn-On.
_Avoid_: Scheduler ownership, engagement flag

**Manual Override**:
A significant divergence between a Target's observed properties and its Effective Properties that is not explained by another State Definition. It suspends Scheduler Control until the relevant off/on or Synced Scene Turn-On cycle.
_Avoid_: User modification, manual change, changed flag

**Forced Definition**:
A State Definition that remains applicable despite a Manual Override or a missing Synced Scene Turn-On. If it prescribes `on` or `off`, it actively preserves that power condition throughout its State Interval.
_Avoid_: Force flag, enforced state

**Off-Light Update**:
A scheduled property update sent while a light remains off, so its next turn-on begins with its Effective Properties.
_Avoid_: Background update, standby update
