package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;

/** Geometry only. World collision, line of sight, and target validity stay server-authoritative. */
final class WeaponFiringRoutePolicy {
	static final long REFRESH_TICKS = 10L;
	static final long NO_SOLUTION_TIMEOUT_TICKS = 120L;
	static final long WEAPON_SUPPRESSION_TICKS = 180L;
	static final double ARRIVAL_DISTANCE = 2.1;

	private WeaponFiringRoutePolicy() {}

	static List<Candidate> candidates(CombatWeapon weapon, Vec3 target, Vec3 aircraft,
		Vec3 preferred, Vec3 attackAxis, int slot, int count,
		CombatPolicy.AirspaceSlot airspace) {
		var result = new ArrayList<Candidate>();
		if (preferred != null) result.add(new Candidate(preferred, horizontal(attackAxis), true));
		double slotPhase = Math.floorMod(slot, Math.max(1, count)) * Math.PI * 2.0 / Math.max(1, count)
			+ airspace.phaseOffset();
		switch (weapon) {
			case LASER -> addLaser(result, target, slotPhase, airspace);
			case MISSILE -> addMissile(result, target, slotPhase, airspace);
			case AUTOCANNON -> addGun(result, target, aircraft, slotPhase, airspace);
			default -> { }
		}
		return List.copyOf(result);
	}

	private static void addLaser(List<Candidate> result, Vec3 target, double phase,
		CombatPolicy.AirspaceSlot airspace) {
		// First search the normal upper orbit. Then search side-on cavern lanes; a roof
		// must not force an aircraft to keep requesting an unreachable point above it.
		for (double height : new double[] {10 + airspace.heightOffset(), 6, 3, 0, -2}) {
			for (double radius : new double[] {10 + airspace.radiusOffset(), 14, 20, 28}) {
				for (int step = 0; step < 12; step++) {
					double angle = phase + step * Math.PI / 6.0;
					Vec3 point = target.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
					result.add(new Candidate(point, horizontal(target.subtract(point)), false));
				}
			}
		}
		for (double height : new double[] {12, 20, 32, -8}) {
			Vec3 point = target.add(0, height, 0);
			result.add(new Candidate(point, horizontal(target.subtract(point)), false));
		}
	}

	private static void addMissile(List<Candidate> result, Vec3 target, double phase,
		CombatPolicy.AirspaceSlot airspace) {
		for (double height : new double[] {16 + airspace.heightOffset(), 12, 9, 22}) {
			for (double radius : new double[] {32 + airspace.radiusOffset(), 40, 52, 62}) {
				for (int step = 0; step < 12; step++) {
					double angle = phase + step * Math.PI / 6.0;
					Vec3 point = target.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
					result.add(new Candidate(point, horizontal(target.subtract(point)), false));
				}
			}
		}
	}

	private static void addGun(List<Candidate> result, Vec3 target, Vec3 aircraft,
		double phase, CombatPolicy.AirspaceSlot airspace) {
		Vec3 base = horizontal(target.subtract(aircraft));
		double baseAngle = Math.atan2(base.z, base.x);
		// A gun route is the point beyond the target. Flying toward it creates a forward
		// firing segment through the target instead of a hover point or a rearward shot.
		for (double height : new double[] {7 + airspace.heightOffset(), 4, 1.5, 10}) {
			for (double radius : new double[] {22, 30, 38}) {
				for (int step : new int[] {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6, 7, -7, 8}) {
					double angle = baseAngle + Math.PI + step * Math.PI / 8.0 + phase * 0.08;
					Vec3 point = target.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
					result.add(new Candidate(point, horizontal(point.subtract(target)), false));
				}
			}
		}
	}

	static double score(Candidate candidate, Vec3 aircraft, Vec3 preferred,
		boolean directCorridor, boolean retained) {
		double score = aircraft.distanceTo(candidate.point()) * 0.55;
		if (preferred != null) score += preferred.distanceTo(candidate.point()) * 0.16;
		if (candidate.formation()) score -= 18.0;
		if (directCorridor) score -= 12.0;
		if (retained) score -= 24.0;
		return score;
	}

	static boolean missileEnvelope(Vec3 point, Vec3 target) {
		return MicroMissilePolicy.launchEnvelope(point.subtract(target).horizontalDistance(),
			point.y - target.y);
	}

	private static Vec3 horizontal(Vec3 value) {
		if (value == null) return new Vec3(0, 0, 1);
		Vec3 flat = value.multiply(1, 0, 1);
		return flat.lengthSqr() < 0.001 ? new Vec3(0, 0, 1) : flat.normalize();
	}

	record Candidate(Vec3 point, Vec3 attackAxis, boolean formation) {}
}
