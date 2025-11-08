package at.sv.hue.api;

import at.sv.hue.ColorMode;
import at.sv.hue.Effect;
import at.sv.hue.Gradient;
import at.sv.hue.Pair;
import at.sv.hue.FormatUtil;
import at.sv.hue.color.ColorComparator;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Data
@Builder(toBuilder = true)
public final class PutCall {
    String id;
    Integer bri;
    Integer ct;
    Double x;
    Double y;
    Boolean on;
    Effect effect;
    Gradient gradient;
    Integer transitionTime;
    @EqualsAndHashCode.Exclude
    Double[][] gamut;

    public boolean isNullCall() {
        return Stream.of(bri, ct, x, y, on, effect, gradient).allMatch(Objects::isNull);
    }

    public ColorMode getColorMode() {
        if (ct != null) {
            return ColorMode.CT;
        } else if (getXY() != null) {
            return ColorMode.XY;
        } else if (gradient != null) {
            return ColorMode.GRADIENT;
        }
        return ColorMode.NONE;
    }

    public boolean isOn() {
        return on == Boolean.TRUE;
    }

    public boolean isOff() {
        return on == Boolean.FALSE;
    }

    public void resetOn() {
        this.on = null;
    }

    public Pair<Double, Double> getXY() {
        if (x == null || y == null) {
            return null;
        }
        return Pair.of(x, y);
    }

    @Override
    public String toString() {
        return "{" +
               "id=" + id +
               getFormattedPropertyIfSet("on", on) +
               getFormattedBriIfSet() +
               getFormattedCtIfSet() +
               getFormattedPropertyIfSet("x", x) +
               getFormattedPropertyIfSet("y", y) +
               getFormattedPropertyIfSet("effect", effect) +
               getFormattedPropertyIfSet("gradient", gradient) +
               getFormattedTransitionTimeIfSet() +
               "}";
    }

    private String getFormattedPropertyIfSet(String name, Object property) {
        if (property == null) return "";
        return formatPropertyName(name) + property;
    }

    private String getFormattedBriIfSet() {
        if (bri == null) return "";
        return formatPropertyName("bri") + bri + " (" + FormatUtil.formatBrightnessPercent(bri) + "%)";
    }

    private String getFormattedCtIfSet() {
        if (ct == null) return "";
        long kelvin = Math.round(1_000_000.0 / ct);
        return formatPropertyName("ct") + ct + " (" + kelvin + "K)";
    }

    private String formatPropertyName(String name) {
        return ", " + name + "=";
    }

    private String getFormattedTransitionTimeIfSet() {
        if (transitionTime == null) return "";
        return formatPropertyName("tr") + transitionTime + " (" + Duration.ofMillis(transitionTime * 100L) + ")";
    }

    public boolean hasSameLightState(PutCall other) {
        return Objects.equals(this.on, other.on) && // todo: mutation coverage
               Objects.equals(this.bri, other.bri) &&
               Objects.equals(this.effect, other.effect) &&
               Objects.equals(this.gradient, other.gradient) &&
               Objects.equals(this.x, other.x) &&
               Objects.equals(this.y, other.y) &&
               Objects.equals(this.ct, other.ct);
    }

    public boolean hasNotSimilarLightState(PutCall other, int brightnessThreshold,
                                            int colorTemperatureThresholdKelvin,
                                            double colorThreshold) {
        return !Objects.equals(this.on, other.on) ||
               brightnessIsNotSimilar(this.bri, other.bri, brightnessThreshold) ||
               !Objects.equals(this.effect, other.effect) ||
               colorIsNotSimilar(this, other, colorThreshold) ||
               gradientIsNotSimilar(this, other, colorThreshold) ||
               ctIsNotSimilar(this.ct, other.ct, colorTemperatureThresholdKelvin);
    }

    private boolean brightnessIsNotSimilar(Integer bri1, Integer bri2, int threshold) {
        if (Objects.equals(bri1, bri2)) {
            return false;
        }
        if (bri1 == null || bri2 == null) {
            return true;
        }
        return Math.abs(bri1 - bri2) >= threshold;
    }

    private boolean colorIsNotSimilar(PutCall call1, PutCall call2, double threshold) {
        // Only compare if both have x/y values set
        if (call1.x == null || call1.y == null || call2.x == null || call2.y == null) {
            return !Objects.equals(call1.x, call2.x) || !Objects.equals(call1.y, call2.y);
        }
        return ColorComparator.colorDiffers(
                call1.x, call1.y, call2.x, call2.y, call1.gamut, threshold);
    }

    private boolean gradientIsNotSimilar(PutCall putCall, PutCall other, double colorThreshold) {
        if (putCall.gradient == null && other.gradient == null) {
            return false;
        }
        if (putCall.gradient == null || other.gradient == null) {
            return true;
        }
        List<Pair<Double, Double>> points = putCall.gradient.points();
        List<Pair<Double, Double>> otherPoints = other.gradient.points();
        for (int i = 0; i < otherPoints.size(); i++) {
            Pair<Double, Double> point = points.get(i);
            Pair<Double, Double> otherPoint = otherPoints.get(i);
            if (ColorComparator.colorDiffers(point.first(), point.second(),
                    otherPoint.first(), otherPoint.second(), putCall.gamut, colorThreshold)) {
                return true;
            }
        }
        return false;
    }

    private boolean ctIsNotSimilar(Integer ct1, Integer ct2, int thresholdKelvin) {
        if (Objects.equals(ct1, ct2)) {
            return false;
        }
        if (ct1 == null || ct2 == null) {
            return true;
        }
        int kelvin1 = convertToKelvin(ct1);
        int kelvin2 = convertToKelvin(ct2);
        return Math.abs(kelvin1 - kelvin2) >= thresholdKelvin;
    }

    private int convertToKelvin(int mired) {
        return 1_000_000 / mired;
    }
}
