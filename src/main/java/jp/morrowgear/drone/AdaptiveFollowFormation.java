package jp.morrowgear.drone;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class AdaptiveFollowFormation {
	private AdaptiveFollowFormation() {
	}

	static Profile profile(int count, double openness, int ceilingClearance,
		boolean skyVisible, boolean fluidNearby, boolean dark, double ownerSpeed) {
		double open = Mth.clamp(openness, 0.0, 1.0);
		boolean column = ceilingClearance <= 3 || open < 0.42;
		double speed = Mth.clamp(ownerSpeed, 0.0, 1.2);
		double radius = column
			? 1.55 + open * 1.2
			: AirframeEnvelope.ORBIT_RADIUS + Math.sqrt(Math.max(1, count)) * 0.7 + speed * 1.5;
		double maximumRadius = column ? 3.0 : 13.0;
		radius = Mth.clamp(radius, 1.35, maximumRadius);
		double availableHeight = Math.max(1.15, ceilingClearance - 1.1);
		double baseHeight = column
			? Math.min(1.85, availableHeight)
			: AirframeEnvelope.ORBIT_HEIGHT + open * 0.8 + speed * 0.75 + (fluidNearby ? 1.0 : 0.0);
		if (!skyVisible) baseHeight = Math.min(baseHeight, availableHeight);
		double verticalSpacing = column ? 0.48 : Mth.clamp(0.9 + open * 0.3, 0.9, 1.2);
		double angularSpeed = column ? 0.0 : AirframeEnvelope.angularSpeed(
			Mth.clamp(0.042 - count * 0.0014 - speed * 0.006, 0.018, 0.038), radius);
		double forwardBias = (dark ? 1.1 : 0.0) + Math.min(1.8, speed * 2.2);
		return new Profile(radius, baseHeight, verticalSpacing, angularSpeed, forwardBias, column);
	}

	static Vec3 offset(Profile profile, int index, int count, long tick, Vec3 heading) {
		return offset(profile, index, count, tick, heading, 0.0);
	}

	static Vec3 offset(Profile profile, int index, int count, long tick, Vec3 heading,
		double phaseOffset) {
		return offset(profile, index, count, tick, heading,
			-Math.PI * 2.0 / ringCount(index, count), phaseOffset);
	}

	static Vec3 orbitEntryOffset(Profile profile, int index, int count, long tick,
		Vec3 heading, double progress, double phaseOffset) {
		if (profile.column()) return offset(profile, index, count, tick, heading, phaseOffset);
		double radius = Math.max(1.0, profile.radius());
		double trailSpacing = Math.min(0.72, AirframeEnvelope.SLOT_DISTANCE / radius);
		double fullSpacing = Math.PI * 2.0 / ringCount(index, count);
		double spacing = trailSpacing + (fullSpacing - trailSpacing) * smoothStep(progress);
		return offset(profile, index, count, tick, heading, -spacing, phaseOffset);
	}

	private static Vec3 offset(Profile profile, int index, int count, long tick,
		Vec3 heading, double phaseSpacing, double phaseOffset) {
		Vec3 forward = heading.multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.001) forward = new Vec3(0, 0, 1);
		forward = forward.normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		if (profile.column()) {
			return forward.scale(-4.0 - index * AirframeEnvelope.SLOT_DISTANCE)
				.add(0, profile.baseHeight(), 0);
		}
		int ringSlot = count > 8 ? index % 8 : index;
		double phase = tick * profile.angularSpeed() + phaseOffset + ringSlot * phaseSpacing;
		double layer = index / 8;
		double radius = profile.radius() + layer * 1.25;
		double largeFleetLayer = layer * AirframeEnvelope.LAYER_HEIGHT;
		return right.scale(Math.cos(phase) * radius)
			.add(forward.scale(Math.sin(phase) * radius + profile.forwardBias()))
			.add(0, profile.baseHeight() + (index % 3) * profile.verticalSpacing() + largeFleetLayer, 0);
	}

	private static int ringCount(int index, int count) {
		if (count <= 8) return Math.max(1, count);
		int ringStart = Math.max(0, index / 8) * 8;
		return Math.max(1, Math.min(8, count - ringStart));
	}

	private static double smoothStep(double progress) {
		double clamped = Mth.clamp(progress, 0.0, 1.0);
		return 1.0 - (1.0 - clamped) * (1.0 - clamped);
	}

	record Profile(double radius, double baseHeight, double verticalSpacing,
		double angularSpeed, double forwardBias, boolean column) {
	}
}
