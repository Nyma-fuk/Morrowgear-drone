package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

final class DefensiveFormationTest {
	private static final List<DefenseProtocol> DEFAULT_ORDER = List.of(
		DefenseProtocol.PLAYER_PROTECTION,
		DefenseProtocol.FORMATION_COHESION,
		DefenseProtocol.SELF_PRESERVATION
	);

	@AfterEach
	void restoreDoctrine() {
		DefenseDoctrine.setProtocolOrder(DEFAULT_ORDER);
	}

	@Test
	void shieldAnchorSitsBetweenPlayerAndThreat() {
		Vec3 player = new Vec3(10, 64, 10);
		Vec3 threat = new Vec3(20, 64, 10);
		Vec3 anchor = DefensiveFormation.shieldAnchor(player, threat);

		assertTrue(anchor.x > player.x && anchor.x < threat.x);
		assertEquals(player.z, anchor.z, 0.0001);
		assertTrue(anchor.y > player.y);
	}

	@Test
	void wallSlotsRemainSeparatedForAFullSquad() {
		Vec3 player = Vec3.ZERO;
		Vec3 threat = new Vec3(0, 0, 20);
		for (int first = 0; first < 8; first++) {
			for (int second = first + 1; second < 8; second++) {
				Vec3 a = DefensiveFormation.wallOffset(first, 8, player, threat);
				Vec3 b = DefensiveFormation.wallOffset(second, 8, player, threat);
				assertTrue(a.distanceTo(b) >= 0.75, first + " and " + second + " overlap");
			}
		}
	}

	@Test
	void threeDroneWallCoversDifferentHeightsWithoutOverlap() {
		Vec3 player = Vec3.ZERO;
		Vec3 threat = new Vec3(0, 0, 20);
		Vec3 top = DefensiveFormation.wallOffset(0, 3, player, threat);
		Vec3 lowerLeft = DefensiveFormation.wallOffset(1, 3, player, threat);
		Vec3 lowerRight = DefensiveFormation.wallOffset(2, 3, player, threat);

		assertTrue(top.y > lowerLeft.y);
		assertTrue(lowerLeft.distanceTo(lowerRight) > 1.5);
		assertTrue(top.distanceTo(lowerLeft) > 1.0);
	}

	@Test
	void doctrineOrderCanChangeButCannotLoseOrDuplicateProtocols() {
		List<DefenseProtocol> reordered = List.of(
			DefenseProtocol.PLAYER_PROTECTION,
			DefenseProtocol.SELF_PRESERVATION,
			DefenseProtocol.FORMATION_COHESION
		);
		DefenseDoctrine.setProtocolOrder(reordered);
		assertEquals(reordered, DefenseDoctrine.protocolOrder());

		assertThrows(IllegalArgumentException.class,
			() -> DefenseDoctrine.setProtocolOrder(List.of(DefenseProtocol.PLAYER_PROTECTION)));
		assertThrows(IllegalArgumentException.class,
			() -> DefenseDoctrine.setProtocolOrder(List.of(
				DefenseProtocol.PLAYER_PROTECTION,
				DefenseProtocol.PLAYER_PROTECTION,
				DefenseProtocol.SELF_PRESERVATION)));
	}

	@Test
	void environmentalRiskDoesNotOverrideMissionMovement() {
		ThreatAssessment.Snapshot environmentOnly = new ThreatAssessment.Snapshot(
			8, ThreatBand.GUARDED, new Vec3(0, 0, 6), null, false, -1, 0, 8);

		assertTrue(!DefenseDoctrine.requiresDefensiveMovement(environmentOnly));
		assertTrue(!DefenseDoctrine.requiresFormationMovement(environmentOnly));
	}

	@Test
	void nearbyEnemyChangesFormationBeforeItOverridesMission() {
		ThreatAssessment.Snapshot guardedEnemy = new ThreatAssessment.Snapshot(
			12, ThreatBand.GUARDED, new Vec3(0, 0, 6), null, false, 42, 1, 2);
		ThreatAssessment.Snapshot highEnemy = new ThreatAssessment.Snapshot(
			18, ThreatBand.HIGH, new Vec3(0, 0, 6), null, false, 42, 2, 2);

		assertTrue(DefenseDoctrine.requiresFormationMovement(guardedEnemy));
		assertTrue(!DefenseDoctrine.requiresDefensiveMovement(guardedEnemy));
		assertTrue(DefenseDoctrine.requiresDefensiveMovement(highEnemy));
	}
}
