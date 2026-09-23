package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Captures the renderer's exact emitted vertices with identity-pose test doubles. */
public final class MissileGeometryChecks {
    public static final class Vec3 {
        public final double x, y, z;
        public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public Vec3 add(Vec3 b) { return new Vec3(x + b.x, y + b.y, z + b.z); }
        public Vec3 subtract(Vec3 b) { return new Vec3(x - b.x, y - b.y, z - b.z); }
        public Vec3 scale(double a) { return new Vec3(x * a, y * a, z * a); }
        public Vec3 cross(Vec3 b) { return new Vec3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x); }
        public double dot(Vec3 b) { return x * b.x + y * b.y + z * b.z; }
        public double lengthSqr() { return dot(this); }
        public Vec3 normalize() { return scale(1 / Math.sqrt(lengthSqr())); }
    }
    public static final class PoseStack { public static final class Pose {} }
    public static final class OverlayTexture { public static final int NO_OVERLAY = 0; }
    public static final class State {
        public Vec3 velocity;
        public final int lightCoords = 0x700050;
        State(Vec3 velocity) { this.velocity = velocity; }
    }
    public static final class Vertex {
        Vec3 point, normal;
        int alpha, light;
        Vertex(Vec3 point) { this.point = point; }
    }
    public static final class VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();
        public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) { vertices.add(new Vertex(new Vec3(x, y, z))); return this; }
        public VertexConsumer setColor(int red, int green, int blue, int alpha) { vertices.getLast().alpha = alpha; return this; }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setOverlay(int overlay) { return this; }
        public VertexConsumer setLight(int light) { vertices.getLast().light = light; return this; }
        public VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) { vertices.getLast().normal = new Vec3(x, y, z); return this; }
    }
    private record Triangle(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 normal() { return b.subtract(a).cross(c.subtract(a)); }
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private static List<Triangle> triangles(List<Vertex> vertices) {
        List<Triangle> result = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i += 4) {
            Vec3 a = vertices.get(i).point, b = vertices.get(i + 1).point, c = vertices.get(i + 2).point, d = vertices.get(i + 3).point;
            Triangle first = new Triangle(a, b, c), second = new Triangle(c, d, a);
            check(first.normal().lengthSqr() > 1e-14, "First triangle is degenerate");
            result.add(first);
            if (second.normal().lengthSqr() > 1e-14) result.add(second);
        }
        return result;
    }
    private static String key(Vec3 p) {
        return Math.round(p.x * 1_000_000) + "," + Math.round(p.y * 1_000_000) + "," + Math.round(p.z * 1_000_000);
    }
    private static void edge(Map<String, int[]> edges, Vec3 a, Vec3 b) {
        String from = key(a), to = key(b);
        check(!from.equals(to), "Unexpected zero-length mesh edge");
        boolean order = from.compareTo(to) < 0;
        int[] counts = edges.computeIfAbsent(order ? from + ":" + to : to + ":" + from, ignored -> new int[2]);
        counts[0]++; counts[1] += order ? 1 : -1;
    }
    private static boolean rayHits(Triangle t, Vec3 origin, Vec3 direction) {
        Vec3 n = t.normal();
        if (n.dot(direction) >= -1e-10) return false;
        double distance = t.a.subtract(origin).dot(n) / direction.dot(n);
        if (distance < 0) return false;
        Vec3 point = origin.add(direction.scale(distance));
        Vec3[] points = {t.a, t.b, t.c};
        for (int i = 0; i < 3; i++) {
            Vec3 a = points[i], b = points[(i + 1) % 3];
            if (b.subtract(a).cross(point.subtract(a)).dot(n) < -1e-10) return false;
        }
        return true;
    }
    public static void main(String[] args) {
        Vec3[] directions = {new Vec3(0,0,1), new Vec3(0,0,-1), new Vec3(1,0,0), new Vec3(-1,0,0),
            new Vec3(0,1,0), new Vec3(0,-1,0), new Vec3(1,2,3), new Vec3(-1,2,-3), new Vec3(0.000001,1,0.000001)};
        for (Vec3 input : directions) {
            Vec3 direction = input.normalize();
            VertexConsumer out = new VertexConsumer();
            CapturedMissileGeometry.shell(new State(input), new PoseStack.Pose(), out);
            List<Vertex> vertices = out.vertices;
            int bodyQuads = (MissileCapsuleShape.HULL.size() - 1) * MissileCapsuleShape.SIDES;
            check(vertices.size() == (bodyQuads + MissileCapsuleShape.SIDES * 2) * 4, "Missing hull or cap vertices");
            switch (args[0]) {
                case "actual_normals" -> {
                    for (int q = 0; q < vertices.size() / 4; q++) {
                        Vertex a = vertices.get(q * 4), b = vertices.get(q * 4 + 1), c = vertices.get(q * 4 + 2);
                        Vec3 normal = b.point.subtract(a.point).cross(c.point.subtract(a.point)).normalize();
                        for (int j = 0; j < 4; j++) {
                            Vertex v = vertices.get(q * 4 + j);
                            check(Double.isFinite(v.normal.lengthSqr()) && Math.abs(v.normal.lengthSqr() - 1) < 1e-5, "Invalid submitted normal");
                            check(normal.dot(v.normal) > 0.99999, "Submitted normal disagrees with actual winding");
                            check(v.alpha == 255 && v.light == 0x700050, "Solid shell must remain opaque and normally lit");
                        }
                        if (q < bodyQuads) {
                            int section = q / MissileCapsuleShape.SIDES;
                            var from = MissileCapsuleShape.HULL.get(section); var to = MissileCapsuleShape.HULL.get(section + 1);
                            if (from.z() == to.z()) {
                                double expected = Math.signum(from.radius() - to.radius());
                                check(normal.dot(direction) * expected > 0.99999, "Stepped shoulder faces into the solid shell");
                            } else {
                                Vec3 center = a.point.add(b.point).scale(0.5);
                                Vec3 radial = center.subtract(direction.scale(center.dot(direction))).normalize();
                                check(normal.dot(radial) > 0.1, "Side face winds inward");
                            }
                        } else check(normal.dot(direction) * (q < bodyQuads + MissileCapsuleShape.SIDES ? -1 : 1) > 0.99999,
                            "Front or rear cap winds inward");
                    }
                }
                case "closed_mesh" -> {
                    Map<String, int[]> edges = new HashMap<>();
                    double volume = 0;
                    for (Triangle t : triangles(vertices)) {
                        edge(edges, t.a, t.b); edge(edges, t.b, t.c); edge(edges, t.c, t.a);
                        volume += t.a.dot(t.b.cross(t.c)) / 6;
                    }
                    for (var entry : edges.entrySet()) check(entry.getValue()[0] == 2 && entry.getValue()[1] == 0,
                        "Open, duplicate or same-direction edge: " + entry.getKey());
                    check(volume > 0.03 && volume < 0.10, "Shell is inverted or missing volume");
                }
                case "caps_visible" -> {
                    List<Triangle> mesh = triangles(vertices);
                    Vec3 side = direction.cross(new Vec3(0,1,0));
                    if (side.lengthSqr() < 0.0001) side = new Vec3(1,0,0);
                    side = side.normalize();
                    Vec3 up = direction.cross(side).normalize();
                    for (int front : new int[] {-1, 1}) for (int ring = 0; ring <= 2; ring++) for (int i = 0; i < 16; i++) {
                        double angle = i * Math.PI / 8;
                        Vec3 origin = direction.scale(front * 2).add(side.scale(Math.cos(angle) * ring * 0.04)).add(up.scale(Math.sin(angle) * ring * 0.04));
                        Vec3 ray = direction.scale(-front);
                        double capZ = front > 0 ? MissileCapsuleShape.FRONT : MissileCapsuleShape.REAR;
                        check(mesh.stream().anyMatch(t -> Math.abs(t.a.dot(direction) - capZ) < 1e-6 && rayHits(t, origin, ray)),
                            "Backface-culled axial ray passes through an uncapped front/rear");
                    }
                }
                default -> throw new AssertionError("Unknown geometry check");
            }
        }
        System.out.printf(Locale.ROOT, "%s: 9 orientations, 96 quads per solid shell%n", args[0]);
    }
}
