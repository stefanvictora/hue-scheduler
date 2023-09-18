package at.sv.hue.color;

import at.sv.hue.ColorMode;
import at.sv.hue.api.PutCall;

import static at.sv.hue.ColorMode.*;

public final class ColorModeConverter {

    private ColorModeConverter() {
    }

    public static void convertIfNeeded(PutCall putCall, Double[][] colorGamut, ColorMode source, ColorMode target) {
        if (source == CT && target == HS) {
            int[] rgb = convertCtToRgb(putCall);
            setHueAndSatFromRgb(rgb[0], rgb[1], rgb[2], putCall);
        } else if (source == HS && target == XY) {
            int[] rgb = convertHueAndSatToRgb(putCall);
            setXYFromRgb(putCall, colorGamut, rgb);
        } else if (source == CT && target == XY) {
            int[] rgb = convertCtToRgb(putCall);
            setXYFromRgb(putCall, colorGamut, rgb);
        } else if (source == XY && target == HS) {
            int[] rgb = convertXYToRgb(putCall, colorGamut);
            setHueAndSatFromRgb(rgb[0], rgb[1], rgb[2], putCall);
        } else if (source == XY && target == CT) {
            int[] rgb = convertXYToRgb(putCall, colorGamut);
            setCtFromRgb(putCall, rgb);
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

    private static int[] convertXYToRgb(PutCall putCall, Double[][] colorGamut) {
        int[] rgb = RGBToXYConverter.convert(putCall.getX(), putCall.getY(), 255, colorGamut);
        putCall.setX(null);
        putCall.setY(null);
        return rgb;
    }

    private static void setHueAndSatFromRgb(int red, int green, int blue, PutCall putCall) {
        int[] hsv = RGBToHSVConverter.rgbToHsv(red, green, blue);
        putCall.setHue(hsv[0]);
        putCall.setSat(hsv[1]);
    }

    private static void setXYFromRgb(PutCall putCall, Double[][] colorGamut, int[] rgb) {
        RGBToXYConverter.XYColor xyColor = RGBToXYConverter.convert(rgb[0], rgb[1], rgb[2], colorGamut);
        putCall.setX(xyColor.getX());
        putCall.setY(xyColor.getY());
    }

    private static void setCtFromRgb(PutCall putCall, int[] rgb) {
        int mired = CTToRGBConverter.approximateMiredFromRGB(rgb[0], rgb[1], rgb[2]);
        putCall.setCt(mired);
    }
}
