package at.sv.hue;

import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;

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


    public ScheduledLightStateValidator(Identifier identifier, boolean groupState, LightCapabilities capabilities,
                                        Integer brightness, Integer ct, Double x, Double y, Boolean on, String effect) {
        this.identifier = identifier;
        this.groupState = groupState;
        this.capabilities = capabilities;
        this.brightness = assertValidBrightnessValue(brightness);
        this.ct = assertCtSupportAndValue(ct);
        this.x = assertValidXAndY(x);
        this.y = assertValidXAndY(y);
        this.on = on;
        this.effect = assertValidEffectValue(effect);
        assertValidXyPair();
        assertColorCapabilities();
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

    private Double assertValidXAndY(Double xOrY) {
        if (xOrY != null && (xOrY > 1 || xOrY < 0)) {
            throw new InvalidXAndYValue("Invalid x or y value '" + xOrY + "'. Allowed double range: 0-1");
        }
        return xOrY;
    }

    private void assertValidXyPair() {
        if ((x == null) != (y == null)) {
            throw new InvalidPropertyValue("x and y must be provided together.");
        }
    }

    private void assertColorCapabilities() {
        if (isColorState() && !capabilities.isColorSupported()) {
            throw new ColorNotSupported(getFormattedName() + " does not support setting color! "
                                        + "Capabilities: " + capabilities.getCapabilities());
        }
    }

    private boolean isColorState() {
        return x != null || y != null;
    }

    private String getFormattedName() {
        if (groupState) {
            return "Group '" + identifier.name() + "'";
        }
        return "Light '" + identifier.name() + "'";
    }
}
