package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.List;

/** Bounded emitter geometry, independent of Minecraft for numeric clipping tests. */
final class CarrierEffectGeometry {
    static final double APERTURE_RADIUS = 1.15;
    static final int SIDES = 12, MAX_BEAMS = 16;
    record Point(double x, double y, double z) {
        Point add(Point p) { return new Point(x + p.x, y + p.y, z + p.z); }
        Point scale(double n) { return new Point(x * n, y * n, z * n); }
        Point lerp(Point p, double t) { return scale(1 - t).add(p.scale(t)); }
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    record Quad(Point a, Point b, Point c, Point d) {}
    record Column(double minX, double maxX, double minZ, double maxZ) {
        boolean contains(Point p) { return p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ; }
    }
    static double progress(int tick, int duration, double interpolation) {
        if (duration <= 0 || !Double.isFinite(interpolation)) return 0;
        return Math.clamp((tick + Math.clamp(interpolation, 0, 10)) / duration, 0, 1);
    }
    static boolean validRay(Point start, Point end, Column column) {
        return start.finite() && end.finite() && end.y < start.y - .01
            && (column == null || column.contains(start) && column.contains(end));
    }
    static List<Quad> beam(Point start, Point end, double radius, Column column) {
        if (!validRay(start, end, column) || !Double.isFinite(radius) || radius <= 0) return List.of();
        radius = Math.min(APERTURE_RADIUS, radius);
        List<Quad> out = new ArrayList<>(SIDES * 4);
        for (int i = 0; i < SIDES; i++) {
            Point a = ring(start, radius, i), b = ring(start, radius, i + 1);
            Point c = ring(end, radius * .32, i + 1), d = ring(end, radius * .32, i);
            // Horizontal rings keep the entire beam below the physical lens plane.
            polygon(out, List.of(a, b, c, d), column);
        }
        return List.copyOf(out);
    }
    static List<Quad> areaBeam(Point start, Point end, double impactRadius) {
        if (!validRay(start,end,null) || !Double.isFinite(impactRadius) || impactRadius <= 0 || impactRadius > 16)
            return List.of();
        List<Quad> out = new ArrayList<>(SIDES);
        for (int i=0;i<SIDES;i++) out.add(new Quad(ring(start,APERTURE_RADIUS,i),
            ring(start,APERTURE_RADIUS,i+1),ring(end,impactRadius,i+1),ring(end,impactRadius,i)));
        return List.copyOf(out);
    }
    static List<Quad> disc(Point center, double radius, double inner, Column column) {
        if (!center.finite() || !Double.isFinite(radius) || !Double.isFinite(inner) || radius <= 0) return List.of();
        radius = Math.min(APERTURE_RADIUS, radius); inner = Math.clamp(inner, 0, radius);
        List<Quad> out = new ArrayList<>(SIDES * 4);
        for (int i = 0; i < SIDES; i++) polygon(out,
            List.of(ring(center, inner, i), ring(center, radius, i), ring(center, radius, i + 1), ring(center, inner, i + 1)), column);
        return List.copyOf(out);
    }
    static Point emitter(Point center, int index, int count, double radius) {
        if (!center.finite() || count <= 0 || index < 0 || index >= count || !Double.isFinite(radius)) return center;
        double angle = index * Math.PI * 2 / count - Math.PI / 2;
        return center.add(new Point(Math.cos(angle) * radius, .04, Math.sin(angle) * radius));
    }
    static List<Quad> sphere(Point center, double radius) {
        if (!center.finite() || !Double.isFinite(radius) || radius <= 0 || radius > 4) return List.of();
        int bands = 7;
        List<Quad> out = new ArrayList<>(bands * SIDES);
        for (int band = 0; band < bands; band++) {
            double a0 = -1.36 + band * 2.72 / bands;
            double a1 = -1.36 + (band + 1) * 2.72 / bands;
            double y0 = Math.sin(a0) * radius, y1 = Math.sin(a1) * radius;
            double r0 = Math.cos(a0) * radius, r1 = Math.cos(a1) * radius;
            for (int side = 0; side < SIDES; side++) out.add(new Quad(
                spherePoint(center, r0, y0, side), spherePoint(center, r0, y0, side + 1),
                spherePoint(center, r1, y1, side + 1), spherePoint(center, r1, y1, side)));
        }
        return List.copyOf(out);
    }
    static Column chunk(Column selected, int offsetX, int offsetZ) {
        if (selected == null) return null;
        double width = selected.maxX - selected.minX, depth = selected.maxZ - selected.minZ;
        return new Column(selected.minX + offsetX * width, selected.maxX + offsetX * width,
            selected.minZ + offsetZ * depth, selected.maxZ + offsetZ * depth);
    }
    static Column centralChunk(Column footprint, int offsetX, int offsetZ) {
        if (footprint == null) return null;
        double centerX = (footprint.minX + footprint.maxX) * .5;
        double centerZ = (footprint.minZ + footprint.maxZ) * .5;
        double x0 = centerX - 8 + offsetX * 16, z0 = centerZ - 8 + offsetZ * 16;
        return new Column(x0, x0 + 16, z0, z0 + 16);
    }
    static List<Quad> outline(Column column, double y, double width, double expand) {
        if (column == null || !Double.isFinite(y) || !Double.isFinite(width) || width <= 0
            || !Double.isFinite(expand) || expand < 0 || expand > 16) return List.of();
        double x0 = column.minX - expand, x1 = column.maxX + expand;
        double z0 = column.minZ - expand, z1 = column.maxZ + expand;
        double half = Math.min(width, Math.min(x1 - x0, z1 - z0) / 2) / 2;
        return List.of(
            horizontal(x0 - half, x1 + half, z0 - half, z0 + half, y),
            horizontal(x0 - half, x1 + half, z1 - half, z1 + half, y),
            horizontal(x0 - half, x0 + half, z0 + half, z1 - half, y),
            horizontal(x1 - half, x1 + half, z0 + half, z1 - half, y));
    }
    static List<Quad> circleOutline(Point center, double radius, double y, double width, Column column) {
        if (center == null || !center.finite() || !Double.isFinite(radius) || radius <= 0 || radius > 32
            || !Double.isFinite(y) || !Double.isFinite(width) || width <= 0 || width >= radius) return List.of();
        Point atY = new Point(center.x, y, center.z);
        List<Quad> out = new ArrayList<>(SIDES * 2);
        for (int i = 0; i < SIDES; i++) polygon(out, List.of(
            ring(atY, radius - width, i), ring(atY, radius, i),
            ring(atY, radius, i + 1), ring(atY, radius - width, i + 1)), column);
        return List.copyOf(out);
    }
    static List<Quad> releaseRing(Point center, double radius, double width, Column column) {
        if (!center.finite() || !Double.isFinite(radius) || !Double.isFinite(width)
            || radius <= 0 || radius > 32 || width <= 0 || width >= radius) return List.of();
        List<Quad> out = new ArrayList<>(SIDES * 2);
        double inner = radius - width;
        for (int i = 0; i < SIDES; i++) polygon(out,
            List.of(ring(center, inner, i), ring(center, radius, i),
                ring(center, radius, i + 1), ring(center, inner, i + 1)), column);
        return List.copyOf(out);
    }
    static List<Quad> shockCurtain(Point center, double radius, double height, Column column) {
        if (!center.finite() || !Double.isFinite(radius) || radius <= 0 || radius > 32
            || !Double.isFinite(height) || height <= 0 || height > 24) return List.of();
        List<Quad> out = new ArrayList<>(SIDES);
        for (int i = 0; i < SIDES; i++) polygon(out, List.of(
            ring(center, radius * .72, i), ring(center, radius * .72, i + 1),
            ring(center.add(new Point(0, height, 0)), radius, i + 1),
            ring(center.add(new Point(0, height, 0)), radius, i)), column);
        return List.copyOf(out);
    }
    /** A bounded square work column, expanded below the lens instead of a wandering pencil beam. */
    static List<Quad> workColumn(Point start, double bottom, Column column, double inset) {
        if (column == null || !start.finite() || !Double.isFinite(bottom) || bottom >= start.y
            || !Double.isFinite(inset) || inset < 0 || !column.contains(start)) return List.of();
        double x0 = column.minX + inset, x1 = column.maxX - inset;
        double z0 = column.minZ + inset, z1 = column.maxZ - inset;
        if (x0 >= x1 || z0 >= z1) return List.of();
        double shoulder = Math.max(bottom, start.y - 4);
        Point[] lens = {new Point(start.x-.8,start.y,start.z-.8), new Point(start.x+.8,start.y,start.z-.8),
            new Point(start.x+.8,start.y,start.z+.8), new Point(start.x-.8,start.y,start.z+.8)};
        Point[] top = {new Point(x0,shoulder,z0),new Point(x1,shoulder,z0),
            new Point(x1,shoulder,z1),new Point(x0,shoulder,z1)};
        List<Quad> out = new ArrayList<>(9);
        for (int i=0;i<4;i++) {
            int j=(i+1)%4;
            polygon(out,List.of(lens[i],lens[j],top[j],top[i]),column);
            if (shoulder > bottom) out.add(new Quad(top[i],top[j],
                new Point(top[j].x,bottom,top[j].z),new Point(top[i].x,bottom,top[i].z)));
        }
        out.add(new Quad(new Point(x0,bottom,z0),new Point(x1,bottom,z0),
            new Point(x1,bottom,z1),new Point(x0,bottom,z1)));
        return List.copyOf(out);
    }
    private static Point ring(Point center, double radius, int i) {
        double angle = i * Math.PI * 2 / SIDES;
        return center.add(new Point(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
    }
    private static Point spherePoint(Point center, double radius, double y, int i) {
        double angle = i * Math.PI * 2 / SIDES;
        return center.add(new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius));
    }
    private static Quad horizontal(double x0, double x1, double z0, double z1, double y) {
        return new Quad(new Point(x0, y, z0), new Point(x1, y, z0),
            new Point(x1, y, z1), new Point(x0, y, z1));
    }
    private static void polygon(List<Quad> out, List<Point> polygon, Column column) {
        if (column != null) {
            polygon = clip(polygon, 0, column.minX, true);
            polygon = clip(polygon, 0, column.maxX, false);
            polygon = clip(polygon, 2, column.minZ, true);
            polygon = clip(polygon, 2, column.maxZ, false);
        }
        if (polygon.size() == 4) out.add(new Quad(polygon.get(0), polygon.get(1), polygon.get(2), polygon.get(3)));
        else for (int i = 1; i + 1 < polygon.size(); i++)
            out.add(new Quad(polygon.get(0), polygon.get(i), polygon.get(i + 1), polygon.get(i + 1)));
    }
    private static double axis(Point p, int axis) { return axis == 0 ? p.x : p.z; }
    private static List<Point> clip(List<Point> points, int axis, double bound, boolean minimum) {
        if (points.isEmpty()) return points;
        List<Point> out = new ArrayList<>(8);
        Point previous = points.getLast();
        boolean insidePrevious = minimum ? axis(previous, axis) >= bound : axis(previous, axis) <= bound;
        for (Point next : points) {
            boolean inside = minimum ? axis(next, axis) >= bound : axis(next, axis) <= bound;
            if (inside != insidePrevious) {
                double t = (bound - axis(previous, axis)) / (axis(next, axis) - axis(previous, axis));
                Point p = new Point(interpolate(previous.x, next.x, t), interpolate(previous.y, next.y, t), interpolate(previous.z, next.z, t));
                out.add(axis == 0 ? new Point(bound, p.y, p.z) : new Point(p.x, p.y, bound));
            }
            if (inside) out.add(next);
            previous = next; insidePrevious = inside;
        }
        return out;
    }
    private static double interpolate(double a, double b, double t) {
        return Math.clamp(a + (b - a) * t, Math.min(a, b), Math.max(a, b));
    }
}
