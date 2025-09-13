package at.sv.hue;

import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.color.XYColorGamutCorrection;

import java.util.List;

public final class ScheduledLightStateValidator {

    private final Identifier identifier;
    private final boolean groupState;
    private final LightCapabilities capabilities;

    private final Integer brightness;
    private final Integer ct;
    private final Double x;
    private final Double y;
    private final Boolean on;
    private final String effect;
    private final Gradient gradient;

    public ScheduledLightStateValidator(Identifier identifier, boolean groupState, LightCapabilities capabilities,
                                        Integer brightness, Integer ct, Double x, Double y, Boolean on, String effect,
                                        Gradient gradient) {
        this.identifier = identifier;
        this.groupState = groupState;
        this.capabilities = capabilities;
        this.brightness = assertValidBrightnessValue(brightness);
        this.ct = assertCtSupportAndValue(ct);
        this.on = on;
        this.effect = assertValidEffectValue(effect);
        assertValidXyPair(x, y);
        if (x != null && y != null) {
            assertColorCapabilities();
            var xy = assertAndCorrectXYValue(x, y);
            this.x = xy.first();
            this.y = xy.second();
        } else {
            this.x = null;
            this.y = null;
        }
        this.gradient = assertValidGradientValue(gradient);
    }

    public ScheduledLightState getScheduledLightState() {
        if (isEffectState()) {
            return ScheduledLightState.builder()
                                      .id(identifier.id())
                                      .bri(brightness)
                                      .on(on)
                                      .effect(Effect.builder()
                                                    .effect(effect)
                                                    .ct(ct)
                                                    .x(x)
                                                    .y(y)
                                                    .build())
                                      .build();
        } else {
            return ScheduledLightState.builder()
                                      .id(identifier.id())
                                      .bri(brightness)
                                      .ct(ct)
                                      .x(x)
                                      .y(y)
                                      .on(on)
                                      .effect(effect)
                                      .gradient(gradient)
                                      .gamut(capabilities.getColorGamut())
                                      .build();
        }
    }

    private boolean isEffectState() {
        return effect != null && !"none".equals(effect);
    }

    private String assertValidEffectValue(String effect) {
        if (effect == null) {
            return null;
        }
        if (groupState) {
            throw new InvalidPropertyValue("Effects are not supported by groups.");
        }
        List<String> supportedEffects = capabilities.getEffects();
        if (supportedEffects == null) {
            throw new InvalidPropertyValue("Light does not support any effects.");
        }
        if (effect.equals("none")) {
            return effect;
        }
        if (!supportedEffects.contains(effect)) {
            throw new InvalidPropertyValue("Unsupported value for effect property: '" + effect + "'." +
                                           " Supported effects: " + supportedEffects);
        }
        return effect;
    }

    private Integer assertValidBrightnessValue(Integer brightness) {
        if (brightness == null) {
            return null;
        }
        assertBrightnessSupported();
        return brightness;
    }

    private void assertBrightnessSupported() {
        if (!capabilities.isBrightnessSupported()) {
            throw new BrightnessNotSupported(getFormattedName() + " does not support setting brightness! "
                                             + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private Integer assertCtSupportAndValue(Integer ct) {
        if (ct == null) {
            return null;
        }
        assertCtSupported();
        if (capabilities.getCtMax() == null || capabilities.getCtMin() == null) {
            return ct;
        }
        if (ct > capabilities.getCtMax()) {
            return capabilities.getCtMax();
        }
        if (ct < capabilities.getCtMin()) {
            return capabilities.getCtMin();
        }
        return ct;
    }

    private void assertCtSupported() {
        if (!capabilities.isCtSupported()) {
            throw new ColorTemperatureNotSupported(getFormattedName() + " does not support setting color temperature! "
                                                   + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private void assertValidXyPair(Double x, Double y) {
        boolean onlyOneIsNull = (x == null) ^ (y == null);
        if (onlyOneIsNull) {
            throw new InvalidPropertyValue("x and y must be provided together.");
        }
    }

    private void assertColorCapabilities() {
        if (!capabilities.isColorSupported()) {
            throw new ColorNotSupported(getFormattedName() + " does not support setting color! "
                                        + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private Pair<Double, Double> assertAndCorrectXYValue(Double x, Double y) {
        if (x < 0 || y <= 0 || !(x + y <= 1)) {
            throw new InvalidXAndYValue("Invalid xy chromaticity: x=" + x + ", y=" + y + ". Allowed range: 0 <= x <= 1, 0 < y <= 1, x + y <= 1");
        }
        XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, capabilities.getColorGamut());
        return Pair.of(correction.getX(), correction.getY());
    }

    private Gradient assertValidGradientValue(Gradient gradient) {
        if (gradient != null) {
            assertGradientCapabilities();
            assertValidNumberOfGradientPoints(gradient);
            assertValidGradientMode(gradient);
            var points = gradient.points().stream()
                                 .map(point -> {
                                     var xy = assertAndCorrectXYValue(point.first(), point.second());
                                     return Pair.of(xy.first(), xy.second());
                                 })
                                 .toList();
            return gradient.toBuilder().points(points).build();
        }
        return null;
    }

    private void assertGradientCapabilities() {
        if (!capabilities.isGradientSupported()) {
            throw new InvalidPropertyValue(getFormattedName() + " does not support setting gradient! "
                                           + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private void assertValidNumberOfGradientPoints(Gradient gradient) {
        if (gradient.points().size() < 2) {
            throw new InvalidPropertyValue("Invalid gradient '" + gradient +
                                           "'. A gradient must contain at least two colors.");
        }
        if (gradient.points().size() > capabilities.getMaxGradientPoints()) {
            throw new InvalidPropertyValue("Invalid gradient '" + gradient +
                                           "'. The maximum number of gradient points for this light is " +
                                           capabilities.getMaxGradientPoints() + ".");
        }
    }

    private void assertValidGradientMode(Gradient gradient) {
        if (gradient.mode() == null) {
            return;
        }
        List<String> supportedModes = capabilities.getGradientModes();
        if (supportedModes == null || !supportedModes.contains(gradient.mode())) {
            throw new InvalidPropertyValue("Unsupported gradient mode: '" + gradient.mode() + "'." +
                                           " Supported modes: " + supportedModes);
        }
    }

    private String getFormattedName() {
        if (groupState) {
            return "Group '" + identifier.name() + "'";
        }
        return "Light '" + identifier.name() + "'";
    }
}
