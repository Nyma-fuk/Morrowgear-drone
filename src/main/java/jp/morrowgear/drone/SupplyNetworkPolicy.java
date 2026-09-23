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
	public static final long MISSING_DRONE_TICKS = 1200;
	public static final long NOTICE_INTERVAL = 1200;

	private SupplyNetworkPolicy() {}

	public static int demand(int minimum, int stock, int incoming) {
		return (int)Math.max(0L, Math.min(MAX_STOCK, minimum)
			- (long)Math.max(0, stock) - Math.max(0, incoming));
	}

	public static int available(int stock, int reserved) {
		return Math.max(0, stock - Math.max(0, reserved));
	}

	public static boolean dispatchable(Readiness state) {
		return state != null && state.cargoRole() && state.idle() && state.empty()
			&& !state.manualWing() && !state.mission() && !state.pendingTask()
			&& !state.recovery() && !state.combat() && !state.salvage()
			&& !state.service() && !state.existingRoute() && !state.powerLost()
			&& state.batteryPercent() >= (state.docked()
				? AutonomousTaskForcePolicy.MINIMUM_DOCKED_BATTERY
				: AutonomousTaskForcePolicy.MINIMUM_FIELD_BATTERY);
	}

	public static boolean protectedGroup(String group) {
		return !"ALPHA".equals(group);
	}

	public static List<Rule> defaults() {
		return List.of(new Rule(SupplyKind.FUEL, 16, 100), new Rule(SupplyKind.REPAIR, 8, 90),
			new Rule(SupplyKind.GUN, 8, 80), new Rule(SupplyKind.LASER, 8, 70),
			new Rule(SupplyKind.MISSILE, 4, 60));
	}

	public record Rule(SupplyKind kind, int minimum, int priority) {
		public Rule {
			if (kind == null || minimum < 0 || minimum > MAX_STOCK || priority < 0 || priority > 100)
				throw new IllegalArgumentException("Invalid supply rule");
		}
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
