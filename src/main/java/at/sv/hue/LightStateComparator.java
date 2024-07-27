package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.color.ColorComparator;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LightStateComparator {

    private final PutCall lastPutCall;
    private final LightState currentState;

    public boolean lightStateDiffers() {
        if (currentState.isOff() && !isOnAndStateDiffers()) {
            return false; // don't compare state if light is off, unless it's an on state
        }
        return isOnAndStateDiffers() ||
               brightnessDiffers() ||
               effectDiffers() ||
               colorModeOrValuesDiffer();
    }

    private boolean brightnessDiffers() {
        return lastPutCall.getBri() != null && currentState.isBrightnessSupported() && brightnessIsNotSimilar();
    }

    private boolean brightnessIsNotSimilar() {
        return Math.abs(lastPutCall.getBri() - currentState.getBrightness()) >= 5;
    }

    private boolean colorModeOrValuesDiffer() {
        ColorMode colorMode = lastPutCall.getColorMode();
        if (colorMode == ColorMode.NONE) {
            return false; // if no color mode scheduled, always treat as equal
        }
        if (colorModeNotSupportedByState(colorMode)) {
            return false;
        }
        if (incompatibleColorMode(colorMode)) {
            return true;
        }
        return switch (colorMode) {
            case CT -> ctDiffers();
            case HS -> ColorComparator.colorDiffers(currentState.getX(), currentState.getY(),
                    lastPutCall.getHue(), lastPutCall.getSat());
            case XY -> ColorComparator.colorDiffers(currentState.getX(), currentState.getY(),
                    lastPutCall.getX(), lastPutCall.getY(), currentState.getLightCapabilities().getColorGamut());
            default -> false;
        };
    }

    private boolean colorModeNotSupportedByState(ColorMode colorMode) {
        return colorMode == ColorMode.CT && !currentState.isCtSupported()
               || colorMode == ColorMode.HS && !currentState.isColorSupported()
               || colorMode == ColorMode.XY && !currentState.isColorSupported();
    }

    private boolean incompatibleColorMode(ColorMode colorMode) {
        return colorMode == ColorMode.CT && currentState.getColormode() != ColorMode.CT ||
               currentState.getColormode() == ColorMode.CT && colorMode != ColorMode.CT;
    }

    private boolean ctDiffers() {
        if (currentState.getColorTemperature() == null) {
            return true;
        }
        return colorTemperatureIsNotSimilar();
    }

    private boolean colorTemperatureIsNotSimilar() {
        return Math.abs(getAdjustedLastCt() - currentState.getColorTemperature()) >= 5;
    }

    private int getAdjustedLastCt() {
        LightCapabilities lightCapabilities = currentState.getLightCapabilities();
        Integer currentMin = lightCapabilities.getCtMin();
        Integer currentMax = lightCapabilities.getCtMax();
        if (currentMin != null && currentMax != null) {
            return Math.min(Math.max(lastPutCall.getCt(), currentMin), currentMax);
        } else {
            return lastPutCall.getCt();
        }
    }

    private boolean effectDiffers() {
        String lastEffect = lastPutCall.getEffect();
        if (lastEffect == null) {
            return false; // if no effect scheduled, always treat as equal
        } else if (currentState.getEffect() == null) {
            return !"none".equals(lastEffect); // if effect scheduled, but none set, only consider "none" to be equal
        } else {
            return !lastEffect.equals(currentState.getEffect()); // otherwise, effects have to be exactly the same
        }
    }

    /**
     * We only detect any changes if the state itself has "on:true". This is specifically meant for detecting turning
     * off lights inside a group, when the "on:true" state is enforced via the state.
     */
    private boolean isOnAndStateDiffers() {
        return lastPutCall.getOn() != null && currentState.isOnOffSupported() && lastPutCall.isOn() && !currentState.isOn();
    }
}
