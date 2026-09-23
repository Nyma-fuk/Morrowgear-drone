package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import jp.morrowgear.drone.carrier.CarrierAnchor;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierServiceBay;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Pure routing and source wiring checks. They do not simulate a running ServerLevel. */
final class SupplyCarrierExteriorIntegrationTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/jp/morrowgear/drone", name));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, start);
        return source.substring(from, to);
    }

    private static CarrierDroneServiceAdapter.ExteriorAction action(DroneMode mode, boolean service,
            boolean protectedWork, boolean waypoint, boolean cargo, boolean owned, boolean paused) {
        return CarrierDroneServiceAdapter.exteriorAction(mode, service, protectedWork, waypoint, cargo, owned, paused);
    }

    @Test void cargoOnlyContinuesTheExistingOwnedRouteAndCannotOverrideCommands() {
        var cargo = CarrierDroneServiceAdapter.ExteriorAction.CARGO;
        var hold = CarrierDroneServiceAdapter.ExteriorAction.HOLD;
        assertEquals(cargo, action(DroneMode.WAYPOINT, false, false, true, true, true, false));
        assertEquals(hold, action(DroneMode.WAYPOINT, false, false, true, true, false, false));
        assertEquals(hold, action(DroneMode.WAYPOINT, false, false, true, true, true, true));
        for (DroneMode mode : new DroneMode[] {DroneMode.FOLLOW, DroneMode.RETURN, DroneMode.ORBIT, DroneMode.STANDBY})
            assertEquals(hold, action(mode, false, false, true, true, true, false));
    }

    @Test void protectedWorkWinsOverLocalRoutesAndServiceWinsOverCargo() {
        for (DroneMode mode : DroneMode.values()) {
            assertEquals(CarrierDroneServiceAdapter.ExteriorAction.HOLD,
                action(mode, true, true, true, true, true, false));
            assertEquals(CarrierDroneServiceAdapter.ExteriorAction.DOCK,
                action(mode, true, false, true, true, true, false));
        }
    }

    @Test void missingWaypointsHoldAndOwnerRelativeModesUseExteriorFollow() {
        assertEquals(CarrierDroneServiceAdapter.ExteriorAction.HOLD,
            action(DroneMode.WAYPOINT, false, false, false, false, false, false));
        assertEquals(CarrierDroneServiceAdapter.ExteriorAction.WAYPOINT,
            action(DroneMode.WAYPOINT, false, false, true, false, false, false));
        for (DroneMode mode : new DroneMode[] {DroneMode.FOLLOW, DroneMode.RETURN, DroneMode.ORBIT})
            assertEquals(CarrierDroneServiceAdapter.ExteriorAction.FOLLOW,
                action(mode, false, false, false, false, false, false));
    }

    @Test void standOffUsesTranslatedHullBoundsAndNeverCabinCoordinates() {
        for (AABB hull : new AABB[] {new AABB(-13, 100, -25, 13, 111, 25),
                new AABB(1000, 70, -3000, 1026, 81, -2950)}) {
            for (int hash : new int[] {Integer.MIN_VALUE, -1, 0, 1, 7, Integer.MAX_VALUE}) {
                Vec3 target = CarrierDroneServiceAdapter.exteriorFollowTarget(hull, hash);
                assertFalse(hull.inflate(2).contains(target));
                assertEquals(hull.minY - 3, target.y);
                assertTrue(target.z > hull.minZ && target.z < hull.maxZ);
                assertTrue(target.x <= hull.minX - 6 || target.x >= hull.maxX + 6);
            }
        }
    }

    @Test void allFourBayApproachesGoOutsideV27BeforeDescendingAndThenUseTheAperture() {
        AABB hull = new AABB(-13, 100, -25, 13, 111, 25);
        assertEquals(4, CarrierPolicy.BAY_SLOTS);
        for (int slot = 0; slot < CarrierPolicy.BAY_SLOTS; slot++) {
            Vec3 staging = new Vec3(CarrierPolicy.bayX(slot), 98, CarrierPolicy.bayZ(slot));
            Vec3 bay = staging.add(0, 4.4, 0);
            Vec3 above = new Vec3(0, 115, 0);
            Vec3 outside = CarrierDroneServiceAdapter.approachWaypoint(hull, above, staging, staging);
            assertTrue(outside.x >= hull.maxX + 3 || outside.x <= hull.minX - 3);
            assertEquals(above.y, outside.y);
            Vec3 below = CarrierDroneServiceAdapter.approachWaypoint(hull, outside, staging, staging);
            assertEquals(outside.x, below.x);
            assertEquals(staging.y, below.y);
            assertEquals(staging, CarrierDroneServiceAdapter.approachWaypoint(hull, below, staging, staging));
            assertEquals(bay, CarrierDroneServiceAdapter.approachWaypoint(hull, staging, bay, staging));
            assertEquals(staging, CarrierDroneServiceAdapter.approachWaypoint(hull, bay, staging, staging));
        }
    }

    @Test void savedExitSlotSurvivesReloadAndOldSessionsRemainReadable() {
        var home = new CarrierAnchor("minecraft:overworld", 12, 80, 10);
        var identity = new CarrierServiceBay.Identity(UUID.randomUUID(), UUID.randomUUID(),
            Optional.of(home), "CUSTOM_WING", "manual-route");
        for (int slot = 0; slot < 4; slot++) {
            var session = new CarrierDroneServiceAdapter.Session(UUID.randomUUID(), identity, 5, "cargo", home, 100, slot);
            var json = CarrierDroneServiceAdapter.Session.CODEC.encodeStart(JsonOps.INSTANCE, session).getOrThrow();
            assertEquals(session, CarrierDroneServiceAdapter.Session.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
            json.getAsJsonObject().remove("slot");
            assertEquals(-1, CarrierDroneServiceAdapter.Session.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().slot());
            json.getAsJsonObject().addProperty("slot", 4);
            assertTrue(CarrierDroneServiceAdapter.Session.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        }
    }

    @Test void allFourMovingBayRoutesCanApproachAndExitWithTheExistingFlightController() {
        for (int slot = 0; slot < CarrierPolicy.BAY_SLOTS; slot++) {
            Vec3 origin = new Vec3(0, 100, 0), motion = new Vec3(.04, 0, .08);
            Vec3 drone = origin.add(0, 16, 0), velocity = Vec3.ZERO;
            int stage = 0;
            for (int tick = 0; tick < 1500 && stage < 3; tick++) {
                origin = origin.add(motion);
                AABB hull = new AABB(origin.x - 13, origin.y, origin.z - 25,
                    origin.x + 13, origin.y + 11, origin.z + 25);
                Vec3 staging = origin.add(CarrierPolicy.bayX(slot), -2, CarrierPolicy.bayZ(slot));
                Vec3 bay = staging.add(0, 4.4, 0);
                Vec3 target = stage == 2 ? CarrierDroneServiceAdapter.exitWaypoint(hull, drone, staging)
                    : CarrierDroneServiceAdapter.approachWaypoint(hull, drone, stage == 0 ? staging : bay, staging);
                velocity = FlightDynamics.steerMovingOrbit(velocity, drone, target, motion, .55);
                drone = drone.add(velocity);
                assertTrue(velocity.length() <= .55 + 1e-9);
                if (stage == 0 && CarrierServiceBay.stable(drone, velocity, staging, motion)) stage = 1;
                else if (stage == 1 && CarrierServiceBay.stable(drone, velocity, bay, motion)) stage = 2;
                else if (stage == 2 && (drone.x < hull.minX - 3 || drone.x > hull.maxX + 3)
                        && CarrierServiceBay.stable(drone, velocity, target, motion)) stage = 3;
            }
            assertEquals(3, stage, "Approach, bay and safe exterior exit: slot " + slot);
        }
    }

    @Test void cargoAltitudeIsInsideTheRealEndpointServiceRadiusForPositiveAndNegativeCoordinates() throws Exception {
        String entity = source("DroneEntity.java");
        String target = section(entity, "private Vec3 cargoNavigationTarget()", "private void tickCabinHome(");
        assertTrue(target.contains("Vec3.atCenterOf(waypointPos().above(6))"));
        String navigation = section(entity, "private Vec3 targetPosition(", "private boolean solarServiceActive(");
        assertTrue(navigation.contains("supplyOwnsMission()"));
        assertTrue(navigation.contains("return cargoNavigationTarget()"));
        for (BlockPos endpoint : new BlockPos[] {new BlockPos(0, 64, 0), new BlockPos(-501, -40, 1001)}) {
            Vec3 arrival = Vec3.atCenterOf(endpoint.below(4).above(6));
            assertEquals(Vec3.atCenterOf(endpoint).add(0, 2, 0), arrival);
            assertTrue(arrival.distanceToSqr(Vec3.atCenterOf(endpoint).add(0, 2, 0)) <= 9);
        }
    }

    @Test void cabinBranchKeepsGlobalGuardAndCannotEvaluateThreatsOrDiscoverTargets() throws Exception {
        String entity = source("DroneEntity.java");
        String ai = section(entity, "protected void customServerAiStep(", "private Vec3 smoothFlightMotion(");
        assertTrue(ai.contains("if (owner == null || owner.level() != level) return;"));
        assertTrue(ai.indexOf("tickCabinExterior(level, owner)") < ai.indexOf("if (owner == null || owner.level() != level) return;"));
        String cabin = section(entity, "private boolean tickCabinExterior(", "private void logOperationalTransition(");
        assertTrue(cabin.contains("CarrierDroneServiceAdapter.ownedCabinCarrier(level, owner)"));
        for (String forbidden : new String[] {"owner.position()", "targetPosition(", "updateCombat(",
                "ThreatAssessment.", "getEntitiesOfClass(", "setCombatState(CombatState.MISSILE", "MorrowgearMissileEntity.launch("})
            assertFalse(cabin.contains(forbidden), forbidden);
        assertTrue(cabin.contains("beginCombatRejoin(level, \"OWNER IN CABIN / COMBAT CANCELLED\")"));
    }

    @Test void cargoUsesExistingTransfersAndRechecksOwnershipAfterEndpointCallbacks() throws Exception {
        String cabin = section(source("DroneEntity.java"), "private boolean tickCabinExterior(", "private void holdCabinExterior(");
        assertTrue(cabin.indexOf("level.hasChunkAt(endpoint)") < cabin.indexOf("tickCargoMission(level)"));
        assertTrue(cabin.indexOf("exteriorAction() != CarrierDroneServiceAdapter.ExteriorAction.CARGO") > cabin.indexOf("tickCargoMission(level)"));
        for (String forbidden : new String[] {"loadCargo(", "unloadCargo(", "removeItem(", "insertNetworkSupply(",
                "assignWaypoint(", "entityData.set(GROUP", "entityData.set(MISSION_ID", "taskStack.clear("})
            assertFalse(cabin.contains(forbidden), forbidden);
        assertTrue(cabin.contains("cargoNavigationTarget()"));
    }

    @Test void ordinaryCargoRechargesAndResumesWithoutUsingSecurityTheaterInTheCabin() throws Exception {
        String home = section(source("DroneEntity.java"), "private void tickCabinHome(", "private void logOperationalTransition(");
        assertTrue(home.contains("dock.isOwnedBy(ownerId())"));
        assertTrue(home.contains("DroneServicePolicy.nonCombatSortieReady("));
        assertTrue(home.contains("role() != DroneRole.SECURITY && role() != DroneRole.SALVAGE"));
        assertTrue(home.contains("resumeServiceTask(level)"));
        assertTrue(home.contains("COMBAT RESUME WAIT"));
        assertFalse(home.contains("CombatTheaterCoordinator"));
    }

    @Test void bothPhysicalBranchesRenewOnlyTheExistingBoundedOperationTicket() throws Exception {
        String ai = section(source("DroneEntity.java"), "protected void customServerAiStep(", "private Vec3 smoothFlightMotion(");
        assertTrue(ai.contains("CarrierDroneServiceAdapter.ownedCabinCarrier(level, owner) != null"));
        assertTrue(ai.indexOf("addTicketWithRadius(") < ai.indexOf("tickCarrierService(level, owner)"));
        assertTrue(ai.contains("RemoteOperationPolicy.keepsChunkActive(ownerSupportsExterior,"));
        assertTrue(ai.contains("RemoteOperationPolicy.ENTITY_TICKING_TICKET_RADIUS"));
    }

    @Test void serviceExitIsBoundedAndRunsBeforeResumingTheSuspendedMission() throws Exception {
        String entity = source("DroneEntity.java");
        String release = section(entity, "void releaseCarrierService(", "private boolean carrierWorkUnchanged(");
        assertTrue(release.contains("if (!carrierExitPending && resumeCarrierTask(level, owner))"));
        String recovery = section(entity, "private boolean tickCarrierRecovery(", "private void flyCarrierLocal(");
        assertTrue(recovery.indexOf("carrierExitPending") < recovery.indexOf("resumeCarrierTask(level, owner)"));
        assertTrue(recovery.contains("CarrierDroneServiceAdapter.MAX_EXIT_TICKS"));
        assertTrue(recovery.contains("carrier.bayApproachPosition(carrierService.slot())"));
        assertTrue(recovery.contains("flyCarrierLocal(level, exit, carrier.bayVelocity(carrierService.slot()), false)"));
        assertTrue(recovery.contains("CarrierDroneServiceAdapter.exitWaypoint(carrier.getBoundingBox(), position(), staging)"));
        assertTrue(recovery.indexOf("EXIT BLOCKED / RECOVERY WAIT") < recovery.indexOf("resumeCarrierTask(level, owner)"));
        assertFalse(recovery.contains("taskStack.resume("));
    }
}
