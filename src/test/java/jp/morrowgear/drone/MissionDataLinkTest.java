package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MissionDataLinkTest {
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@AfterEach
	void clearDataLink() {
		MissionDataLink.clear();
	}

	@Test
	void combinesScoutReportsAndKeepsTheMoreDangerousRouteState() {
		MissionDataLink.publish("overworld", OWNER, "mission", "scout-1", report(
			MissionDataLink.RouteStatus.CLEAR, 4, 0.8, 100));
		MissionDataLink.publish("overworld", OWNER, "mission", "scout-2", report(
			MissionDataLink.RouteStatus.HAZARDOUS, 18, 0.6, 105));

		MissionDataLink.Snapshot snapshot = MissionDataLink.read("overworld", OWNER, "mission", 110);
		assertEquals(MissionDataLink.RouteStatus.HAZARDOUS, snapshot.routeStatus());
		assertEquals(18, snapshot.threatScore());
		assertEquals(2, snapshot.sourceCount());
		assertEquals(0.7, snapshot.confidence(), 0.001);
		assertTrue(snapshot.available());
	}

	@Test
	void staleReportsExpireInsteadOfRemainingAuthoritative() {
		MissionDataLink.publish("overworld", OWNER, "mission", "scout-1", report(
			MissionDataLink.RouteStatus.CLEAR, 0, 1.0, 100));

		MissionDataLink.Snapshot snapshot = MissionDataLink.read("overworld", OWNER, "mission", 301);
		assertFalse(snapshot.available());
		assertEquals(MissionDataLink.RouteStatus.UNKNOWN, snapshot.routeStatus());
	}

	@Test
	void missionAndOwnerBoundariesPreventInformationLeakage() {
		MissionDataLink.publish("overworld", OWNER, "mission-a", "scout-1", report(
			MissionDataLink.RouteStatus.CLEAR, 0, 1.0, 100));
		assertFalse(MissionDataLink.read("overworld", OWNER, "mission-b", 100).available());
		assertFalse(MissionDataLink.read("nether", OWNER, "mission-a", 100).available());
	}

	private static MissionDataLink.ScoutReport report(MissionDataLink.RouteStatus status,
		int threat, double confidence, long tick) {
		return new MissionDataLink.ScoutReport(status, Vec3.ZERO, threat, new Vec3(5, 70, 5),
			status == MissionDataLink.RouteStatus.CLEAR, confidence, tick, "WING-SCOUT");
	}
}
