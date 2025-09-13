package at.sv.hue.color;

import java.util.Locale;

/**
 * AI generated
 */
public final class OkLchParser {

    private record OkLch(double L, double C, double hDeg) {
    }

    /**
     * Parse a CSS-style oklch() color string and convert to XYColor.
     * Example inputs: "oklch(0.5 0.2 120)", "oklch(50% 0.2 120deg / 0.3)"
     *
     * @param input the input string
     * @return the parsed color
     * @throws IllegalArgumentException if the input is invalid
     */
    public static XYColor parseOkLch(String input) {
        OkLch o = parse(input);

        double[] xy = OkLabUtil.OKLchDeg_to_xy(o.L, o.C, o.hDeg);
        return new XYColor(xy[0], xy[1], mapBrightness(o.L));
    }

    private static OkLch parse(String input) {
        String s = validateInput(input);
        String[] parts = splitIntoParts(s);

        double L = parseLightness(parts[0]);
        double C = parseChroma(parts[1]);
        double hDeg = parseAndNormalizeHue(parts[2]);

        return new OkLch(clamp01(L), Math.max(0.0, C), hDeg);
    }

    private static String validateInput(String input) {
        String s = input.trim();
        if (s.regionMatches(true, 0, "oklch(", 0, 6) && s.endsWith(")")) {
            return s.substring(6, s.length() - 1).trim();
        }
        throw new IllegalArgumentException("Invalid OKLCH format. Expected: oklch(L C h) or oklch(L C h/A), got: " + input);
    }

    private static String[] splitIntoParts(String s) {
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash).trim();
        }
        String[] parts = s.replace(',', ' ').trim().split("\\s+");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Need L C h");
        }
        return parts;
    }

    private static double parseLightness(String token) {
        String t = token.trim();
        if (t.endsWith("%")) {
            return Double.parseDouble(t.substring(0, t.length() - 1)) / 100.0;
        }
        return Double.parseDouble(t);
    }

    private static double parseChroma(String token) {
        return Math.max(0.0, Double.parseDouble(token.trim()));
    }

    private static double parseAndNormalizeHue(String token) {
        double hDeg = parseAngleToDegrees(token);
        return normalizeHueDegrees(hDeg);
    }

    private static double parseAngleToDegrees(String token) {
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.endsWith("deg")) {
            return Double.parseDouble(t.substring(0, t.length() - 3));
        }
        if (t.endsWith("grad")) {
            return Double.parseDouble(t.substring(0, t.length() - 4)) * (9.0 / 10.0);
        }
        if (t.endsWith("rad")) {
            return Math.toDegrees(Double.parseDouble(t.substring(0, t.length() - 3)));
        }
        if (t.endsWith("turn")) {
            return Double.parseDouble(t.substring(0, t.length() - 4)) * 360.0;
        }
        return Double.parseDouble(t); // unitless = degrees
    }

    private static double normalizeHueDegrees(double h) {
        double r = h % 360.0;
        return r < 0 ? r + 360.0 : r;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int mapBrightness(double L) {
        double t = clamp01(L);
        int v = (int) Math.round(t * 254.0);
        return Math.max(1, Math.min(254, v));
    }
}
