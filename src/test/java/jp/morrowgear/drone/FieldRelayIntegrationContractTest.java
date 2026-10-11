package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FieldRelayIntegrationContractTest {
	private static String source(String file) throws IOException {
		return Files.readString(Path.of("src/main/java/jp/morrowgear/drone/" + file));
	}

	@Test void solarServiceIsNotUsedAsTheDataLinkGraph() throws IOException {
		String network = source("DataLinkRelayNetwork.java");
		assertTrue(network.contains("FieldRelayRuntime.relayNodes(player)"));
		assertFalse(network.contains("SolarServiceRegistry"));
	}

	@Test void relayFlightCannotPreemptSafetyServiceOrFieldWork() throws IOException {
		String entity = source("DroneEntity.java");
		String eligibility = entity.substring(entity.indexOf("boolean fieldRelayAvailableForMesh()"),
			entity.indexOf("public CombatState combatState()"));
		assertTrue(eligibility.contains("!serviceReturnActive()"));
		assertTrue(eligibility.contains("!hasFieldOperation()"));
		assertTrue(eligibility.contains("!cargoLiftAssigned()"));
		assertTrue(eligibility.contains("recoveryLevel() <= 0"));
		assertTrue(eligibility.contains("!manualDockReturn"));
		assertTrue(eligibility.contains("fieldMeshAuthorized"));
	}

	@Test void relayTransitUsesSharedRecoveryWithoutDroppingItsMeshSlot() throws IOException {
		String entity = source("DroneEntity.java");
		String runtime = source("FieldRelayRuntime.java");
		String flight = entity.substring(entity.indexOf("private boolean tickFieldRelayMesh"),
			entity.indexOf("private boolean fieldMeshMayHoldPending"));
		assertTrue(flight.contains("Vec3 target = navigationTarget(level, requested, environment)"));
		assertFalse(flight.contains("directMeshLeg ? requested : navigationTarget"));
		assertTrue(runtime.contains("filter(DroneEntity::fieldRelayControlsMeshFlight)"));
		assertTrue(runtime.contains("drone.fieldRelayAvailableForMesh()"));
		assertTrue(entity.contains("fieldRelayControlsMeshFlight() && recoveryLevel() <= 0"));
		String verifier = source("MorrowgearRuntimeVerifier.java");
		assertTrue(verifier.contains("field relay obstacle reroute and slot retention"));
		assertTrue(verifier.contains("FIELD relay lost or changed its reserved mesh slot"));
		assertTrue(verifier.contains("FIELD relay did not re-enter the data-link graph after arrival"));
	}

	@Test void placementReturnsToDockAndMeshRequiresExplicitPersistentConsent() throws IOException {
		String entity = source("DroneEntity.java");
		String deployment = source("MorrowgearDrone.java");
		assertTrue(deployment.contains("drone.prepareForCommissioningReturn()"));
		assertTrue(deployment.contains("action.equals(\"mesh_deploy\")"));
		assertTrue(deployment.contains("action.equals(\"mesh_withdraw\")"));
		assertTrue(deployment.contains("通信メッシュ命令はFIELD機専用です"));
		assertTrue(entity.contains("output.putBoolean(\"FieldMeshAuthorized\", fieldMeshAuthorized)"));
		assertTrue(entity.contains("input.getBooleanOr(\"FieldMeshAuthorized\", legacyActiveMesh)"));
		assertTrue(entity.contains("revokeFieldMeshAuthorization();\n\t\trequestManualDockReturn();"));
	}

	@Test void outOfRangeCommandsCreateABoundedDynamicDemand() throws IOException {
		assertTrue(source("DataLinkRelayNetwork.java").contains("FieldRelayRuntime.requestCoverage"));
		String runtime = source("FieldRelayRuntime.java");
		assertTrue(runtime.contains("tick + 400L"));
		assertTrue(runtime.contains(".limit(8)"));
	}

	@Test void meshAltitudeAndServiceResumeDoNotFollowThePlayersYOrGenericFallback() throws IOException {
		String runtime = source("FieldRelayRuntime.java");
		String entity = source("DroneEntity.java");
		assertTrue(runtime.contains("previous.flightPlaneY()"));
		assertTrue(runtime.contains("new Vec3(horizontal.x, flightPlaneY, horizontal.z)"));
		assertFalse(runtime.contains("holdAltitude(level, horizontal)"));
		assertTrue(runtime.contains("level.getSeaLevel()"));
		assertFalse(runtime.contains("controllerY + FieldRelayFlightPolicy.HOLD_CLEARANCE"));
		assertTrue(entity.contains("resumeAuthorizedFieldMesh(level, \"SERVICE COMPLETE\")"));
		assertTrue(entity.contains("resumeAuthorizedFieldMesh(level, \"SOLAR LINK END\")"));
		assertTrue(entity.contains("FIELD MESH / SERVICE PENDING / HOLD"));
		assertTrue(entity.contains("FIELD MESH / RESERVE / HOLD"));
	}

	@Test void deployedMeshOriginIsPersistentAndDoesNotFollowPlayerMovement() throws IOException {
		String runtime = source("FieldRelayRuntime.java");
		String entity = source("DroneEntity.java");
		String command = source("MorrowgearDrone.java");
		assertTrue(runtime.contains("DEPLOYMENT_ANCHORS"));
		assertTrue(runtime.contains("FieldRelayDeploymentPolicy.resolve"));
		assertFalse(runtime.contains("CONTROLLER_ANCHOR_HYSTERESIS"));
		assertFalse(runtime.contains("controllerAnchor("));
		assertTrue(entity.contains("output.putDouble(\"FieldMeshAnchorX\""));
		assertTrue(entity.contains("input.getDoubleOr(\"FieldMeshAnchorX\""));
		assertTrue(entity.contains("FieldRelayRuntime.authorizationChanged(owner)"));
		assertTrue(command.contains("FieldRelayRuntime.deploymentAnchor(player)"));
	}
}
