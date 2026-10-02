package at.sv.hue;

import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.SceneNotFoundException;
import at.sv.hue.time.StartTimeProvider;
import lombok.RequiredArgsConstructor;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;

/** Builds definitions from scene names and bridge data, without changing the running schedule. */
@RequiredArgsConstructor
final class SceneStateFactory {
    private final HueApi api;
    private final StartTimeProvider startTimeProvider;
    private final int minTrBeforeGapInMinutes;
    private final int brightnessOverrideThreshold;
    private final int colorTemperatureOverrideThresholdKelvin;
    private final double colorOverrideThreshold;
    private final boolean interpolateAll;

    /** Returns null when the scene is absent or its name does not declare a schedule. */
    ScheduledState create(Identifier scene) {
        if (scene == null) {
            return null;
        }
        SceneNameParser.ParseResult schedule = SceneNameParser.parse(scene.name(), startTimeProvider);
        if (schedule == null) {
            return null;
        }
        Identifier group = api.getGroupIdForScene(scene.id());
        if (group == null) {
            throw new SceneNotFoundException("No scene with ID '" + scene.id() + "' found");
        }
        List<ScheduledLightState> lightStates = schedule.gap()
                ? List.of(ScheduledLightState.builder().id(group.id()).build())
                : api.getSceneLightStates(scene.id());

        return ScheduledState.builder()
                .identifier(group)
                .startString(schedule.timeExpression())
                .lightStates(InputConfigurationParser.applyOnModifier(lightStates, schedule.on()))
                .groupLightIds(getGroupLights(group.id()))
                .sceneId(scene.id())
                .sceneOnModifier(schedule.on())
                .transitionTimeBeforeString(schedule.transitionTimeBefore())
                .definedTransitionTime(parseTransitionTime(schedule.transitionTime()))
                .daysOfWeek(parseDaysOfWeek(schedule.daysOfWeek()))
                .startTimeProvider(startTimeProvider)
                .minTrBeforeGapInMinutes(minTrBeforeGapInMinutes)
                .brightnessOverrideThreshold(brightnessOverrideThreshold)
                .colorTemperatureOverrideThresholdKelvin(colorTemperatureOverrideThresholdKelvin)
                .colorOverrideThreshold(colorOverrideThreshold)
                .force(schedule.forced())
                .interpolate(resolveInterpolation(schedule.interpolate()))
                .groupState(true)
                .sceneScheduleGap(schedule.gap())
                .build();
    }

    private List<String> getGroupLights(String groupId) {
        try {
            return api.getGroupLights(groupId);
        } catch (EmptyGroupException e) {
            return List.of();
        }
    }

    private Boolean resolveInterpolation(Boolean explicitValue) {
        if (explicitValue != null) {
            return explicitValue;
        }
        return interpolateAll ? Boolean.TRUE : null;
    }

    private static EnumSet<DayOfWeek> parseDaysOfWeek(String expression) {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (expression != null) {
            DayOfWeeksParser.parseDayOfWeeks(expression, days);
        }
        return days;
    }

    private static Integer parseTransitionTime(String expression) {
        return expression == null ? null : InputConfigurationParser.parseTransitionTime("transition-time", expression);
    }
}
