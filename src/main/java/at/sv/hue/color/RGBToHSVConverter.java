package at.sv.hue.color;

final class RGBToHSVConverter {
    
    private RGBToHSVConverter() {
    }
    
    /**
     * Converts the given rgb values to Philips Hue compatible hue, saturation and brightness values.
     * This means that the values are directly converted to the ranges the Hue API accepts.
     *
     * @return hue [0, 65535], sat [0, 254], brightness [0, 254]
     */
    public static int[] rgbToHsv(int red, int green, int blue) {
        float[] hsv = java.awt.Color.RGBtoHSB(red, green, blue, null);
        int hue = (int) (hsv[0] * 65535);
        int saturation = (int) (hsv[1] * 254);
        int brightness = (int) (hsv[2] * 254);
        return new int[]{hue, saturation, brightness};
    }

    /**
     * Converts the given HSV value to RGB. The HSV values are expected to be in Hue API compatible format.
     *
     * @param hue        [0, 65535]
     * @param saturation [0, 254]
     * @param brightness [0, 254]
     * @return rgb value corresponding to the given HSV
     */
    public static int[] hsvToRgb(int hue, int saturation, int brightness) {
        float hueAdjusted = hue / 65535.0f;
        float saturationAdjusted = saturation / 254.0f;
        float brightnessAdjusted = brightness / 254.0f;

        int rgb = java.awt.Color.HSBtoRGB(hueAdjusted, saturationAdjusted, brightnessAdjusted);
        return new int[]{
                (rgb >> 16) & 0xFF,  // red
                (rgb >> 8) & 0xFF,   // green
                rgb & 0xFF           // blue
        };
    }
}
