package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class VisorAirspaceMapPolicyTest {
	@Test void rangeIncludesMeshExtentsButRemainsBounded() {
		assertEquals(160.0, VisorAirspaceMapPolicy.range(0, 0, -40, 40, -40, 40));
		assertEquals(224.0, VisorAirspaceMapPolicy.range(0, 0, -200, 120, -80, 90));
		assertEquals(384.0, VisorAirspaceMapPolicy.range(0, 0, -800, 800, -800, 800));
	}

	@Test void projectionShowsAltitudeAndKeepsTheCenterStable() {
		var frame = new VisorAirspaceMapPolicy.Frame(100, 200, 480, 190, 10, 70, -20, 160);
		var center = VisorAirspaceMapPolicy.project(frame, 10, 70, -20);
		var high = VisorAirspaceMapPolicy.project(frame, 10, 110, -20);
		assertEquals(340, center.x());
		assertTrue(high.y() < center.y());
	}

	@Test void birdViewPanelHasAStableBottomCenterAnchorAtCommonResolutions() {
		for (int[] screen : new int[][] {{1920, 1080}, {1600, 900}, {1280, 720}, {960, 540}}) {
			var first = VisorAirspaceMapPolicy.panel(screen[0], screen[1]);
			var second = VisorAirspaceMapPolicy.panel(screen[0], screen[1]);
			assertEquals(first, second);
			assertEquals((screen[0] - first.width()) / 2, first.x());
			assertTrue(first.y() >= 96);
			assertTrue(first.x() >= 24 && first.x() + first.width() <= screen[0] - 24);
			assertTrue(first.y() + first.height() <= screen[1] - 92);
		}
	}

	@Test void terrainSamplingCoversBothRangeBoundaries() {
		assertEquals(-160.0, VisorAirspaceMapPolicy.sampleOffset(0, 160), 1.0e-9);
		assertEquals(0.0, VisorAirspaceMapPolicy.sampleOffset(5, 160), 1.0e-9);
		assertEquals(160.0, VisorAirspaceMapPolicy.sampleOffset(10, 160), 1.0e-9);
	}

	@Test void domeBaseIsAnchoredToTerrainAtTheDomeCenter() {
		assertEquals(81.5, VisorAirspaceMapPolicy.domeBaseY(80.0), 1.0e-9);
	}

	@Test void exceptionalTerrainReliefIsCompressedWithoutMovingTheDatum() {
		assertEquals(80.0, VisorAirspaceMapPolicy.terrainDisplayY(80.0, 80.0), 1.0e-9);
		assertTrue(VisorAirspaceMapPolicy.terrainDisplayY(300.0, 80.0) < 120.0);
		assertTrue(VisorAirspaceMapPolicy.terrainDisplayY(-100.0, 80.0) > 40.0);
		assertTrue(VisorAirspaceMapPolicy.terrainDisplayY(96.0, 80.0) > 80.0);
	}

	@Test void coverageWireframeRepresentsTheRealThreeDimensionalRange() {
		var horizontal = VisorAirspaceMapPolicy.spherePoint(10, 70, -20,
			VisorAirspaceMapPolicy.SphereRing.HORIZONTAL, Math.PI / 2.0);
		var vertical = VisorAirspaceMapPolicy.spherePoint(10, 70, -20,
			VisorAirspaceMapPolicy.SphereRing.NORTH_SOUTH, Math.PI / 2.0);
		assertEquals(128.0, distance(horizontal, 10, 70, -20), 1.0e-9);
		assertEquals(128.0, distance(vertical, 10, 70, -20), 1.0e-9);
		assertTrue(vertical.y() > 70.0);
	}

	@Test void aggregateDomeIsAnUpperHemisphereWithAStableBaseAndApex() {
		var west = VisorAirspaceMapPolicy.domePoint(10, 70, -20, 160, 96,
			VisorAirspaceMapPolicy.DomeArc.EAST_WEST, 0.0);
		var apex = VisorAirspaceMapPolicy.domePoint(10, 70, -20, 160, 96,
			VisorAirspaceMapPolicy.DomeArc.EAST_WEST, Math.PI / 2.0);
		var east = VisorAirspaceMapPolicy.domePoint(10, 70, -20, 160, 96,
			VisorAirspaceMapPolicy.DomeArc.EAST_WEST, Math.PI);
		assertEquals(70.0, west.y(), 1.0e-9);
		assertEquals(166.0, apex.y(), 1.0e-9);
		assertEquals(70.0, east.y(), 1.0e-9);
		assertEquals(160.0, Math.abs(west.x() - 10), 1.0e-9);
		assertEquals(160.0, Math.abs(east.x() - 10), 1.0e-9);
	}

	@Test void aggregateDomeLineBudgetDoesNotScaleWithRelayCount() {
		int arcSegmentsWithGlow = VisorAirspaceMapPolicy.DOME_SEGMENTS * 4 * 2;
		assertTrue(arcSegmentsWithGlow <= 160);
		assertTrue(VisorAirspaceMapPolicy.TERRAIN_GRID * VisorAirspaceMapPolicy.TERRAIN_GRID <= 121);
		assertTrue(VisorAirspaceMapPolicy.TERRAIN_REFRESH_TICKS >= 40);
		assertTrue(VisorAirspaceMapPolicy.TERRAIN_SCAN_STEP >= 4);
	}

	private static double distance(VisorAirspaceMapPolicy.Point3 point, double x, double y, double z) {
		return Math.sqrt(Math.pow(point.x() - x, 2) + Math.pow(point.y() - y, 2)
			+ Math.pow(point.z() - z, 2));
	}
}
