package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

final class TacticalLayoutPolicyTest {
	private static final List<Viewport> VIEWPORTS = List.of(
		new Viewport(320, 180), new Viewport(640, 360), new Viewport(854, 480),
		new Viewport(900, 500), new Viewport(960, 540), new Viewport(1024, 768),
		new Viewport(1280, 720), new Viewport(1366, 768),
		new Viewport(1600, 900), new Viewport(1920, 1080), new Viewport(2560, 1440),
		new Viewport(3440, 1440), new Viewport(3840, 2160), new Viewport(5120, 1440));
	private static final List<Integer> FLEET_SIZES = List.of(0, 1, 7, 8, 9, 24, 40, 100, 128);

	@TestFactory
	Stream<DynamicTest> everySupportedViewportKeepsPanelsSeparated() {
		return VIEWPORTS.stream().map(viewport -> DynamicTest.dynamicTest(viewport.toString(), () -> {
			TacticalLayoutPolicy.Metrics metrics = TacticalLayoutPolicy.calculate(viewport.width, viewport.height);
			assertTrue(metrics.structurallyValid());
			assertEquals(metrics.width(), metrics.leftWidth() + metrics.mapWidth() + metrics.rightWidth());
			assertEquals(metrics.bottomTop() - metrics.header(), metrics.mapHeight());
		}));
	}

	@TestFactory
	Stream<DynamicTest> allFleetSizesClampEveryScrollablePanel() {
		return VIEWPORTS.stream().flatMap(viewport -> FLEET_SIZES.stream().map(size ->
			DynamicTest.dynamicTest(viewport + " fleet " + size, () -> {
				TacticalLayoutPolicy.Metrics metrics = TacticalLayoutPolicy.calculate(viewport.width, viewport.height);
				for (int capacity : List.of(metrics.unitCapacity(), metrics.dockCapacity(),
					metrics.missionCapacity(), metrics.cargoCapacity())) {
					int maximum = Math.max(0, size - capacity);
					assertEquals(0, TacticalLayoutPolicy.clampScroll(-999, size, capacity));
					assertEquals(maximum, TacticalLayoutPolicy.clampScroll(999, size, capacity));
					int middle = maximum / 2;
					assertEquals(middle, TacticalLayoutPolicy.clampScroll(middle, size, capacity));
				}
			}))) ;
	}

	private record Viewport(int width, int height) {
		@Override public String toString() { return width + "x" + height; }
	}
}
