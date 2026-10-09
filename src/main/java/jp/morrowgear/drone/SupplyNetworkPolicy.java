package jp.morrowgear.drone;

import java.util.List;

import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;

public final class SupplyNetworkPolicy {
	public static final int MAX_ROUTES = 64;
	public static final int MAX_JOBS = 64;
	public static final int MAX_CANDIDATES = 128;
	public static final int MAX_SOURCE_SLOTS = 54;
	public static final int MAX_STOCK = 576;
	public static final int MAX_TRIP_ITEMS = 64;
	public static final int MAX_ROUTE_DISTANCE = 256;
	public static final int DISCOVERY_RADIUS = 32;
	public static final int DISCOVERY_VERTICAL = 8;
	public static final int MAX_DISCOVERED_NODES = 64;
	public static final long MISSING_DRONE_TICKS = 1200;
	public static final long NOTICE_INTERVAL = 1200;

	private SupplyNetworkPolicy() {}

	public static int demand(int minimum, int stock, int incoming) {
		return demand(minimum, minimum, stock, incoming);
	}

	public static int demand(int trigger, int target, int stock, int incoming) {
		return demand(trigger, target, stock, incoming, false);
	}

	public static int demand(int trigger, int target, int stock, int incoming, boolean refillActive) {
		long projected = (long)Math.max(0, stock) + Math.max(0, incoming);
		int checkedTrigger = Math.clamp(trigger, 0, MAX_STOCK);
		int checkedTarget = Math.clamp(target, checkedTrigger, MAX_STOCK);
		return !refillActive && projected >= checkedTrigger ? 0 : (int)Math.max(0L, checkedTarget - projected);
	}

	public static int available(int stock, int reserved) {
		return Math.max(0, stock - Math.max(0, reserved));
	}

	public static boolean dispatchable(Readiness state) {
		return state != null && state.cargoRole() && state.idle() && state.empty()
			&& (!state.manualWing() || state.docked()) && !state.mission() && !state.pendingTask()
			&& !state.recovery() && !state.combat() && !state.salvage()
			&& !state.service() && !state.existingRoute() && !state.powerLost()
			&& state.batteryPercent() >= (state.docked()
				? AutonomousTaskForcePolicy.MINIMUM_DOCKED_BATTERY
				: AutonomousTaskForcePolicy.MINIMUM_FIELD_BATTERY);
	}

	public static boolean protectedGroup(String group) {
		return !("ALPHA".equals(group) || group != null && group.startsWith("BASE-WING-"));
	}

	public static List<Rule> defaults() {
		return List.of(new Rule(SupplyKind.FUEL, 6, 16, 100), new Rule(SupplyKind.REPAIR, 3, 8, 90),
			new Rule(SupplyKind.GUN, 3, 8, 80), new Rule(SupplyKind.LASER, 3, 8, 70),
			new Rule(SupplyKind.MISSILE, 2, 4, 60));
	}

	public record Rule(SupplyKind kind, int minimum, int target, int priority) {
		public Rule {
			if (kind == null || minimum < 0 || minimum > MAX_STOCK || target < minimum || target > MAX_STOCK
				|| priority < 0 || priority > 100)
				throw new IllegalArgumentException("Invalid supply rule");
		}
		public Rule(SupplyKind kind, int minimum, int priority) { this(kind, minimum, minimum, priority); }
	}

	public record Readiness(boolean cargoRole, boolean idle, boolean empty, boolean manualWing,
		boolean mission, boolean pendingTask, boolean recovery, boolean combat, boolean salvage,
		boolean service, boolean existingRoute, boolean powerLost, boolean docked, int batteryPercent) {}

	public enum Status {
		DISABLED, READY, IN_TRANSIT, RETURNING, RECHARGING, WAIT_CHUNK, NO_IDLE_CARGO,
		SOURCE_SHORTAGE, SOURCE_LOST, DOCK_LOST, DOCK_FULL, RESERVATION_INVALID,
		ASSIGNMENT_LOST, CARGO_MISMATCH, CANCELLED, LOAD_RETAINED, RUNTIME_UNAVAILABLE,
		ACCESS_DENIED, CAPACITY_LIMIT;

		public boolean error() {
			return switch (this) {
				case SOURCE_SHORTAGE, SOURCE_LOST, DOCK_LOST, DOCK_FULL, RESERVATION_INVALID,
					ASSIGNMENT_LOST, CARGO_MISMATCH, LOAD_RETAINED, RUNTIME_UNAVAILABLE,
					ACCESS_DENIED, CAPACITY_LIMIT -> true;
				default -> false;
			};
		}
	}
}
