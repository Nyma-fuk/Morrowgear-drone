package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.UUID;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class FleetThreatNetworkTest {
	@AfterEach void clear() { FleetThreatNetwork.clear(); }

	@Test void strongestFreshScoutReportIsSharedFleetWide() {
		UUID owner = UUID.randomUUID();
		FleetThreatNetwork.publish("overworld", owner, "S1", new FleetThreatNetwork.Report(9, Vec3.ZERO, 1, "WING-A", "R1", 100));
		FleetThreatNetwork.publish("overworld", owner, "S2", new FleetThreatNetwork.Report(18, new Vec3(4, 5, 6), 2, "WING-B", "R2", 110));
		FleetThreatNetwork.Snapshot snapshot = FleetThreatNetwork.read("overworld", owner, 120);
		assertTrue(snapshot.active());
		assertEquals(18, snapshot.score());
		assertEquals("WING-B", snapshot.sourceWing());
		assertFalse(FleetThreatNetwork.read("overworld", owner, 200).active());
	}

	@Test void playerDangerOutranksAHigherScoringScoutReport() {
		UUID owner = UUID.randomUUID();
		FleetThreatNetwork.publish("overworld", owner, "SCOUT-1",
			new FleetThreatNetwork.Report(40, new Vec3(20, 64, 20), 80, "WING-SCOUT", "PATROL", 100));
		FleetThreatNetwork.publish("overworld", owner, "PLAYER-1",
			new FleetThreatNetwork.Report(8, new Vec3(2, 64, 2), 42, "PLAYER-GUARD", "", 99));
		FleetThreatNetwork.Snapshot snapshot = FleetThreatNetwork.read("overworld", owner, 110);
		assertEquals(42, snapshot.entityId());
		assertEquals("PLAYER-GUARD", snapshot.sourceWing());
	}

	@Test void allDistinctFreshContactsRemainAvailableToTheTheaterCoordinator() {
		UUID owner = UUID.randomUUID();
		FleetThreatNetwork.publish("overworld", owner, "SCOUT-1",
			new FleetThreatNetwork.Report(12, Vec3.ZERO, 10, "WING-A", "PATROL", 100));
		FleetThreatNetwork.publish("overworld", owner, "SCOUT-1",
			new FleetThreatNetwork.Report(16, new Vec3(3, 0, 0), 11, "WING-A", "PATROL", 101));
		FleetThreatNetwork.publish("overworld", owner, "SCOUT-2",
			new FleetThreatNetwork.Report(20, new Vec3(3, 0, 0), 11, "WING-B", "PATROL", 102));

		List<FleetThreatNetwork.Snapshot> contacts = FleetThreatNetwork.readAll("overworld", owner, 110);

		assertEquals(2, contacts.size());
		assertEquals(11, contacts.getFirst().entityId());
		assertEquals(20, contacts.getFirst().score());
	}
}
