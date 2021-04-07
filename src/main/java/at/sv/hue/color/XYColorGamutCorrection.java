package at.sv.hue.color;

/**
 * Code adapted from <a href="https://github.com/home-assistant/core/blob/dev/homeassistant/util/color.py">Home Assistant color.py</a>
 * License: Apache-2.0 License
 * <p>
 * Which in turn adapted the code from <a href="https://github.com/benknight/hue-python-rgb-converter">benknight/hue-python-rgb-converter</a>
 * License: MIT License
 */
final class XYColorGamutCorrection {

    private final Point p;
    private final ColorGamut gamut;

    public XYColorGamutCorrection(double x, double y, Double[][] gamut) {
        p = new Point(x, y);
        this.gamut = new ColorGamut(gamut);
    }

    public Point adjustIfNeeded() {
        if (!isInRange()) {
            return getClosestPointToPoint();
        }
        return p;
    }

    private boolean isInRange() {
        Point v1 = new Point(gamut.getGreen().x - gamut.getRed().x, gamut.getGreen().y - gamut.getRed().y);
        Point v2 = new Point(gamut.getBlue().x - gamut.getRed().x, gamut.getBlue().y - gamut.getRed().y);

        Point q = new Point(p.x - gamut.getRed().x, p.y - gamut.getRed().y);

        double s = crossProduct(q, v2) / crossProduct(v1, v2);
        double t = crossProduct(v1, q) / crossProduct(v1, v2);
        return s >= 0.0 && t >= 0.0 && s + t <= 1.0;
    }

    private double crossProduct(Point p1, Point p2) {
        return p1.x * p2.y - p1.y * p2.x;
    }

    private Point getClosestPointToPoint() {
        Point pAB = getClosestPointToLine(gamut.getRed(), gamut.getGreen(), p);
        Point pAC = getClosestPointToLine(gamut.getBlue(), gamut.getRed(), p);
        Point pBC = getClosestPointToLine(gamut.getGreen(), gamut.getBlue(), p);

        double dAB = getDistanceBetweenTwoPoints(p, pAB);
        double dAC = getDistanceBetweenTwoPoints(p, pAC);
        double dBC = getDistanceBetweenTwoPoints(p, pBC);

        double lowest = dAB;
        Point closest = pAB;
        if (dAC < lowest) {
            lowest = dAC;
            closest = pAC;
        }
        if (dBC < lowest) {
            closest = pBC;
        }
        return new Point(closest.x, closest.y);
    }

    private Point getClosestPointToLine(Point a, Point b, Point p) {
        Point ap = new Point(p.x - a.x, p.y - a.y);
        Point ab = new Point(b.x - a.x, b.y - a.y);
        double ab2 = ab.x * ab.x + ab.y * ab.y;
        double ap_ab = ap.x * ab.x + ap.y * ab.y;
        double t = ap_ab / ab2;
        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        return new Point(a.x + ab.x * t, a.y + ab.y * t);
    }

    private double getDistanceBetweenTwoPoints(Point p1, Point p2) {
        double dx = p1.x - p2.x;
        double dy = p1.y - p2.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
