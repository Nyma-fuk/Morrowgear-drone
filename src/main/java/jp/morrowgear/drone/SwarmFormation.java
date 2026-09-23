package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SwarmFormation {
	static final int WING_SIZE = 8;
	static final int HIERARCHICAL_FLEET_SIZE = 13;
	enum Pattern { SOLO, COLUMN, DELTA, STAR }

	private SwarmFormation() {
	}

	static Pattern patternFor(int count) {
		if (count <= 1) return Pattern.SOLO;
		if (count == 2) return Pattern.COLUMN;
		if (count <= WING_SIZE) return Pattern.DELTA;
		return Pattern.STAR;
	}

	static boolean hierarchical(int count) {
		return count >= HIERARCHICAL_FLEET_SIZE;
	}

	static int wingIndex(int missionIndex) {
		return Math.max(0, missionIndex) / WING_SIZE;
	}

	static int wingSize(int expected, int missionIndex) {
		int start = wingIndex(missionIndex) * WING_SIZE;
		return Math.max(1, Math.min(WING_SIZE, Math.max(0, expected - start)));
	}

	static int wingCount(int expected) {
		return Math.max(1, (Math.max(1, expected) + WING_SIZE - 1) / WING_SIZE);
	}

	static int wingLocalIndex(int missionIndex) {
		return Math.floorMod(missionIndex, WING_SIZE);
	}

	static Vec3 wingTravelOffset(int wingIndex, int wingCount, Vec3 forward, boolean constrained) {
		if (wingCount <= 1) return Vec3.ZERO;
		Vec3 horizontal = forward.multiply(1, 0, 1);
		if (horizontal.lengthSqr() < 0.001) horizontal = new Vec3(0, 0, 1);
		horizontal = horizontal.normalize();
		if (constrained) return horizontal.scale(-wingIndex * WING_SIZE * AirframeEnvelope.SLOT_DISTANCE);
		Vec3 right = new Vec3(-horizontal.z, 0, horizontal.x);
		double centered = wingIndex - (wingCount - 1) * 0.5;
		double lateral = centered * 30.0;
		double vertical = (wingIndex % 3) * AirframeEnvelope.LAYER_HEIGHT;
		double longitudinal = -(wingIndex / 3) * 10.0;
		return right.scale(lateral).add(horizontal.scale(longitudinal)).add(0, vertical, 0);
	}

	static Vec3 wingOrbitCenter(Vec3 center, int wingIndex) {
		return center.add(0, Math.max(0, wingIndex) * AirframeEnvelope.LAYER_HEIGHT, 0);
	}

	static double wingArrivalRadius(int expected) {
		int wings = wingCount(expected);
		double outerAnchor = (wings - 1) * 0.5 * 30.0;
		return outerAnchor + formationDiameter(Math.min(WING_SIZE, expected)) * 0.6 + 8.0;
	}

	static double orbitRadius(int count) {
		return AirframeEnvelope.orbitRadius(count);
	}

	static double layeredOrbitRadius(int count, int layerIndex, int layerCount) {
		double baseRadius = orbitRadius(count);
		if (layerCount <= 1) return baseRadius;
		int safeLayer = Math.max(0, Math.min(layerIndex, layerCount - 1));
		double taper = Math.min(18.0, (layerCount - 1) * 1.8);
		double lowerLayerWeight = 1.0 - safeLayer / (double) (layerCount - 1);
		return baseRadius + taper * lowerLayerWeight;
	}

	static double layeredAngularSpeed(double baseSpeed, int layerIndex, int layerCount) {
		if (layerCount <= 1) return baseSpeed;
		int safeLayer = Math.max(0, Math.min(layerIndex, layerCount - 1));
		double progress = safeLayer / (double) (layerCount - 1);
		return baseSpeed * (0.82 + progress * 0.36);
	}

	static double layeredPhaseOffset(double basePhase, int layerIndex) {
		return basePhase + Math.max(0, layerIndex) * Math.PI * (3.0 - Math.sqrt(5.0));
	}

	static boolean canProceedWithAvailableUnits(boolean mustering, int expected, int available, long elapsedTicks) {
		return available >= expected || !mustering || elapsedTicks >= 300;
	}

	static int leaderIndex(String missionId, int count) {
		return count <= 0 ? 0 : Math.floorMod(missionId.hashCode(), count);
	}

	static int formationIndex(int memberIndex, int leaderIndex, int count) {
		return count <= 0 ? 0 : Math.floorMod(memberIndex - leaderIndex, count);
	}

	static int plannedFormationSize(int expected, List<Integer> missionIndexes) {
		int indexedSize = missionIndexes.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
		return Math.max(1, Math.max(expected, indexedSize));
	}

	static Vec3 nonRegressiveMergeTarget(Vec3 current, Vec3 destination, Vec3 formationSlot) {
		Vec3 forward = destination.subtract(current).multiply(1, 0, 1);
		if (forward.lengthSqr() < 0.01) return formationSlot;
		forward = forward.normalize();
		Vec3 slotDelta = formationSlot.subtract(current);
		double longitudinal = slotDelta.dot(forward);
		if (longitudinal >= -0.25) return formationSlot;

		Vec3 lateralAndVertical = slotDelta.subtract(forward.scale(longitudinal));
		double advance = Math.min(2.4, Math.max(0.8, current.distanceTo(destination) * 0.08));
		return current.add(forward.scale(advance)).add(lateralAndVertical.scale(0.18));
	}

	static double maxSpread(List<Vec3> positions) {
		double maximum = 0.0;
		for (int first = 0; first < positions.size(); first++) {
			for (int second = first + 1; second < positions.size(); second++) {
				maximum = Math.max(maximum, positions.get(first).distanceTo(positions.get(second)));
			}
		}
		return maximum;
	}

	static boolean convergenceRequired(double spread, int count) {
		return spread > convergenceThreshold(count);
	}

	static boolean convergenceComplete(double spread, int count) {
		return spread <= convergenceThreshold(count) * 0.68;
	}

	static int convergenceQuorum(int count) {
		if (count <= 1) return 1;
		return Math.max(2, (int) Math.ceil(count * 0.6));
	}

	static List<Integer> cohesiveCluster(List<Vec3> positions, int expectedCount) {
		if (positions.isEmpty()) return List.of();
		List<Integer> best = List.of();
		for (int seed = 0; seed < positions.size(); seed++) {
			List<Integer> cluster = cohesiveClusterAround(positions, expectedCount, seed);
			if (cluster.size() > best.size()) best = List.copyOf(cluster);
		}
		return best;
	}

	static List<Integer> cohesiveClusterAround(List<Vec3> positions, int expectedCount, int seed) {
		if (positions.isEmpty() || seed < 0 || seed >= positions.size()) return List.of();
		double limit = convergenceThreshold(Math.max(expectedCount, positions.size())) * 0.68;
		List<Integer> candidates = new ArrayList<>();
		for (int index = 0; index < positions.size(); index++) candidates.add(index);
		candidates.sort(Comparator.comparingDouble(index -> positions.get(seed).distanceTo(positions.get(index))));
		List<Integer> cluster = new ArrayList<>();
		for (int candidate : candidates) {
			boolean fits = cluster.stream().allMatch(member ->
				positions.get(member).distanceTo(positions.get(candidate)) <= limit);
			if (fits) cluster.add(candidate);
		}
		return List.copyOf(cluster);
	}

	static boolean individuallyArrived(int missionStage, double distance, double arrivalRadius,
		boolean formationComplete) {
		return missionStage == DroneEntity.MISSION_MOVING && formationComplete && distance <= arrivalRadius;
	}

	private static double convergenceThreshold(int count) {
		return Math.max(15.0, Math.max(orbitRadius(count) * 2.0 + 6.0,
			formationDiameter(count) + 5.0));
	}

	static double formationDiameter(int count) {
		if (count <= 1) return 0.0;
		Vec3 forward = new Vec3(0, 0, 1);
		List<Vec3> slots = new ArrayList<>();
		for (int index = 0; index < count; index++) slots.add(movingOffset(index, count, forward));
		return maxSpread(slots);
	}

	static Vec3 orbitPosition(Vec3 center, int index, int count, long tick, double angularSpeed) {
		return orbitPosition(center, index, count, tick, angularSpeed, 0.0);
	}

	static Vec3 orbitPosition(Vec3 center, int index, int count, long tick, double angularSpeed,
		double phaseOffset) {
		return orbitPosition(center, index, count, tick, angularSpeed, phaseOffset, orbitRadius(count));
	}

	static Vec3 orbitPosition(Vec3 center, int index, int count, long tick, double angularSpeed,
		double phaseOffset, double radius) {
		double angle = tick * AirframeEnvelope.angularSpeed(angularSpeed, radius) + phaseOffset
			- index * Math.PI * 2.0 / Math.max(1, count);
		double vertical = (index % 3 - 1) * 0.45;
		return center.add(Math.cos(angle) * radius, vertical, Math.sin(angle) * radius);
	}

	static Vec3 orbitEntryPosition(Vec3 center, int index, int count, long tick,
		double angularSpeed, double progress, double phaseOffset) {
		return orbitEntryPosition(center, index, count, tick, angularSpeed, progress, phaseOffset,
			orbitRadius(count));
	}

	static Vec3 orbitEntryPosition(Vec3 center, int index, int count, long tick,
		double angularSpeed, double progress, double phaseOffset, double radius) {
		double trailSpacing = Math.min(0.72, AirframeEnvelope.SLOT_DISTANCE / radius);
		double fullSpacing = Math.PI * 2.0 / Math.max(1, count);
		double clamped = Math.max(0.0, Math.min(1.0, progress));
		double eased = 1.0 - (1.0 - clamped) * (1.0 - clamped);
		double spacing = trailSpacing + (fullSpacing - trailSpacing) * eased;
		double angle = tick * AirframeEnvelope.angularSpeed(angularSpeed, radius) + phaseOffset - index * spacing;
		double vertical = (index % 3 - 1) * 0.45;
		return center.add(Math.cos(angle) * radius, vertical, Math.sin(angle) * radius);
	}

	static Vec3 movingOffset(int index, int count, Vec3 forward) {
		return movingOffset(index, count, forward, false);
	}

	static Vec3 movingOffset(int index, int count, Vec3 forward, boolean constrained) {
		if (index == 0 || count <= 1) return Vec3.ZERO;
		Vec3 direction = constrained ? forward : forward.multiply(1, 0, 1);
		if (direction.lengthSqr() < 0.001) direction = new Vec3(0, 0, 1);
		direction = direction.normalize();
		Vec3 horizontal = direction.multiply(1, 0, 1);
		if (horizontal.lengthSqr() < 0.001) horizontal = new Vec3(0, 0, 1);
		horizontal = horizontal.normalize();
		Vec3 right = new Vec3(-horizontal.z, 0, horizontal.x);
		if (constrained) {
			return direction.scale(-index * AirframeEnvelope.SLOT_DISTANCE);
		}
		double vertical = (index % 3 - 1) * 0.85;
		return switch (patternFor(count)) {
			case SOLO -> Vec3.ZERO;
			case COLUMN -> direction.scale(-index * AirframeEnvelope.SLOT_DISTANCE).add(0, vertical, 0);
			case DELTA -> {
				int row = (index + 1) / 2;
				double side = index % 2 == 1 ? -1.0 : 1.0;
				double lateral = side * (4.0 + (row - 1) * 2.7);
				yield direction.scale(-row * 4.3).add(right.scale(lateral)).add(0, vertical, 0);
			}
			case STAR -> {
				int spoke = (index - 1) % 5;
				int ring = (index - 1) / 5 + 1;
				double angle = spoke * Math.PI * 2.0 / 5.0;
				double radius = ring * 6.0;
				double centerBehind = radius + 1.8;
				yield right.scale(Math.cos(angle) * radius)
					.add(direction.scale(Math.sin(angle) * radius - centerBehind))
					.add(0, vertical, 0);
			}
		};
	}
}
