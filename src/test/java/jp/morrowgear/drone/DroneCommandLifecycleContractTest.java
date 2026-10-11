package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DroneCommandLifecycleContractTest {
	private static String source(String file) throws Exception {
		return Files.readString(Path.of("src/main/java/jp/morrowgear/drone/" + file))
			.replace("\r\n", "\n");
	}

	@Test void explicitDockReturnCannotResumeAStaleServiceTask() throws Exception {
		String entity = source("DroneEntity.java");
		assertTrue(entity.contains("requestManualDockReturn(\"DOCK RETURN / OPERATOR\")"));
		assertTrue(entity.contains("taskStack.clear();\n\t\tserviceResumeMode = DroneMode.STANDBY.id();"));
		assertTrue(entity.contains("if (!manualDockReturn) {\n\t\t\t\tserviceResumeMode = mode.id();"));
		assertTrue(entity.contains("DOCKED / OPERATOR RETURN COMPLETE"));
	}

	@Test void meshCommandsHaveOneAuthoritativeLifecycle() throws Exception {
		String entity = source("DroneEntity.java");
		String commands = source("MorrowgearDrone.java");
		assertTrue(commands.contains("else drone.requestFieldMeshWithdrawal();"));
		assertTrue(entity.contains("FIELD MESH / DEPLOY / ASSIGNING"));
		assertTrue(entity.contains("FIELD MESH / WITHDRAW / RTB"));
		assertTrue(entity.contains("FIELD MESH / DOCK SERVICE / FLT "));
	}

	@Test void acceptedCommandsHaveObservableCompletionEdges() throws Exception {
		String transactions = source("OperatorCommandTransactions.java");
		assertTrue(transactions.contains("action.equals(\"mesh_deploy\")"));
		assertTrue(transactions.contains("action.equals(\"mesh_withdraw\")"));
		assertTrue(transactions.contains("action.startsWith(\"guard:\")"));
		assertTrue(transactions.contains("action.startsWith(\"patrol:\")"));
		assertTrue(transactions.contains("action.startsWith(\"follow:\")"));
		assertTrue(transactions.contains("action.startsWith(\"attack:\")"));
	}

	@Test void supplyCustodyCannotBeReportedAsACompletedUnrelatedCommand() throws Exception {
		String entity = source("DroneEntity.java");
		String commands = source("MorrowgearDrone.java");
		assertTrue(entity.contains("cargoLiftMissionId != null, supplyLoadCommitted())"));
		assertTrue(entity.contains("COMMAND REJECTED / SUPPLY LOAD SAFETY"));
		assertTrue(commands.contains("else transaction.rejected(\"指定Dockを割り当てられません\")"));
		assertTrue(commands.contains("else transaction.rejected(\"Wing変更を適用できません\")"));
	}
}
