package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OperatorMissionControlContractTest {
	private static String main(String file) throws Exception {
		return Files.readString(Path.of("src/main/java/jp/morrowgear/drone", file));
	}

	private static String client(String file) throws Exception {
		return Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client", file));
	}

	@Test void forcedMissionOverrideClearsCombatAndRejectsUnsafePhysicalLoads() throws Exception {
		String entity = main("DroneEntity.java");
		String override = entity.substring(entity.indexOf("public boolean forceOperatorOverride()"),
			entity.indexOf("public String forcedOperatorCommandRejection()"));
		assertTrue(override.contains("clearCombatState()"));
		assertTrue(override.contains("clearEmergencyInterception()"));
		assertTrue(override.contains("taskStack.clear()"));
		assertTrue(override.contains("!acceptsOperatorFlightCommand()"));
		assertTrue(entity.contains("supplyLoadCommitted()"));
		assertTrue(entity.contains("salvageTowCommitted()"));
		assertTrue(entity.contains("cargoLiftMissionId != null"));
	}

	@Test void explicitTargetCommandIsValidatedServerSideAndPersistsAcrossReload() throws Exception {
		String server = main("MorrowgearDrone.java");
		assertTrue(server.contains("action.startsWith(\"attack:\")"));
		assertTrue(server.contains("drone.assignOperatorAttack(player, living"));
		String entity = main("DroneEntity.java");
		assertTrue(entity.contains("ThreatAssessment.targetDisposition(target, owner"));
		assertTrue(entity.contains("output.putString(\"OperatorCombatTarget\""));
		assertTrue(entity.contains("input.getStringOr(\"OperatorCombatTarget\""));
	}

	@Test void tacticalMapKeepsForeignFleetReadOnlyAndOutOfOwnSelection() throws Exception {
		String ui = client("TacticalDashboard.java");
		assertTrue(ui.contains("List<DroneEntity> cachedForeignDrones"));
		assertTrue(ui.contains("List<ForeignDock> cachedForeignDocks"));
		assertTrue(ui.contains("!drone.matchesOwner(client.player.getUUID()"));
		assertTrue(ui.contains("drone.matchesOwner(client.player.getUUID()"));
		assertFalse(ui.contains("selected.add(foreign"));
		assertTrue(ui.contains("issueAttack(target.entityId())"));
		assertTrue(ui.contains("forceOverride && forceableAction(rawAction)"));
	}
}
