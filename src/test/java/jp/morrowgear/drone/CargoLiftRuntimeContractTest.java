package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CargoLiftRuntimeContractTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/jp/morrowgear/drone").resolve(relative));
    }

    @Test void inventoryTransferUsesParentOwnedAtomicLedgerBeforeWorldMutation() throws Exception {
        String load = source("CargoLiftLoadEntity.java");
        int begin = load.indexOf("CarrierExpansionTransactions.beginLift(");
        int remove = load.indexOf("CarrierContainerFootprint.removeContainer(level, sourcePos())");
        int finish = load.indexOf("CarrierExpansionTransactions.finishLift(");
        int place = load.indexOf("level.setBlockAndUpdate(destination");
        assertTrue(begin >= 0 && begin < remove, "beginLift must precede source removal");
        assertTrue(finish >= 0 && finish < place, "finishLift must precede final placement");
        assertTrue(load.contains("CarrierExpansionTransactions.rollbackToPlaced"));
        assertTrue(load.contains("CarrierExpansionTransactions.beginLift(level, identity, destination, missionId())"));
    }

    @Test void missionIsPersistentAndOwnsAllFourAircraftUntilSafeRelease() throws Exception {
        String load = source("CargoLiftLoadEntity.java");
        String drone = source("DroneEntity.java");
        assertTrue(load.contains("for (Corner corner : Corner.values())"));
        assertTrue(load.contains("output.store(\"Cargo\", ItemStack.CODEC, cargo)"));
        assertTrue(load.contains("Phase.EMERGENCY_DESCENT"));
        assertTrue(load.contains("findSafeLanding"));
        assertTrue(load.contains("case LIFT -> position().y >= transitBaseY(level, position())"),
            "horizontal transit must wait for full load-bottom terrain clearance");
        assertTrue(load.contains("enforceTransitClearance(level)"),
            "transit damping must not pull the suspended load below terrain clearance");
        assertTrue(load.contains("followAircraftSupport(members"),
            "the physical load must follow the four-aircraft support centroid");
        assertTrue(load.contains("advanceRigGuide("),
            "aircraft must follow a bounded guide instead of chasing a leading load");
        assertTrue(load.contains("CargoLiftPolicy.attachmentOffset(corner)"));
        assertTrue(load.contains("CargoLiftPolicy.formationOffset(corner)"));
        assertTrue(load.contains("HasRigGuide"), "the guide must survive save and reload");
        assertTrue(drone.contains("tickCargoLiftAssignment(level)"));
        assertTrue(drone.contains("cargoLiftFlightTarget(level, load, station)"));
        assertTrue(drone.contains("CargoLiftPolicy.ROUTE_REFRESH_TICKS"),
            "blocked rendezvous routes must be cached instead of rescanned every tick");
        assertTrue(drone.contains("DroneNavigator.cargoLiftDetour"));
        assertTrue(load.contains("RENDEZVOUS_ABORT_TICKS"),
            "an unreachable container must terminate instead of consuming server time forever");
        assertTrue(drone.contains("CONTAINER LIFT SAFETY"));
    }

    @Test void controllerHasOwnerFilteredSnapshotStartAndSafeCancel() throws Exception {
        String network = source("CargoLiftNetwork.java");
        String common = source("MorrowgearDrone.java");
        String client = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/ControllerCargoLiftScreen.java"));
        assertTrue(network.contains("cargo.ownerId().equals(player.getUUID())"));
        assertTrue(network.contains("CargoLiftPolicy.REQUIRED_AIRCRAFT"));
        assertTrue(network.contains("mission.cancelBeforeSpawn"));
        assertTrue(common.contains("CargoLiftNetwork.register()"));
        assertTrue(client.contains("4機で搬送開始"));
        assertTrue(client.contains("安全中止"));
        assertTrue(client.contains("CargoLiftStatusRequest"));
    }
}
