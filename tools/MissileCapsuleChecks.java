package jp.morrowgear.drone.client;

import java.util.HashSet;

public final class MissileCapsuleChecks {
	private record Point(double x, double y, double z) {
		Point subtract(Point other) { return new Point(x - other.x, y - other.y, z - other.z); }
		Point cross(Point other) { return new Point(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
		double lengthSquared() { return x * x + y * y + z * z; }
	}
	private static Point point(MissileCapsuleShape.Ring ring, int side) {
		double a = MissileCapsuleShape.angle(side);
		return new Point(Math.cos(a) * ring.radius(), Math.sin(a) * ring.radius(), ring.z());
	}
	private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
	public static void main(String[] args) {
		var hull = MissileCapsuleShape.HULL;
		double radius = hull.stream().mapToDouble(MissileCapsuleShape.Ring::radius).max().orElseThrow();
		double length = MissileCapsuleShape.FRONT - MissileCapsuleShape.REAR;
		switch (args[0]) {
			case "proportions" -> {
				check(length >= 0.65 && length <= 0.85, "Capsule must stay short");
				check(radius >= 0.17 && radius <= 0.22, "Armored body must remain visibly thick");
				check(length / (radius * 2) >= 1.6 && length / (radius * 2) <= 2.5, "Long slender missile silhouette returned");
				check(hull.getLast().radius() >= radius * 0.55, "Front cap became a pointed nose");
				check(hull.getFirst().z() == MissileCapsuleShape.REAR && hull.getLast().z() == MissileCapsuleShape.FRONT, "Open-ended profile");
			}
			case "stepped_shell" -> {
				int shoulders = 0;
				for (int i = 1; i < hull.size(); i++) {
					var a = hull.get(i - 1); var b = hull.get(i);
					check(b.z() >= a.z(), "Profile must not fold backward");
					check(b.radius() > 0 && Double.isFinite(b.radius()), "Invalid shell radius");
					if (b.z() == a.z() && Math.abs(b.radius() - a.radius()) >= 0.02) shoulders++;
				}
				check(shoulders >= 3, "Stepped armor shoulders are missing");
				check(hull.stream().map(MissileCapsuleShape.Ring::color).distinct().count() >= 4, "Shell lost its material separation");
			}
			case "embedded_band" -> {
				check(MissileCapsuleShape.GLOW_RADIUS < radius, "Red strip must remain below the outer armor");
				boolean seated = false;
				for (int i = 0; i + 1 < hull.size(); i++) {
					var a = hull.get(i); var b = hull.get(i + 1);
					if (a.z() <= MissileCapsuleShape.GLOW_FROM && b.z() >= MissileCapsuleShape.GLOW_TO
						&& a.radius() == b.radius() && a.radius() < MissileCapsuleShape.GLOW_RADIUS
						&& MissileCapsuleShape.GLOW_RADIUS - a.radius() <= 0.004) seated = true;
				}
				check(seated, "Emission needs an actual recessed seat, not a floating exterior band");
				check(MissileCapsuleShape.GLOW_TO - MissileCapsuleShape.GLOW_FROM <= length * 0.08, "Red emission dominates the shell");
			}
			case "surface_geometry" -> {
				for (int i = 0; i + 1 < hull.size(); i++) for (int side = 0; side < MissileCapsuleShape.SIDES; side++) {
					Point a = point(hull.get(i), side), b = point(hull.get(i), side + 1), c = point(hull.get(i + 1), side + 1);
					Point normal = b.subtract(a).cross(c.subtract(a));
					check(Double.isFinite(normal.lengthSquared()) && normal.lengthSquared() > 1e-10, "Degenerate shell quad");
					if (hull.get(i).z() != hull.get(i + 1).z()) check(normal.x * (a.x + b.x) + normal.y * (a.y + b.y) > 0, "Inward side winding");
				}
				for (var ring : hull) check(point(ring, 0).subtract(point(ring, MissileCapsuleShape.SIDES)).lengthSquared() < 1e-25, "Unclosed radial seam");
			}
			case "short_exhaust" -> {
				check(MissileCapsuleShape.REAR - MissileCapsuleShape.EXHAUST_END <= length * 0.4, "Long engine streak returned");
				check(MissileCapsuleShape.EXHAUST_END < MissileCapsuleShape.EXHAUST_CORE_END
					&& MissileCapsuleShape.EXHAUST_CORE_END < MissileCapsuleShape.REAR, "Invalid nested thrust cones");
				check(!MissileCapsuleShape.exhaustVisible(false, false), "Ejected missile cannot show an ignited motor");
				check(MissileCapsuleShape.exhaustVisible(true, false), "Server ignition must reveal motor");
				check(!MissileCapsuleShape.exhaustVisible(true, true), "Impacted missile must extinguish motor");
			}
			case "cold_eject" -> {
				check(MissileCapsuleShape.COLD_EJECT_PUFFS == 2, "Cold release must remain low cost");
				for (double age : new double[] {0, 0.5, 1, 2, 2.999})
					check(MissileCapsuleShape.ejectVisible(true, false, false, age), "Missing initial cold release");
				for (double age : new double[] {-1, 3, 13, 30, Double.NaN, Double.POSITIVE_INFINITY})
					check(!MissileCapsuleShape.ejectVisible(true, false, false, age), "Cold release escaped its initial 3 ticks");
				check(!MissileCapsuleShape.ejectVisible(false, false, false, 0), "Reloaded TURN/GUIDED phase cannot replay release");
				check(!MissileCapsuleShape.ejectVisible(true, true, false, 0), "Ignited motor must suppress cold release");
				check(!MissileCapsuleShape.ejectVisible(true, false, true, 0), "Impacted round must suppress cold release");
				for (boolean motor : new boolean[] {false, true}) for (boolean impact : new boolean[] {false, true})
					check(!(MissileCapsuleShape.ejectVisible(true, motor, impact, 0)
						&& MissileCapsuleShape.exhaustVisible(motor, impact)), "Cold release and engine flames overlap");
			}
			case "smoke_budget" -> {
				for (int size = 0; size <= 1000; size++) {
					int count = MissileCapsuleShape.smokeCount(size);
					check(count <= 12 && count <= size, "Unbounded smoke geometry");
					var indices = new HashSet<Integer>();
					for (int i = 0; i < count; i++) {
						int index = MissileCapsuleShape.historyIndex(i, size);
						check(index >= 0 && index < size && indices.add(index), "Invalid or duplicate history sampling");
					}
					if (count > 1) check(indices.contains(0) && indices.contains(size - 1), "Smoke lost a history endpoint");
				}
				check(MissileCapsuleShape.maximumQuads() == 216, "Update the audited complete geometry budget");
				check(MissileCapsuleShape.maximumQuads() <= 224, "Per-missile geometry budget exceeded");
			}
			case "smoke_fade" -> {
				int previous = 255;
				for (int step = 0; step <= 1000; step++) {
					double age = MissileCapsuleShape.age(step * 18.0 / 1000, 18);
					int alpha = MissileCapsuleShape.smokeAlpha(age);
					check(alpha >= 0 && alpha <= 92, "Smoke became opaque");
					check(MissileCapsuleShape.smokeRadius(age) >= 0.08 && MissileCapsuleShape.smokeRadius(age) <= 0.301, "Unbounded puff radius");
					check(MissileCapsuleShape.smokeRadius(age) + MissileCapsuleShape.smokeRise(age) < 0.5, "Smoke escapes its culling bounds");
					if (age >= 0.125) { check(alpha <= previous, "Old smoke must fade monotonically"); previous = alpha; }
				}
				check(MissileCapsuleShape.smokeAlpha(MissileCapsuleShape.age(18, 18)) == 0, "Expired smoke remains visible");
				check(MissileCapsuleShape.age(Double.NaN, 18) == 1 && MissileCapsuleShape.age(1, 0) == 1, "Invalid smoke ages must disappear");
			}
			default -> throw new AssertionError("Unknown check");
		}
	}
}
