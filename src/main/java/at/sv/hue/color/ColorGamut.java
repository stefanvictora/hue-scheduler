package at.sv.hue.color;

import lombok.Getter;

@Getter
final class ColorGamut {

    private final Point red;
    private final Point green;
    private final Point blue;

    public ColorGamut(Double[][] gamut) {
        red = new Point(gamut[0][0], gamut[0][1]);
        green = new Point(gamut[1][0], gamut[1][1]);
        blue = new Point(gamut[2][0], gamut[2][1]);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ColorGamut that = (ColorGamut) o;

        if (!red.equals(that.red)) return false;
        if (!green.equals(that.green)) return false;
        return blue.equals(that.blue);
    }

    @Override
    public int hashCode() {
        int result = red.hashCode();
        result = 31 * result + green.hashCode();
        result = 31 * result + blue.hashCode();
        return result;
    }
}
