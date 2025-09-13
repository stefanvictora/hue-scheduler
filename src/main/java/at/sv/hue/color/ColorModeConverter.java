package at.sv.hue.color;

import at.sv.hue.ColorMode;
import at.sv.hue.Gradient;
import at.sv.hue.Pair;
import at.sv.hue.api.PutCall;

import java.util.List;

import static at.sv.hue.ColorMode.*;

public final class ColorModeConverter {

    private ColorModeConverter() {
    }

    public static void convertIfNeeded(PutCall putCall, ColorMode target) {
        ColorMode source = putCall.getColorMode();
        if (source == CT && target == XY) {
            int[] rgb = convertCtToRgb(putCall);
            setXYFromRgb(putCall, rgb);
        } else if (source == XY && target == CT) {
            setCtFromXY(putCall);
        } else if (source == GRADIENT && target == XY) {
            gradientToXY(putCall);
        } else if (source == XY && target == GRADIENT) {
            Pair<Double, Double> point = Pair.of(putCall.getX(), putCall.getY());
            putCall.setGradient(Gradient.builder()
                                        .points(List.of(point, point))
                                        .build());
            putCall.setX(null);
            putCall.setY(null);
        } else if (source == GRADIENT && target == CT) {
            gradientToXY(putCall);
            convertIfNeeded(putCall, target);
        }
    }

    private static void gradientToXY(PutCall putCall) {
        Pair<Double, Double> firstPoint = putCall.getGradient().points().getFirst();
        putCall.setGradient(null);
        putCall.setX(firstPoint.first());
        putCall.setY(firstPoint.second());
    }

    private static int[] convertCtToRgb(PutCall putCall) {
        int[] rgb = CTToRGBConverter.approximateRGBFromMired(putCall.getCt());
        putCall.setCt(null);
        return rgb;
    }

    private static void setXYFromRgb(PutCall putCall, int[] rgb) {
        XYColor xyColor = RGBToXYConverter.rgbToXY(rgb[0], rgb[1], rgb[2], putCall.getGamut());
        putCall.setX(xyColor.x());
        putCall.setY(xyColor.y());
    }

    /**
     * Use McCamy's approximation to set the ct in mired from XY
     */
    private static void setCtFromXY(PutCall putCall) {
        XYColorGamutCorrection correction = new XYColorGamutCorrection(putCall.getX(), putCall.getY(), putCall.getGamut());
        double n = (correction.getX() - 0.3320) / (0.1858 - correction.getY());
        int mired = (int) (1_000_000.0 / (437 * Math.pow(n, 3) + 3601 * Math.pow(n, 2) + 6861 * n + 5517));
        putCall.setX(null);
        putCall.setY(null);
        putCall.setCt(mired);
    }
}
