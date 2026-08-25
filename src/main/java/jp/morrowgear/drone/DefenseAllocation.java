package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class DefenseAllocation {
	private static final int REBALANCE_INTERVAL = 20;
	private static final float RELIEF_HEALTH_RATIO = 0.35f;
	private static final Map<UUID, State> STATES = new HashMap<>();

	private DefenseAllocation() {
	}

	static Assignment assign(ServerLevel level, ServerPlayer owner, List<DroneEntity> drones,
		ThreatAssessment.Snapshot threat, DroneEntity drone) {
		long tick = level.getGameTime();
		State state = STATES.get(owner.getUUID());
		if (state == null || state.calculatedTick != tick) {
			state = calculate(owner, drones, threat, tick, state);
			STATES.put(owner.getUUID(), state);
		}
		int slot = state.selected.indexOf(drone.getUUID());
		return new Assignment(slot >= 0, Math.max(0, slot), state.selected.size());
	}

	private static State calculate(ServerPlayer owner, List<DroneEntity> drones,
		ThreatAssessment.Snapshot threat, long tick, State previous) {
		int missionCount = (int) drones.stream().filter(drone -> drone.mode() == DroneMode.WAYPOINT).count();
		int required = DefenseResourcePolicy.requiredDefenders(threat, drones.size(), missionCount);
		if (required == 0) return new State(tick, tick, List.of());

		Set<UUID> prior = previous == null ? Set.of() : new HashSet<>(previous.selected);
		boolean damagedDefender = drones.stream().anyMatch(drone -> prior.contains(drone.getUUID())
			&& drone.getHealth() / drone.getMaxHealth() < RELIEF_HEALTH_RATIO);
		boolean selectionValid = previous != null && previous.selected.size() == required
			&& drones.stream().map(DroneEntity::getUUID).collect(java.util.stream.Collectors.toSet()).containsAll(previous.selected);
		if (selectionValid && !damagedDefender && tick - previous.rebalancedTick < REBALANCE_INTERVAL) {
			return new State(tick, previous.rebalancedTick, previous.selected);
		}

		List<DroneEntity> candidates = new ArrayList<>(drones);
		candidates.sort(Comparator
			.comparingInt((DroneEntity candidate) -> candidate.getHealth() / candidate.getMaxHealth() < RELIEF_HEALTH_RATIO ? 1 : 0)
			.thenComparingInt(candidate -> DefenseResourcePolicy.availabilityRank(candidate.mode()))
			.thenComparingInt(candidate -> prior.contains(candidate.getUUID()) ? 0 : 1)
			.thenComparingDouble(candidate -> candidate.distanceToSqr(owner))
			.thenComparing(DroneEntity::unitId));
		List<UUID> selected = candidates.stream().limit(required).map(DroneEntity::getUUID).toList();
		return new State(tick, tick, selected);
	}

	record Assignment(boolean assigned, int slot, int count) {
	}

	private record State(long calculatedTick, long rebalancedTick, List<UUID> selected) {
	}
}
