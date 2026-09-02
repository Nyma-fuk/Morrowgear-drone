package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AutonomousTaskForcePolicy {
	public static final int MAX_UNITS = WingMembershipPolicy.MAX_MEMBERS;
	public static final int MINIMUM_FIELD_BATTERY = 35;
	public static final int MINIMUM_DOCKED_BATTERY = DroneServicePolicy.NORMAL_SORTIE_POWER;
	public static final int MINIMUM_HEALTH = 30;

	private AutonomousTaskForcePolicy() {
	}

	public static boolean operationallyIdle(boolean powerLost, boolean serviceReturn,
		boolean combat, boolean emergencyIntercept, int recoveryLevel,
		boolean fieldOperation, boolean securityPatrol, boolean salvageAssignment,
		boolean cargoRoute, DroneMode mode, boolean docked) {
		return !powerLost && !serviceReturn && !combat && !emergencyIntercept
			&& recoveryLevel <= 0 && !fieldOperation && !securityPatrol
			&& !salvageAssignment && !cargoRoute
			&& (mode == DroneMode.STANDBY || docked);
	}

	public static Plan plan(FieldOperationType type, int radius, List<Candidate> candidates) {
		if (type == null || type == FieldOperationType.NONE || candidates == null) return Plan.blocked("INVALID OPERATION");
		Map<DroneRole, Integer> desired = desiredRoster(type, radius);
		Map<DroneRole, List<Candidate>> available = new EnumMap<>(DroneRole.class);
		for (Candidate candidate : candidates) {
			if (candidate == null || !candidate.ready()) continue;
			available.computeIfAbsent(candidate.role(), ignored -> new ArrayList<>()).add(candidate);
		}
		Comparator<Candidate> order = Comparator
			.comparing(Candidate::docked)
			.thenComparing(Comparator.comparingInt(Candidate::batteryPercent).reversed())
			.thenComparing(Comparator.comparingInt(Candidate::healthPercent).reversed())
			.thenComparingDouble(Candidate::distanceSquared)
			.thenComparing(Candidate::unitId);
		available.values().forEach(list -> list.sort(order));
		if (available.getOrDefault(DroneRole.ENGINEER, List.of()).isEmpty()) {
			return Plan.blocked("ENGINEER UNAVAILABLE");
		}

		List<Candidate> selected = new ArrayList<>();
		Map<DroneRole, Integer> assigned = new EnumMap<>(DroneRole.class);
		Map<DroneRole, Integer> missing = new LinkedHashMap<>();
		for (Map.Entry<DroneRole, Integer> entry : desired.entrySet()) {
			List<Candidate> roleCandidates = available.getOrDefault(entry.getKey(), List.of());
			int count = Math.min(entry.getValue(), Math.min(roleCandidates.size(), MAX_UNITS - selected.size()));
			for (int index = 0; index < count; index++) selected.add(roleCandidates.get(index));
			if (count > 0) assigned.put(entry.getKey(), count);
			if (count < entry.getValue()) missing.put(entry.getKey(), entry.getValue() - count);
			if (selected.size() >= MAX_UNITS) break;
		}
		return new Plan(List.copyOf(selected), assigned, missing, "READY");
	}

	static Map<DroneRole, Integer> desiredRoster(FieldOperationType type, int radius) {
		LinkedHashMap<DroneRole, Integer> desired = new LinkedHashMap<>();
		desired.put(DroneRole.ENGINEER, 2);
		desired.put(DroneRole.SCOUT, 1);
		desired.put(DroneRole.CARGO, type == FieldOperationType.EXCAVATE ? 2 : 1);
		desired.put(DroneRole.SECURITY, radius >= largeRadius(type) ? 2 : 1);
		desired.put(DroneRole.FIELD, 1);
		return Collections.unmodifiableMap(desired);
	}

	private static int largeRadius(FieldOperationType type) {
		return switch (type) {
			case ORE -> 36;
			case FORESTRY -> 18;
			case EXCAVATE -> 6;
			case NONE -> Integer.MAX_VALUE;
		};
	}

	public record Candidate(int entityId, String unitId, DroneRole role, boolean idle,
		boolean docked, int batteryPercent, int healthPercent, double distanceSquared) {
		public Candidate {
			unitId = unitId == null ? "" : unitId;
			role = role == null ? DroneRole.FIELD : role;
			batteryPercent = Math.max(0, Math.min(100, batteryPercent));
			healthPercent = Math.max(0, Math.min(100, healthPercent));
			distanceSquared = Math.max(0.0, distanceSquared);
		}

		public boolean ready() {
			int requiredBattery = docked ? MINIMUM_DOCKED_BATTERY : MINIMUM_FIELD_BATTERY;
			return idle && batteryPercent >= requiredBattery && healthPercent >= MINIMUM_HEALTH;
		}
	}

	public record Plan(List<Candidate> selected, Map<DroneRole, Integer> assigned,
		Map<DroneRole, Integer> missing, String status) {
		public Plan {
			selected = selected == null ? List.of() : List.copyOf(selected);
			assigned = assigned == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(assigned));
			missing = missing == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(missing));
			status = status == null ? "" : status;
		}

		static Plan blocked(String status) {
			return new Plan(List.of(), Map.of(), Map.of(), status);
		}

		public boolean executable() {
			return !selected.isEmpty() && assigned.getOrDefault(DroneRole.ENGINEER, 0) > 0;
		}

		public String rosterLabel() {
			return "ENG " + assigned.getOrDefault(DroneRole.ENGINEER, 0)
				+ " / SCT " + assigned.getOrDefault(DroneRole.SCOUT, 0)
				+ " / CRG " + assigned.getOrDefault(DroneRole.CARGO, 0)
				+ " / SEC " + assigned.getOrDefault(DroneRole.SECURITY, 0)
				+ " / FLD " + assigned.getOrDefault(DroneRole.FIELD, 0);
		}

		public String missingLabel() {
			if (missing.isEmpty()) return "FULL CAPABILITY";
			List<String> labels = new ArrayList<>();
			missing.forEach((role, count) -> labels.add(TacticalUiPolicy.moduleLabel(role) + " x" + count));
			return "DEGRADED / MISSING " + String.join(", ", labels);
		}
	}
}
