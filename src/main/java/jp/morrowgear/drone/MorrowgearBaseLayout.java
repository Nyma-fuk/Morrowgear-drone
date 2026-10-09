package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;

final class MorrowgearBaseLayout {
	static final int HALF_SIZE = 40;
	static final int GATE_HALF_WIDTH = 4;
	static final int HANGAR_DISTANCE = 34;
	static final int DOCK_FOOTPRINT = 5;
	private static final int[] WALL_LANES = { -24, -8, 8, 24 };

	private MorrowgearBaseLayout() {}

	static List<DockStation> dockStations() {
		List<DockStation> result = new ArrayList<>(16);
		for (int lane : WALL_LANES) result.add(new DockStation(lane, 0, -HANGAR_DISTANCE, Direction.SOUTH));
		for (int lane : WALL_LANES) result.add(new DockStation(HANGAR_DISTANCE, 0, lane, Direction.WEST));
		for (int lane : WALL_LANES) result.add(new DockStation(lane, 0, HANGAR_DISTANCE, Direction.NORTH));
		for (int lane : WALL_LANES) result.add(new DockStation(-HANGAR_DISTANCE, 0, lane, Direction.EAST));
		return List.copyOf(result);
	}

	static boolean gateOpening(int x, int z) {
		boolean northSouthWall = Math.abs(z) >= HALF_SIZE - 1 && Math.abs(x) <= GATE_HALF_WIDTH;
		boolean eastWestWall = Math.abs(x) >= HALF_SIZE - 1 && Math.abs(z) <= GATE_HALF_WIDTH;
		return northSouthWall || eastWestWall;
	}

	static boolean hasClearDeparture(DockStation station) {
		int forwardX = station.x() + station.facing().getStepX() * 9;
		int forwardZ = station.z() + station.facing().getStepZ() * 9;
		return Math.abs(forwardX) < HALF_SIZE - 2 && Math.abs(forwardZ) < HALF_SIZE - 2;
	}

	record DockStation(int x, int y, int z, Direction facing) {}
}
