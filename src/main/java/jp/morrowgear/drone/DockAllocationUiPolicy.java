package jp.morrowgear.drone;

/** Client-facing presentation contract for the shared Dock allocator. */
public final class DockAllocationUiPolicy {
	public static final int UNKNOWN_COUNT = -1;

	private DockAllocationUiPolicy() {
	}

	public static DockView localDock(String dockId, boolean occupied, boolean servicing, String aircraftId) {
		DockState state = !occupied ? DockState.FREE : servicing ? DockState.SERVICE : DockState.OCCUPIED;
		return new DockView(dockId, state, UNKNOWN_COUNT, UNKNOWN_COUNT, UNKNOWN_COUNT,
			normalizeId(aircraftId), false);
	}

	public static DockView synchronizedDock(String dockId, boolean reserved, boolean occupied,
		boolean servicing, String aircraftId, int queueCount, int holdingCount) {
		DockState state = occupied ? servicing ? DockState.SERVICE : DockState.OCCUPIED
			: reserved ? DockState.RESERVED : DockState.FREE;
		return new DockView(dockId, state, 0, Math.max(0, queueCount), Math.max(0, holdingCount),
			normalizeId(aircraftId), true);
	}

	public static AircraftView localAircraft(boolean assigned, boolean docked, String dockId) {
		return new AircraftView(assigned ? AircraftState.ASSIGNED : AircraftState.UNASSIGNED,
			normalizeId(dockId), UNKNOWN_COUNT, UNKNOWN_COUNT, false);
	}

	public static AircraftView synchronizedAircraft(boolean assigned, boolean docked, boolean holding,
		String dockId, int queuePosition, int queueCount) {
		AircraftState state = holding ? AircraftState.HOLDING : docked ? AircraftState.ASSIGNED
			: assigned ? AircraftState.RESERVED : AircraftState.UNASSIGNED;
		return new AircraftView(state, normalizeId(dockId), Math.max(0, queuePosition),
			Math.max(UNKNOWN_COUNT, queueCount), true);
	}

	public static String dockStateLabel(DockView view) {
		return view == null ? "UNKNOWN" : view.state().name();
	}

	public static String queueLabel(DockView view) {
		if (view == null || view.queueCount() == UNKNOWN_COUNT) return "Q --";
		if (view.queueCount() == 0) return "Q 0";
		return view.queuePosition() > 0 ? "Q " + view.queuePosition() + "/" + view.queueCount()
			: "Q " + view.queueCount();
	}

	public static String holdingLabel(DockView view) {
		return view == null || view.holdingCount() == UNKNOWN_COUNT ? "H --" : "H " + view.holdingCount();
	}

	public static String aircraftLabel(AircraftView view) {
		if (view == null) return "DOCK DATA --";
		return switch (view.state()) {
			case ASSIGNED -> "ASSIGNED " + idOrPending(view.dockId());
			case RESERVED -> "RESERVED " + idOrPending(view.dockId());
			case HOLDING -> view.queuePosition() > 0 && view.queueCount() > 0
				? "HOLDING Q " + view.queuePosition() + "/" + view.queueCount()
				: view.queuePosition() > 0 ? "HOLDING Q " + view.queuePosition() : "HOLDING";
			case UNASSIGNED -> "NO DOCK ALLOCATION";
		};
	}

	private static String normalizeId(String value) {
		return value == null ? "" : value.trim();
	}

	private static String idOrPending(String value) {
		return value == null || value.isBlank() ? "PENDING" : value;
	}

	public enum DockState { FREE, RESERVED, OCCUPIED, SERVICE }
	public enum AircraftState { ASSIGNED, RESERVED, HOLDING, UNASSIGNED }

	public record DockView(String dockId, DockState state, int queuePosition, int queueCount,
		int holdingCount, String aircraftId, boolean synchronizedData) {
		public DockView {
			dockId = normalizeId(dockId);
			if (dockId.isBlank() || state == null || queuePosition < UNKNOWN_COUNT
				|| queueCount < UNKNOWN_COUNT || holdingCount < UNKNOWN_COUNT)
				throw new IllegalArgumentException("Invalid Dock allocation view");
			if (queueCount == 0 && queuePosition > 0 || queueCount > 0 && queuePosition > queueCount)
				throw new IllegalArgumentException("Invalid Dock queue position");
			aircraftId = normalizeId(aircraftId);
		}
	}

	public record AircraftView(AircraftState state, String dockId, int queuePosition,
		int queueCount, boolean synchronizedData) {
		public AircraftView {
			if (state == null || queuePosition < UNKNOWN_COUNT || queueCount < UNKNOWN_COUNT)
				throw new IllegalArgumentException("Invalid aircraft allocation view");
			if (queueCount == 0 && queuePosition > 0 || queueCount > 0 && queuePosition > queueCount)
				throw new IllegalArgumentException("Invalid aircraft queue position");
			dockId = normalizeId(dockId);
		}
	}
}
