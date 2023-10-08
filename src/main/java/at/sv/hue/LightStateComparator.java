package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.color.XYColorGamutCorrection;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

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
               colorModeOrValuesDiffer() ||
               effectDiffers();
    }

    private boolean brightnessDiffers() {
        return lastPutCall.getBri() != null && currentState.isBrightnessSupported() &&
               !lastPutCall.getBri().equals(currentState.getBrightness());
    }

    private boolean colorModeOrValuesDiffer() {
        ColorMode colorMode = lastPutCall.getColorMode();
        if (colorMode == ColorMode.NONE) {
            return false; // if no color mode scheduled, always treat as equal
        }
        if (colorModeNotSupportedByState(colorMode)) {
            return false;
        }
        if (colorModeDiffers(colorMode)) {
            return true;
        }
        return switch (colorMode) {
            case CT -> ctDiffers();
            case HS -> lastPutCall.getHue() != null && !lastPutCall.getHue().equals(currentState.getHue()) ||
                       lastPutCall.getSat() != null && !lastPutCall.getSat().equals(currentState.getSat());
            case XY -> xyDiffers();
            default -> false; // should not happen, but as a fallback we just ignore unknown color modes
        };
    }

    private boolean colorModeNotSupportedByState(ColorMode colorMode) {
        return colorMode == ColorMode.CT && !currentState.isCtSupported()
               || colorMode == ColorMode.HS && !currentState.isColorSupported()
               || colorMode == ColorMode.XY && !currentState.isColorSupported();
    }

    private boolean colorModeDiffers(ColorMode colorMode) {
        return !Objects.equals(colorMode, currentState.getColormode());
    }

    private boolean ctDiffers() {
        if (currentState.getColorTemperature() == null) {
            return true;
        }
        return getAdjustedLastCt() != currentState.getColorTemperature();
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

    private boolean xyDiffers() {
        if (currentState.getX() == null) {
            return true;
        }
        XY current = getAdjustedXY(currentState.getX(), currentState.getY());
        XY adjustedLast = getAdjustedXY(lastPutCall.getX(), lastPutCall.getY());
        return doubleDiffers(adjustedLast.x, current.x) || doubleDiffers(adjustedLast.y, current.y);
    }

    private XY getAdjustedXY(double x, double y) {
        Double[][] gamut = currentState.getLightCapabilities().getColorGamut();
        if (gamut == null) {
            return new XY(x, y);
        }
        XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, gamut);
        return new XY(correction.getX(), correction.getY());
    }

    private static boolean doubleDiffers(double last, double current) {
        return getBigDecimal(last).setScale(2, RoundingMode.HALF_UP)
                                  .compareTo(getBigDecimal(current).setScale(2, RoundingMode.HALF_UP)) != 0;
    }

    private static BigDecimal getBigDecimal(Double value) {
        return new BigDecimal(value.toString()).setScale(3, RoundingMode.HALF_UP);
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

    @RequiredArgsConstructor
    private static final class XY {
        public final double x;
        public final double y;
    }
}
