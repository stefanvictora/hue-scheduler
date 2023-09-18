package at.sv.hue.color;

import lombok.Data;

@Data
final class ColorGamut {

    private final Point red;
    private final Point green;
    private final Point blue;

    public ColorGamut(Double[][] gamut) {
        red = new Point(gamut[0][0], gamut[0][1]);
        green = new Point(gamut[1][0], gamut[1][1]);
        blue = new Point(gamut[2][0], gamut[2][1]);
    }
}
