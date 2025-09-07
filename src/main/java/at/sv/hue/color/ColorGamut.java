package at.sv.hue.color;

import lombok.Data;

@Data
final class ColorGamut {

    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};

    private final Point red;
    private final Point green;
    private final Point blue;

    public ColorGamut(Double[][] gamut) {
        if (gamut == null) {
            red = new Point(GAMUT_C[0][0], GAMUT_C[0][1]);
            green = new Point(GAMUT_C[1][0], GAMUT_C[1][1]);
            blue = new Point(GAMUT_C[2][0], GAMUT_C[2][1]);
        } else {
            red = new Point(gamut[0][0], gamut[0][1]);
            green = new Point(gamut[1][0], gamut[1][1]);
            blue = new Point(gamut[2][0], gamut[2][1]);
        }
    }
}
