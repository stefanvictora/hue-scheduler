package at.sv.hue.color;

import at.sv.hue.ColorMode;
import at.sv.hue.api.PutCall;

import static at.sv.hue.ColorMode.*;

public final class ColorModeConverter {

    private ColorModeConverter() {
    }

    public static void convertIfNeeded(PutCall putCall, ColorMode target) {
        ColorMode source = putCall.getColorMode();
        if (source == CT && target == HS) {
            int[] rgb = convertCtToRgb(putCall);
            setHueAndSatFromRgb(putCall, rgb);
        } else if (source == HS && target == XY) {
            int[] rgb = convertHueAndSatToRgb(putCall);
            setXYFromRgb(putCall, rgb);
        } else if (source == CT && target == XY) {
            int[] rgb = convertCtToRgb(putCall);
            setXYFromRgb(putCall, rgb);
        } else if (source == XY && target == HS) {
            int[] rgb = convertXYToRgb(putCall);
            setHueAndSatFromRgb(putCall, rgb);
        } else if (source == XY && target == CT) {
            setCtFromXY(putCall);
        } else if (source == HS && target == CT) {
            int[] rgb = convertHueAndSatToRgb(putCall);
            setCtFromRgb(putCall, rgb);
        }
    }

    private static int[] convertCtToRgb(PutCall putCall) {
        int[] rgb = CTToRGBConverter.approximateRGBFromMired(putCall.getCt());
        putCall.setCt(null);
        return rgb;
    }

    private static int[] convertHueAndSatToRgb(PutCall putCall) {
        int[] rgb = RGBToHSVConverter.hsvToRgb(putCall.getHue(), putCall.getSat(), 254);
        putCall.setHue(null);
        putCall.setSat(null);
        return rgb;
    }

    private static int[] convertXYToRgb(PutCall putCall) {
        int[] rgb = RGBToXYConverter.xyToRgb(putCall.getX(), putCall.getY(), 255, putCall.getGamut());
        putCall.setX(null);
        putCall.setY(null);
        return rgb;
    }

    private static void setHueAndSatFromRgb(PutCall putCall, int[] rgb) {
        int[] hsv = RGBToHSVConverter.rgbToHsv(rgb[0], rgb[1], rgb[2]);
        putCall.setHue(hsv[0]);
        putCall.setSat(hsv[1]);
    }

    private static void setXYFromRgb(PutCall putCall, int[] rgb) {
        RGBToXYConverter.XYColor xyColor = RGBToXYConverter.rgbToXY(rgb[0], rgb[1], rgb[2], putCall.getGamut());
        putCall.setX(xyColor.x());
        putCall.setY(xyColor.y());
    }

    private static void setCtFromRgb(PutCall putCall, int[] rgb) {
        int mired = CTToRGBConverter.approximateMiredFromRGB(rgb[0], rgb[1], rgb[2]);
        putCall.setCt(mired);
    }

    /**
     * Use McCamy's approximation to set the ct in mired from XY
     */
    private static void setCtFromXY(PutCall putCall) {
        double n = (putCall.getX() - 0.3320) / (0.1858 - putCall.getY());
        int mired = (int) (1_000_000.0 / (437 * Math.pow(n, 3) + 3601 * Math.pow(n, 2) + 6861 * n + 5517));
        putCall.setX(null);
        putCall.setY(null);
        putCall.setCt(mired);
    }
}
