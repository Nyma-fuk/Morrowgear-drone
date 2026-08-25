package jp.morrowgear.drone;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TacticalUiPolicy {
	private TacticalUiPolicy() {
	}

	public static String moduleLabel(DroneRole role) {
		if (role == null) return "---";
		return switch (role) {
			case FIELD -> "FIELD";
			case SCOUT -> "SCT";
			case CARGO -> "CRG";
			case ENGINEER -> "ENG";
			case SECURITY -> "SEC";
			case SALVAGE -> "SLV";
		};
	}

	public static String actionLabel(String action) {
		if (action == null) return "UNKNOWN";
		return switch (action) {
			case "follow" -> "FOLLOW";
			case "standby" -> "STANDBY";
			case "return" -> "RETURN";
			case "dock" -> "DOCK";
			case "orbit" -> "ORBIT";
			case "decommission" -> "STORE";
			default -> "UNKNOWN";
		};
	}

	public static CommandAvailability availability(Collection<DroneRole> roles) {
		int selected = roles == null ? 0 : roles.size();
		int field = countAllowed(roles, MissionAssignmentPolicy.MissionKind.FIELD_OPERATION);
		int cargo = countAllowed(roles, MissionAssignmentPolicy.MissionKind.CARGO_ROUTE);
		int security = countAllowed(roles, MissionAssignmentPolicy.MissionKind.SECURITY_PATROL);
		int scouts = countRole(roles, DroneRole.SCOUT);
		int engineers = countRole(roles, DroneRole.ENGINEER);
		return new CommandAvailability(selected, field, cargo, security, scouts, engineers);
	}

	private static int countRole(Collection<DroneRole> roles, DroneRole expected) {
		if (roles == null || expected == null) return 0;
		return (int)roles.stream().filter(role -> role == expected).count();
	}

	public static int countAllowed(Collection<DroneRole> roles, MissionAssignmentPolicy.MissionKind mission) {
		if (roles == null || mission == null) return 0;
		return (int)roles.stream().filter(role -> MissionAssignmentPolicy.allows(role, mission)).count();
	}

	public static String radiusLabel(int radius) {
		return "半径 " + Math.max(0, radius) + " blocks";
	}

	public static int rosterPriority(boolean combat, boolean emergencyIntercept,
		int recoveryLevel, boolean activeMission, boolean docked, int batteryPercent) {
		if (combat || emergencyIntercept) return 0;
		if (recoveryLevel > 0 || batteryPercent < 25) return 1;
		if (activeMission) return 2;
		if (docked) return 4;
		return 3;
	}

	public static Set<Integer> normalizeSelection(SelectionScope scope, Collection<Integer> current,
		List<Integer> available) {
		LinkedHashSet<Integer> valid = new LinkedHashSet<>();
		if (available == null || available.isEmpty()) return valid;
		if (scope == SelectionScope.ALL) {
			valid.addAll(available);
			return valid;
		}
		if (current != null) for (int id : current) if (available.contains(id)) valid.add(id);
		if (scope == SelectionScope.UNIT) {
			int retained = valid.isEmpty() ? available.getFirst() : valid.iterator().next();
			valid.clear();
			valid.add(retained);
		} else if (valid.isEmpty()) {
			valid.add(available.getFirst());
		}
		return valid;
	}

	public enum SelectionScope { UNIT, WING, ALL }

	public record CommandAvailability(int selected, int field, int cargo, int security,
		int scouts, int engineers) {
		public boolean hasSelection() { return selected > 0; }
		public boolean fieldEnabled() { return field > 0; }
		public boolean workEnabled(FieldOperationType type) {
			return type != null && type != FieldOperationType.NONE && field > 0;
		}
		public String workBlockReason(FieldOperationType type) {
			if (!hasSelection()) return "操作対象が選択されていません";
			if (type == null || type == FieldOperationType.NONE) return "作業種別が不正です";
			if (field == 0) return type.label() + "に対応するフィールドユニットが必要です";
			return "";
		}
		public boolean cargoEnabled() { return cargo > 0; }
		public boolean securityEnabled() { return security > 0; }
	}
}
