package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierNavigationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void thousandBlockPathUsesBoundedStepsAndSweepsBothEndsOfTheWholeHull() {
        Vec3 current = new Vec3(-20, 150, -20), target = new Vec3(1024, 180, 512);
        int steps = 0;
        while (current.distanceToSqr(target) > 1e-8) {
            assertTrue(steps++ < 3000);
            Vec3 delta = CarrierNavigation.step(current, target), next = current.add(delta);
            assertTrue(delta.length() <= CarrierNavigation.SPEED + 1e-10);
            var sweep = CarrierNavigation.sweep(current, next);
            for (Vec3 position : java.util.List.of(current, next)) {
                var hull = CarrierEntity.exteriorBounds(position);
                assertTrue(sweep.contains(hull.getMinPosition()));
                assertTrue(sweep.contains(hull.getMaxPosition()));
            }
            var ahead = CarrierNavigation.ahead(current, target);
            assertTrue(Math.abs((ahead.getX() >> 4) - (((int) Math.floor(current.x)) >> 4)) <= 1);
            assertTrue(Math.abs((ahead.getZ() >> 4) - (((int) Math.floor(current.z)) >> 4)) <= 1);
            current = next;
        }
        assertEquals(target.x, current.x, 1e-8);
        assertEquals(target.y, current.y, 1e-8);
        assertEquals(target.z, current.z, 1e-8);
    }

    @Test void destinationValidationHasNo128Or4096RangeCapButRejectsInvalidWorldCoordinates() {
        assertTrue(CarrierNavigation.validTarget(new Vec3(1_000_000, 150, -1_000_000), -64, 320));
        assertFalse(CarrierNavigation.validTarget(new Vec3(Double.NaN, 150, 0), -64, 320));
        assertFalse(CarrierNavigation.validTarget(new Vec3(30_000_000, 150, 0), -64, 320));
        assertFalse(CarrierNavigation.validTarget(new Vec3(0, 310, 0), -64, 320));
        assertFalse(CarrierNavigation.validTarget(new Vec3(0, -50, 0), -64, 320));
    }

    @Test void commandedAltitudeIsTheExactBellyPlaneWhileXZUseBlockColumnCenters() {
        var anchor = new CarrierAnchor("minecraft:overworld", -17, 143, 2048);
        assertEquals(new Vec3(-16.5, 143, 2048.5), CarrierNavigation.destination(anchor));
        assertEquals(new Vec3(-16.5, 143, 2048.5), CarrierNavigation.destination(anchor.pos()));
        assertNotEquals(Vec3.atCenterOf(anchor.pos()), CarrierNavigation.destination(anchor));
    }

    @Test void ticketCountIsNotConfusedWithFullChunksOrGenerationHalo() {
        assertEquals(4, CarrierPolicy.MAX_ACTIVE_CHUNK_LEASES);
        assertEquals(8, CarrierNavigation.MAX_TICKETS);
        assertEquals(392, CarrierNavigation.FULL_CHUNK_INFLUENCE_BOUND);
        assertTrue(CarrierNavigation.generationInfluenceBound() > CarrierNavigation.FULL_CHUNK_INFLUENCE_BOUND);
        assertEquals(CarrierNavigation.TICKET_RADIUS, CarrierChunkLeases.RADIUS);
    }

    @Test void acceptedExteriorJourneyRetainsLeaseOnlyWhileOwnerIsOnlineAndRouteActive() {
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        var ship = new CarrierShip(owner, 0, new CarrierAnchor("minecraft:overworld", 8, 150, 8));
        assertFalse(CarrierChunkLeases.wantsLease(id, ship, owner, null, true));
        ship.destination = new CarrierAnchor("minecraft:overworld", 1032, 150, 8);
        ship.navigationPaused = false;
        assertTrue(CarrierChunkLeases.wantsLease(id, ship, owner, null, true));
        assertFalse(CarrierChunkLeases.wantsLease(id, ship, UUID.randomUUID(), null, true));
        assertFalse(CarrierChunkLeases.wantsLease(id, ship, owner, null, false));
        ship.navigationPaused = true;
        assertFalse(CarrierChunkLeases.wantsLease(id, ship, owner, null, true));
        assertTrue(CarrierChunkLeases.wantsLease(id, ship, owner, id, true));
    }

    @Test void tinyResidualDistanceCompletesInsteadOfLeavingAnInvisibleActiveRoute() {
        Vec3 target = new Vec3(10, 120, -4);
        assertTrue(CarrierNavigation.arrived(target.add(1.0e-7, 0, 0), target));
        assertFalse(CarrierNavigation.arrived(target.add(1.0e-4, 0, 0), target));
    }

    @Test void pauseStopAndReloadRetainIntentButNeverResumeAutomatically() {
        var ship = new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 8, 150, 8));
        var target = new CarrierAnchor("minecraft:overworld", 1032, 150, 8);
        ship.destination = target;
        ship.navigationWaitTicks = CarrierNavigation.LOAD_WAIT_TICKS;
        ship.pauseNavigation(CarrierPolicy.Stop.UNLOADED);
        assertEquals(target, ship.destination);
        assertTrue(ship.navigationPaused);
        assertEquals(0, ship.navigationWaitTicks);
        ship.stop(CarrierPolicy.Stop.EMERGENCY);
        assertEquals(target, ship.destination);
        assertTrue(ship.navigationPaused);
        var encoded = CarrierShip.CODEC.encodeStart(JsonOps.INSTANCE, ship).getOrThrow();
        var loaded = CarrierShip.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(target, loaded.destination);
        assertTrue(loaded.navigationPaused);
        assertEquals(CarrierPolicy.Stop.RELOAD, loaded.stop);
        assertEquals(CarrierPolicy.Mode.IDLE, loaded.mode);
        encoded.getAsJsonObject().remove("navigation_intent");
        assertNull(CarrierShip.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().destination);
    }
}
