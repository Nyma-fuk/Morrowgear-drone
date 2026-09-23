package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierHeadingTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void turnsAtRestUntilTheNegativeZNoseFacesTheDestination() {
        Vec3 origin = new Vec3(8.5, 150, 8.5), target = origin.add(544, 0, 0);
        float yaw = 0;
        for (int tick = 0; tick < 45; tick++) {
            var next = CarrierNavigation.advance(origin, target, yaw, Vec3.ZERO);
            assertEquals(Vec3.ZERO, next.velocity());
            assertEquals(-2, CarrierNavigation.angleDifference(next.yaw(), yaw), 1e-6);
            yaw = next.yaw();
        }
        assertEquals(-90, yaw, 1e-6);
        var first = CarrierNavigation.advance(origin, target, yaw, Vec3.ZERO);
        assertEquals(CarrierNavigation.ACCELERATION, first.velocity().x, 1e-9);
        assertEquals(0, first.velocity().z, 1e-9);
        assertEquals(new Vec3(1, 0, 0).x, CarrierNavigation.rotate(new Vec3(0, 0, -1), yaw).x, 1e-9);
    }

    @Test void aNewSidewaysDestinationBrakesOnTheOldHeadingBeforeTurning() {
        Vec3 position = new Vec3(0, 150, 0), target = new Vec3(544, 150, 0), velocity = new Vec3(0, 0, -.5);
        for (int tick = 0; velocity.length() > 1e-8; tick++) {
            assertTrue(tick < 21);
            var motion = CarrierNavigation.advance(position, target, 0, velocity);
            assertEquals(0, motion.yaw());
            assertEquals(0, motion.velocity().x, 1e-10);
            assertTrue(motion.velocity().z <= 0);
            assertTrue(velocity.length() - motion.velocity().length() <= CarrierNavigation.BRAKING + 1e-10);
            position = position.add(motion.velocity()); velocity = motion.velocity();
        }
        var turning = CarrierNavigation.advance(position, target, 0, velocity);
        assertEquals(Vec3.ZERO, turning.velocity());
        assertEquals(-2, turning.yaw(), 1e-6);
    }

    @Test void realSteeringPolicyCompletesLongAndVerticalLegsWithBoundedAcceleration() {
        for (Vec3 offset : java.util.List.of(new Vec3(544, 0, 0), new Vec3(-700, 20, 500), new Vec3(0, 50, 0), new Vec3(.01, 0, 0))) {
            Vec3 position = new Vec3(8.5, 150, 8.5), target = position.add(offset), velocity = Vec3.ZERO;
            float yaw = 0;
            int tick = 0;
            while (position.distanceToSqr(target) > 1e-16) {
                assertTrue(tick++ < 4000, "navigation must terminate: " + offset);
                var next = CarrierNavigation.advance(position, target, yaw, velocity);
                assertTrue(next.velocity().length() <= CarrierNavigation.SPEED + 1e-10);
                assertTrue(next.velocity().length() - velocity.length() <= CarrierNavigation.ACCELERATION + 1e-8);
                assertTrue(velocity.length() - next.velocity().length() <= CarrierNavigation.BRAKING + 1e-8);
                assertTrue(Math.abs(CarrierNavigation.angleDifference(next.yaw(), yaw)) <= CarrierNavigation.YAW_SLEW + 1e-5);
                if (Math.abs(CarrierNavigation.angleDifference(next.yaw(), yaw)) > 1e-4) assertEquals(Vec3.ZERO, next.velocity());
                Vec3 local = CarrierNavigation.rotate(next.velocity(), -next.yaw());
                assertEquals(0, local.x, 1e-6, "no sideways drift");
                assertTrue(local.z <= 1e-8, "no backwards acceleration");
                position = position.add(next.velocity()); velocity = next.velocity(); yaw = next.yaw();
            }
            assertTrue(position.distanceToSqr(target) <= 1e-16);
        }
    }

    @Test void headingWrapTakesTheShortArc() {
        Vec3 delta = CarrierNavigation.rotate(new Vec3(0, 0, -500), -179);
        var motion = CarrierNavigation.advance(Vec3.ZERO, delta, 179, Vec3.ZERO);
        assertEquals(2, CarrierNavigation.angleDifference(motion.yaw(), 179), 1e-5);
        assertEquals(Vec3.ZERO, motion.velocity());
    }

    @Test void acceptance544BlockOutAndBackReachesTheEntityArrivalThresholdWithinEachTimeout() {
        Vec3 origin = new Vec3(8.5, 150.5, 8.5), position = origin, velocity = Vec3.ZERO;
        float yaw = 0;
        int totalTicks = 0;
        for (Vec3 target : java.util.List.of(origin.add(544, 0, 0), origin)) {
            int legTicks = 0;
            double distance = 0;
            while (position.distanceToSqr(target) >= 1e-16) {
                assertTrue(++legTicks < 7000, "arrival may not approach the threshold forever");
                var motion = CarrierNavigation.advance(position, target, yaw, velocity);
                distance += motion.velocity().length();
                position = position.add(motion.velocity()); velocity = motion.velocity(); yaw = motion.yaw();
            }
            assertTrue(distance >= 512);
            assertTrue(position.distanceToSqr(target) < 1e-16);
            velocity = Vec3.ZERO;
            totalTicks += legTicks;
        }
        assertTrue(totalTicks + 120 < 16000, "ordinary fuel wait also fits the acceptance run budget");
    }

    @Test void originGeometryAndAllFourBayCoordinatesRemainCompatibleAtZeroYaw() {
        Vec3 origin = new Vec3(8.5, 150, 8.5);
        assertEquals(CarrierEntity.exteriorBounds(origin), CarrierNavigation.bounds(origin, 0));
        for (int slot = 0; slot < 4; slot++) {
            Vec3 bay = new Vec3(CarrierPolicy.bayX(slot), 2.4, CarrierPolicy.bayZ(slot));
            assertEquals(origin.add(bay), CarrierNavigation.toWorld(origin, 0, bay));
        }
    }

    @Test void baysBoardingAndExitUseOneInvertibleRotation() {
        Vec3 origin = new Vec3(110.5, 150, -37.5);
        for (float yaw : new float[]{0, 45, 90, 179, -90, 360}) {
            for (int slot = 0; slot < 4; slot++) {
                Vec3 local = new Vec3(CarrierPolicy.bayX(slot), 2.4, CarrierPolicy.bayZ(slot));
                Vec3 world = CarrierNavigation.toWorld(origin, yaw, local);
                assertTrue(CarrierNavigation.toLocal(origin, yaw, world).distanceToSqr(local) < 1e-20);
                assertTrue(CarrierNavigation.bounds(origin, yaw).contains(world));
                Vec3 staging = CarrierNavigation.toWorld(origin, yaw, new Vec3(local.x, -2, local.z));
                assertEquals(world.x, staging.x, 1e-9); assertEquals(world.z, staging.z, 1e-9);
                assertEquals(4.4, world.y - staging.y, 1e-9);
            }
            Vec3 pad = CarrierNavigation.toWorld(origin, yaw, new Vec3(0, -30, 18));
            Vec3 localPad = CarrierNavigation.toLocal(origin, yaw, pad);
            assertTrue(CarrierPolicy.inBoardingProjection(localPad.x, localPad.z));
            assertTrue(localPad.z >= 16);
        }
    }

    @Test void rotationSweepContainsEveryIntermediateCornerNotJustBothEndpointBoxes() {
        Vec3 from = new Vec3(8.5, 150, 8.5), to = from.add(.4, .1, .2);
        for (float yaw : new float[]{0, 26, 45, 88, 179, -181}) {
            var sweep = CarrierNavigation.sweep(from, yaw, to, yaw + 2);
            for (int sample = 0; sample <= 100; sample++) {
                double progress = sample / 100.0;
                Vec3 center = from.add(to.subtract(from).scale(progress));
                for (double x : new double[]{-13, 13}) for (double y : new double[]{0, 11}) for (double z : new double[]{-25, 25}) {
                    Vec3 corner = CarrierNavigation.toWorld(center, yaw + (float)(2 * progress), new Vec3(x, y, z));
                    assertTrue(sweep.contains(corner), "intermediate rotating corner outside sweep");
                }
            }
        }
    }

    @Test void allHeadingSweepsFitWithinTheExistingCurrentChunkTicketRadius() {
        for (int sub = 0; sub < 16; sub++) for (int yaw = -180; yaw < 180; yaw++) {
            Vec3 origin = new Vec3(sub + .1, 150, 15.9 - sub);
            var sweep = CarrierNavigation.sweep(origin, yaw, origin.add(.5, 0, 0), yaw + 2);
            for (double x : new double[]{sweep.minX, sweep.maxX}) for (double z : new double[]{sweep.minZ, sweep.maxZ}) {
                assertTrue(Math.abs(((int)Math.floor(x)) >> 4) <= CarrierNavigation.TICKET_RADIUS);
                assertTrue(Math.abs(((int)Math.floor(z)) >> 4) <= CarrierNavigation.TICKET_RADIUS);
            }
        }
    }
}
