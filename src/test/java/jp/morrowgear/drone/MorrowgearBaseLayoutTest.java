package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class MorrowgearBaseLayoutTest {
	@Test
	void baseProvidesThirtyTwoDistinctDocksAcrossTwoHeightLayers() {
		var stations = MorrowgearBaseLayout.dockStations();
		assertEquals(32, stations.size());
		assertEquals(32, new HashSet<>(stations).size());
		assertEquals(16, stations.stream().filter(station -> station.y() == 0).count());
		assertEquals(16, stations.stream()
			.filter(station -> station.y() == MorrowgearBaseLayout.UPPER_DOCK_Y).count());
	}

	@Test
	void fourWallsRetainWideCardinalGateOpenings() {
		assertTrue(MorrowgearBaseLayout.gateOpening(0, -40));
		assertTrue(MorrowgearBaseLayout.gateOpening(0, 39));
		assertTrue(MorrowgearBaseLayout.gateOpening(-40, 0));
		assertTrue(MorrowgearBaseLayout.gateOpening(39, 0));
		assertFalse(MorrowgearBaseLayout.gateOpening(8, -40));
		assertFalse(MorrowgearBaseLayout.gateOpening(39, 8));
	}
}
