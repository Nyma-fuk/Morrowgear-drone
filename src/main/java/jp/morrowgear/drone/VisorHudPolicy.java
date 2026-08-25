package jp.morrowgear.drone;

public final class VisorHudPolicy {
	public static final int DEFAULT_RANGE = 48;
	public static final int NORMAL_MARKER_BUDGET = 12;
	public static final int NORMAL_LABEL_BUDGET = 2;
	public static final int LEADER_MARKER_BUDGET = 4;
	public static final int OFFSCREEN_DIRECTION_BUDGET = 4;
	public static final double SAFE_ZONE_RATIO = 0.05;
	public static final int NORMAL_SAFE_ZONE_ALPHA = 52;
	public static final int NORMAL_ALPHA = 220;

	private VisorHudPolicy() {}

	public enum Density { INDIVIDUAL, WING, OPERATION, FLEET }
	public enum Detail { UNIT, WING, OPERATION, FLEET }
	public enum CombatAssignment { UNIT_IDS, WING, TASK_FORCE }

	public record DisplayPlan(Detail detail, int markerBudget, int labelBudget,
		int edgeBudget, int alertRows, boolean compactContext) {}

	public static Density density(int unitCount) {
		if (unitCount <= 8) return Density.INDIVIDUAL;
		if (unitCount <= 32) return Density.WING;
		if (unitCount <= 128) return Density.OPERATION;
		return Density.FLEET;
	}

	public static int markerBudget(int unitCount) {
		return switch (density(unitCount)) {
			case INDIVIDUAL -> Math.min(8, Math.max(0, unitCount));
			case WING -> NORMAL_MARKER_BUDGET;
			case OPERATION, FLEET -> 8;
		};
	}

	public static int worldLabelBudget(int unitCount) {
		return density(unitCount) == Density.INDIVIDUAL ? Math.min(NORMAL_LABEL_BUDGET, unitCount) : 2;
	}

	public static DisplayPlan displayPlan(int unitCount, int wingCount, int actionableCount,
		int combatCount, int width, int height) {
		boolean constrained = width < 1280 || height < 720;
		Detail detail = unitCount <= 8 ? Detail.UNIT
			: unitCount <= 32 ? Detail.WING
			: unitCount <= 128 ? Detail.OPERATION : Detail.FLEET;
		int baseMarkers = switch (detail) {
			case UNIT -> Math.min(8, unitCount);
			case WING -> Math.min(12, Math.max(4, wingCount * 2));
			case OPERATION -> Math.min(10, Math.max(4, wingCount));
			case FLEET -> Math.min(8, Math.max(3, wingCount));
		};
		int markerBudget = Math.min(constrained ? 8 : 14,
			baseMarkers + Math.min(4, actionableCount) + (combatCount > 0 ? 2 : 0));
		int labelBudget = constrained ? 1 : detail == Detail.UNIT ? 3 : 2;
		int edgeBudget = combatCount > 0 ? 6 : OFFSCREEN_DIRECTION_BUDGET;
		int alertRows = constrained ? 1 : actionableCount >= 3 ? 3 : Math.min(2, actionableCount);
		return new DisplayPlan(detail, markerBudget, labelBudget, edgeBudget,
			Math.max(actionableCount > 0 ? 1 : 0, alertRows), constrained || detail == Detail.FLEET);
	}

	public static CombatAssignment combatAssignment(int unitCount, int wingCount) {
		if (unitCount <= 2) return CombatAssignment.UNIT_IDS;
		return wingCount <= 1 ? CombatAssignment.WING : CombatAssignment.TASK_FORCE;
	}

	public static int engagementDisplayLimit(Detail detail) {
		return detail == Detail.FLEET ? 4 : 6;
	}

	public static int visibleAlertCount(int availableAlerts, int rowBudget) {
		return Math.max(0, Math.min(availableAlerts, rowBudget));
	}

	public static int informationPriority(boolean pinned, boolean selectedWing, boolean combat,
		int threatScore, int recoveryLevel, int flightPower, int weaponPower,
		boolean serviceReturn) {
		if (pinned) return 0;
		if (threatScore >= 28 || flightPower <= 0) return 0;
		if (combat || recoveryLevel > 0 || serviceReturn || flightPower <= 20
			|| weaponPower <= 10) return 1;
		if (selectedWing || threatScore >= 8 || flightPower <= 35 || weaponPower <= 25) return 2;
		return 3;
	}

	public static int topStripWidth(int guiWidth) {
		return Math.max(320, guiWidth - Math.max(28, guiWidth * 84 / 1920));
	}

	public static int sidePanelWidth(int guiWidth) {
		return Math.min(380, Math.max(300, guiWidth * 380 / 1920));
	}

	public static int actionRailWidth(int guiWidth) {
		return Math.min(820, Math.max(480, guiWidth * 820 / 1920));
	}

	public static int markerAlpha(double screenDistanceFromCenter, double safeRadius,
		boolean pinned, boolean actionable) {
		if (pinned || actionable || screenDistanceFromCenter >= safeRadius) return NORMAL_ALPHA;
		return NORMAL_SAFE_ZONE_ALPHA;
	}

	public static int alertPriority(int threatScore, int recoveryLevel, int flightPower,
		boolean dockPathBlocked) {
		if (threatScore >= 28 || flightPower <= 0 || dockPathBlocked) return 0;
		if (recoveryLevel > 0 || flightPower <= 20) return 1;
		if (threatScore >= 8 || flightPower <= 35) return 2;
		return 3;
	}

	public static boolean clusterTogether(double angularDifferenceDegrees, double worldDistance,
		boolean sameWingOrMission, boolean currentlyClustered) {
		double threshold = currentlyClustered ? 6.0 : 4.0;
		return sameWingOrMission && angularDifferenceDegrees <= threshold && worldDistance <= 8.0;
	}
}
