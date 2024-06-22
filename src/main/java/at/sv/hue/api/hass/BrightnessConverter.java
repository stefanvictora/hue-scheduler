package at.sv.hue.api.hass;

final class BrightnessConverter {

    private BrightnessConverter() {
    }

    /**
     * Convert hass format 0..255 to hue brightness 1..254
     */
    static Integer hassToHueBrightness(Integer hassBrightness) {
        if (hassBrightness == null) {
            return null;
        }
        return Math.round(((float) hassBrightness / 255) * 253 + 1);
    }

    /**
     * Convert hue brightness 1..254 to hass format 1..255
     */
    static Integer hueToHassBrightness(Integer hueBrightness) {
        if (hueBrightness == null) {
            return null;
        }
        if (hueBrightness == 1) {
            return 1; // special case, since brightness=0 on the HA API call is turning the light off
        }
        return Math.round(((float) hueBrightness - 1) / 253 * 255);
    }
}
