package at.sv.hue;

public final class FormatUtil {
    private FormatUtil() {
    }

    public static String formatBrightnessPercent(int bri) {
        double percent = ((bri - 1) * 99.0 / 253.0) + 1.0;
        double roundedOneDecimal = Math.round(percent * 10.0) / 10.0;
        if (Math.abs(roundedOneDecimal - Math.rint(roundedOneDecimal)) < 0.0001) {
            return String.valueOf((int) Math.rint(roundedOneDecimal));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", roundedOneDecimal);
    }
}
