package at.sv.hue;

import at.sv.hue.api.PutCall;
import at.sv.hue.color.ColorTemperatureToRGBConverter;
import at.sv.hue.color.RGBToHSVConverter;
import at.sv.hue.color.RGBToXYConverter;

public class ColorModeConverter {

    public static void convertIfNeeded(PutCall putCall, Double[][] colorGamut, ColorMode source, ColorMode target) {
        if (source == ColorMode.CT && target == ColorMode.HS) {
            // CT -> HS = CT -> RGB -> HS
            int[] rgb = convertMiredToRGB(putCall);
            convertRGBToHSV(rgb[0], rgb[1], rgb[2], putCall);
        } else if (source == ColorMode.HS && target == ColorMode.XY) {
            // HS -> XY = HS -> RGB -> XY
            int[] rgb = convertHSVToRGB(putCall);
            convertRGBToXY(putCall, colorGamut, rgb);
        } else if (source == ColorMode.CT && target == ColorMode.XY) {
            // CT -> XY = CT -> RGB -> XY
            int[] rgb = convertMiredToRGB(putCall);
            convertRGBToXY(putCall, colorGamut, rgb);
        } else if (source == ColorMode.XY && target == ColorMode.HS) {
            // XY -> HS = XY -> RGB -> HS
            int[] rgb = convertXYToRGB(putCall, colorGamut);
            convertRGBToHSV(rgb[0], rgb[1], rgb[2], putCall);
        } else if (source == ColorMode.XY && target == ColorMode.CT) {
            // XY -> CT = XY -> RGB -> CT
            int[] rgb = convertXYToRGB(putCall, colorGamut);
            convertCTToRGB(putCall, rgb);
        } else if (source == ColorMode.HS && target == ColorMode.CT) {
            // HS -> CT = HS -> RGB -> CT
            int[] rgb = convertHSVToRGB(putCall);
            convertCTToRGB(putCall, rgb);
        }
    }

    private static void convertCTToRGB(PutCall putCall, int[] rgb) {
        int mired = ColorTemperatureToRGBConverter.approximateMiredFromRGB(rgb[0], rgb[1], rgb[2]);
        putCall.setCt(mired);
    }

    private static int[] convertMiredToRGB(PutCall putCall) {
        int[] rgb = ColorTemperatureToRGBConverter.approximateRGBFromMired(putCall.getCt());
        putCall.setCt(null);
        return rgb;
    }

    private static int[] convertHSVToRGB(PutCall putCall) {
        int[] rgb = RGBToHSVConverter.hsvToRgb(putCall.getHue(), putCall.getSat(), 254);
        putCall.setSat(null);
        putCall.setHue(null);
        return rgb;
    }

    private static int[] convertXYToRGB(PutCall putCall, Double[][] colorGamut) {
        int[] rgbColor = RGBToXYConverter.convert(putCall.getX(), putCall.getY(), 255, colorGamut);
        putCall.setX(null);
        putCall.setY(null);
        return rgbColor;
    }

    private static void convertRGBToHSV(int red, int green, int blue, PutCall putCall) {
        int[] hsv = RGBToHSVConverter.rgbToHsv(red, green, blue);
        putCall.setHue(hsv[0]);
        putCall.setSat(hsv[1]);
    }

    private static void convertRGBToXY(PutCall putCall, Double[][] colorGamut, int[] rgb) {
        RGBToXYConverter.XYColor xyColor = RGBToXYConverter.convert(rgb[0], rgb[1], rgb[2], colorGamut);
        putCall.setX(xyColor.getX());
        putCall.setY(xyColor.getY());
    }
}
