package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DockRebindContractTest {
	@Test void destructionPreservesThePreReleaseDependencyState() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String clear = section(source, "public void clearDock()", "private void releaseDockReservation()");
		assertTrue(clear.indexOf("boolean wasDocked = isDocked()") < clear.indexOf("releaseDockReservation()"));
		assertTrue(clear.contains("DockAllocationPolicy.replacementRequired"));
		assertTrue(clear.contains("requestDockSlot()"));
		assertTrue(clear.contains("REBIND QUEUED"));
	}

	@Test void everyDockedAircraftRepairsAStaleBindingBeforeService() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		String service = section(source, "private void tickDockSupplies(", "private void updateCombat(");
		assertTrue(service.contains("if (!isDocked()) return"));
		assertTrue(service.contains("clearDock()"));
		assertTrue(service.contains("ALIGNING SERVICE CONTACT"));
		assertTrue(service.indexOf("clearDock()") < service.indexOf("provideFlightCharge"));
	}

	@Test void placementAndDestructionInvalidateTheSameDockTopology() throws Exception {
		String item = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/item/DockKitItem.java"));
		String common = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearDrone.java"));
		assertTrue(item.contains("MorrowgearDrone.dockTopologyChanged"));
		assertTrue(common.contains("dockTopologyChanged(level, dock.ownerId())"));
	}

	private static String section(String source, String start, String end) {
		int from = source.indexOf(start);
		int to = source.indexOf(end, from);
		return source.substring(from, to);
	}
}
