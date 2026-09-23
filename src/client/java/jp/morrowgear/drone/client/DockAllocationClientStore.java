package jp.morrowgear.drone.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jp.morrowgear.drone.DockAllocationUiPolicy.AircraftView;
import jp.morrowgear.drone.DockAllocationUiPolicy.DockView;

/** Holds the latest authoritative allocation snapshot without inventing missing server state. */
public final class DockAllocationClientStore {
	private static Map<Long, DockView> docks = Map.of();
	private static Map<Integer, AircraftView> aircraft = Map.of();

	private DockAllocationClientStore() {
	}

	public static void replace(List<DockEntry> dockEntries, List<AircraftEntry> aircraftEntries) {
		Map<Long, DockView> nextDocks = new HashMap<>();
		for (DockEntry entry : dockEntries == null ? List.<DockEntry>of() : dockEntries)
			nextDocks.put(entry.position(), entry.view());
		Map<Integer, AircraftView> nextAircraft = new HashMap<>();
		for (AircraftEntry entry : aircraftEntries == null ? List.<AircraftEntry>of() : aircraftEntries)
			nextAircraft.put(entry.entityId(), entry.view());
		docks = Map.copyOf(nextDocks);
		aircraft = Map.copyOf(nextAircraft);
	}

	public static Optional<DockView> dock(long position) { return Optional.ofNullable(docks.get(position)); }
	public static Optional<AircraftView> aircraft(int entityId) { return Optional.ofNullable(aircraft.get(entityId)); }
	public static void clear() { docks = Map.of(); aircraft = Map.of(); }

	public record DockEntry(long position, DockView view) {
		public DockEntry { if (view == null) throw new IllegalArgumentException("Dock view is required"); }
	}
	public record AircraftEntry(int entityId, AircraftView view) {
		public AircraftEntry { if (view == null) throw new IllegalArgumentException("Aircraft view is required"); }
	}
}
