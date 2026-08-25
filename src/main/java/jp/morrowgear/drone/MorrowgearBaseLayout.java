package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;

final class MorrowgearBaseLayout {
	static final int HALF_SIZE = 40;
	static final int GATE_HALF_WIDTH = 4;
	static final int UPPER_DOCK_Y = 7;
	private static final int[] LANES = { -27, -18, 18, 27 };

	private MorrowgearBaseLayout() {}

	static List<DockStation> dockStations() {
		List<DockStation> result = new ArrayList<>(32);
		for (int x : LANES) for (int z : LANES) {
			result.add(new DockStation(x, 0, z));
			result.add(new DockStation(x, UPPER_DOCK_Y, z));
		}
		return List.copyOf(result);
	}

	static boolean gateOpening(int x, int z) {
		boolean northSouthWall = Math.abs(z) >= HALF_SIZE - 1 && Math.abs(x) <= GATE_HALF_WIDTH;
		boolean eastWestWall = Math.abs(x) >= HALF_SIZE - 1 && Math.abs(z) <= GATE_HALF_WIDTH;
		return northSouthWall || eastWestWall;
	}

	record DockStation(int x, int y, int z) {}
}
