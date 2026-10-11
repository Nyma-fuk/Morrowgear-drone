package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierTransferRoutePlannerTest {
    @Test void unobstructedTransferKeepsTheGeometricShortestRoute() {
        Vec3 current = new Vec3(0, 100, 0), target = new Vec3(120, 100, 0);
        var plan = CarrierTransferRoutePlanner.plan(current, target, 250, 0, (from, to) -> true).orElseThrow();

        assertEquals("DIRECT", plan.strategy());
        assertEquals(target, plan.waypoints().getLast());
        assertEquals(120, plan.distance(), 1.0e-9);
        assertEquals(1, plan.checks());
    }

    @Test void aWallAcrossTheDirectLaneUsesTheShorterOpenSideInsteadOfBlindClimbing() {
        Vec3 current = new Vec3(0, 100, 0), target = new Vec3(120, 100, 0);
        var plan = CarrierTransferRoutePlanner.plan(current, target, 250, 0,
            (from, to) -> !crossesWall(from, to, 20, 28, -20, 20, Double.POSITIVE_INFINITY))
            .orElseThrow();

        assertTrue(plan.strategy().equals("LEVEL_LEFT") || plan.strategy().equals("LEVEL_RIGHT"), plan.strategy());
        assertEquals(100, plan.approachY(), 1.0e-9);
        assertEquals(target, plan.waypoints().getLast());
        assertTrue(plan.waypoints().stream().anyMatch(point -> Math.abs(point.z) > 20));
    }

    @Test void theLowestClearOverflightWinsAgainstALongerHorizontalDetour() {
        Vec3 current = new Vec3(0, 100, 0), target = new Vec3(120, 100, 0);
        var plan = CarrierTransferRoutePlanner.plan(current, target, 250, 0,
            (from, to) -> !crossesWall(from, to, 20, 28, -20, 20, 111)).orElseThrow();

        assertEquals("ALTITUDE_16", plan.strategy());
        assertEquals(116, plan.approachY(), 1.0e-9);
        assertEquals(116, plan.waypoints().getLast().y, 1.0e-9);
    }

    @Test void completelyBlockedSpaceHasAFixedEvaluationBudget() {
        AtomicInteger checks = new AtomicInteger();
        Optional<CarrierTransferRoutePlanner.Plan> plan = CarrierTransferRoutePlanner.plan(
            new Vec3(0, 100, 0), new Vec3(120, 100, 0), 250, 0,
            (from, to) -> { checks.incrementAndGet(); return false; });

        assertTrue(plan.isEmpty());
        assertTrue(checks.get() <= CarrierTransferRoutePlanner.MAX_CORRIDOR_CHECKS, "checks=" + checks);
    }

    private static boolean crossesWall(Vec3 from, Vec3 to, double minX, double maxX,
                                       double minZ, double maxZ, double maxY) {
        for (int sample = 0; sample <= 128; sample++) {
            Vec3 point = from.add(to.subtract(from).scale(sample / 128.0));
            if (point.x >= minX && point.x <= maxX && point.z >= minZ && point.z <= maxZ
                && point.y <= maxY) return true;
        }
        return false;
    }
}
