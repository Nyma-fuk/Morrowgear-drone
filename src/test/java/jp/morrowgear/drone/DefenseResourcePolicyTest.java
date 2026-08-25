package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

final class DefenseResourcePolicyTest {
	@Test
	void fiveUnitSquadScalesDefenseWithoutAbandoningEveryMission() {
		assertEquals(1, DefenseResourcePolicy.requiredDefenders(snapshot(ThreatBand.GUARDED, 10, 1, false), 5, 5));
		assertEquals(2, DefenseResourcePolicy.requiredDefenders(snapshot(ThreatBand.HIGH, 20, 2, false), 5, 5));
		assertEquals(3, DefenseResourcePolicy.requiredDefenders(snapshot(ThreatBand.CRITICAL, 32, 4, true), 5, 5));
	}

	@Test
	void idleUnitsArePreferredBeforeMissionUnits() {
		assertEquals(0, DefenseResourcePolicy.availabilityRank(DroneMode.STANDBY));
		assertEquals(0, DefenseResourcePolicy.availabilityRank(DroneMode.FOLLOW));
		assertEquals(1, DefenseResourcePolicy.availabilityRank(DroneMode.DOCK));
		assertEquals(2, DefenseResourcePolicy.availabilityRank(DroneMode.WAYPOINT));
	}

	@Test
	void environmentOnlyRiskDoesNotConsumeDefenseUnits() {
		assertEquals(0, DefenseResourcePolicy.requiredDefenders(snapshot(ThreatBand.GUARDED, 8, 0, false), 5, 5));
	}

	@Test
	void singleDroneCanStillProtectItsOwner() {
		assertEquals(1, DefenseResourcePolicy.requiredDefenders(snapshot(ThreatBand.CRITICAL, 40, 3, true), 1, 1));
	}

	private static ThreatAssessment.Snapshot snapshot(ThreatBand band, int score, int enemies, boolean direct) {
		return new ThreatAssessment.Snapshot(score, band, new Vec3(0, 0, 8), null, direct, 42, enemies, 0);
	}
}
