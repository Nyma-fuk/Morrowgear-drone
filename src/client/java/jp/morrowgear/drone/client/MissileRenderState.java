package jp.morrowgear.drone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import jp.morrowgear.drone.MissileTrailHistory;

final class MissileRenderState extends EntityRenderState {
	Vec3 velocity = new Vec3(0, 0, 1);
	boolean impacted;
	boolean motorIgnited;
	boolean ejecting;
	double trailTime;
	List<MissileTrailHistory.Point> trail = List.of();
}

/** Pure local-space profile and fixed per-missile geometry budget. */
final class MissileCapsuleShape {
	static final int SIDES = 8;
	static final int MAX_SMOKE_PUFFS = 12;
	static final int COLD_EJECT_PUFFS = 2;
	static final double REAR = -0.40, FRONT = 0.40;
	static final double GLOW_FROM = -0.082, GLOW_TO = -0.037, GLOW_RADIUS = 0.167;
	static final double EXHAUST_END = -0.67, EXHAUST_CORE_END = -0.55;
	record Ring(double z, double radius, int color) {}
	static final List<Ring> HULL = List.of(
		new Ring(REAR, 0.10, 0x303A40),
		new Ring(-0.34, 0.10, 0x53636C),
		new Ring(-0.34, 0.16, 0x667780),
		new Ring(-0.28, 0.19, 0x85969E),
		new Ring(-0.10, 0.19, 0x46535C),
		new Ring(-0.10, 0.165, 0x222A30),
		new Ring(-0.02, 0.165, 0x46535C),
		new Ring(-0.02, 0.19, 0xA6B3BA),
		new Ring(0.24, 0.19, 0x788991),
		new Ring(0.34, 0.16, 0x65777F),
		new Ring(FRONT, 0.11, 0xBAC5CB));

	private MissileCapsuleShape() {}
	static double angle(int side) { return side * Math.PI * 2 / SIDES; }
	static boolean exhaustVisible(boolean motorIgnited, boolean impacted) { return motorIgnited && !impacted; }
	static boolean ejectVisible(boolean ejecting, boolean motorIgnited, boolean impacted, double age) {
		return ejecting && !motorIgnited && !impacted && Double.isFinite(age) && age >= 0 && age < 3;
	}
	static int smokeCount(int historySize) { return Math.min(MAX_SMOKE_PUFFS, Math.max(0, historySize)); }
	static int historyIndex(int puff, int historySize) {
		int count = smokeCount(historySize);
		if (puff < 0 || puff >= count) throw new IllegalArgumentException("Invalid smoke sample");
		return count == 1 ? historySize - 1 : (int)Math.round(puff * (historySize - 1.0) / (count - 1));
	}
	static double age(double ticks, double lifetime) {
		return !Double.isFinite(ticks) || !Double.isFinite(lifetime) || lifetime <= 0
			? 1 : Math.clamp(ticks / lifetime, 0, 1);
	}
	static double smokeRadius(double age) { return 0.08 + 0.22 * age; }
	static double smokeRise(double age) { return 0.16 * age; }
	static int smokeAlpha(double age) { return (int)Math.round(92 * (1 - age) * (1 - age) * Math.min(1, age * 8)); }
	static int maximumQuads() { return (HULL.size() - 1 + 2 + 1 + Math.max(2, COLD_EJECT_PUFFS) + MAX_SMOKE_PUFFS) * SIDES; }
}
