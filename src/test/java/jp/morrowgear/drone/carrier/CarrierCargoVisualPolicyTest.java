package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierCargoVisualPolicyTest {
    @Test void eightPhysicalHardpointsFormFourMirroredPairsWithinTheApprovedHull() {
        var pods = CarrierCargoVisualPolicy.pods(0xFF);
        assertEquals(CarrierExpansionPolicy.HARDPOINTS, pods.size());
        for (int row = 0; row < 4; row++) {
                var left = CarrierCargoVisualPolicy.hardpoint(row);
                var right = CarrierCargoVisualPolicy.hardpoint(row + 4);
                assertEquals(-left.x(), right.x(), 1e-9);
                assertEquals(left.y(), right.y(), 1e-9);
                assertEquals(left.z(), right.z(), 1e-9);
                assertEquals(left.ordnanceCapable(),right.ordnanceCapable());
                assertEquals(row, left.hardpoint()); assertEquals(row + 4, right.hardpoint());
                assertTrue(Math.abs(left.x()) + 1.3 < CarrierPolicy.WIDTH / 2);
                assertTrue(Math.abs(left.x()) - 1.3 > 6.35, "central plasma aperture must stay clear");
                assertTrue(Math.abs(left.z()) + 1.2 < CarrierPolicy.LENGTH / 2);
        }
        assertEquals(0x55,CarrierCargoVisualPolicy.ordnanceMask());
        for(int slot=0;slot<8;slot++)assertEquals((slot&1)==0,
            CarrierCargoVisualPolicy.ordnanceCapable(slot));
    }

    @Test void occupancyMaskSelectsOnlyInstalledHardpoints() {
        var pods=CarrierCargoVisualPolicy.pods((1<<1)|(1<<6));
        assertEquals(2,pods.size());
        assertEquals(1,pods.get(0).hardpoint()); assertEquals(6,pods.get(1).hardpoint());
    }

    @Test void carrierAlignmentPlacesEverySelectedHardpointVerticallyOverContainerAtAnyHeading() {
        BlockPos container=new BlockPos(213,72,-91);
        for(float yaw:new float[]{0,37,90,181,-64})for(int slot=0;slot<CarrierExpansionPolicy.HARDPOINTS;slot++){
            Vec3 carrier=CarrierCargoVisualPolicy.alignedCarrierPosition(container,88,yaw,slot);
            Vec3 hardpoint=CarrierCargoVisualPolicy.hardpointWorld(carrier,yaw,slot);
            Vec3 center=Vec3.atBottomCenterOf(container);
            assertEquals(center.x,hardpoint.x,1e-9);
            assertEquals(center.z,hardpoint.z,1e-9);
            assertEquals(88+CarrierCargoVisualPolicy.POD_Y,hardpoint.y,1e-9);
        }
    }

    @Test void transferYawPlacesTheHardpointOnTheNearestSideOfTheLoadAndRemainsStable() {
        Vec3 current=new Vec3(0,88,0);
        BlockPos container=new BlockPos(100,64,0);
        for(int slot=0;slot<CarrierExpansionPolicy.HARDPOINTS;slot++){
            float yaw=CarrierCargoVisualPolicy.nearestAlignmentYaw(current,container,slot,37);
            Vec3 carrier=CarrierCargoVisualPolicy.alignedCarrierPosition(container,88,yaw,slot);
            Vec3 hardpoint=CarrierCargoVisualPolicy.hardpointWorld(carrier,yaw,slot);
            Vec3 center=Vec3.atBottomCenterOf(container);
            var pod=CarrierCargoVisualPolicy.hardpoint(slot);
            assertEquals(center.x,hardpoint.x,1e-8);
            assertEquals(center.z,hardpoint.z,1e-8);
            assertEquals(Math.hypot(center.x-current.x,center.z-current.z)-Math.hypot(pod.x(),pod.z()),
                Math.hypot(carrier.x-current.x,carrier.z-current.z),1e-5);
            assertEquals(yaw,CarrierCargoVisualPolicy.nearestAlignmentYaw(current,container,slot,37),1e-6);
        }
    }

    @Test void fourSlingContactsRemainInsideTheContainerTopPlate() {
        assertEquals(90,CarrierCargoVisualPolicy.CONTAINER_YAW);
        assertTrue(CarrierCargoVisualPolicy.CABLE_CORNER_X<CarrierContainerTransferPolicy.CONTAINER_LENGTH/2);
        assertTrue(CarrierCargoVisualPolicy.CABLE_CORNER_Z<CarrierContainerTransferPolicy.CONTAINER_WIDTH/2);
        assertTrue(CarrierCargoVisualPolicy.POD_Y+CarrierContainerTransferPolicy.CONTAINER_HEIGHT
            > CarrierCargoVisualPolicy.COLLAR_TOP,"the locked container must overlap the hull collar instead of floating below it");
    }

    @Test void containerFiveBlockLongAxisAlwaysFollowsCarrierForeAftAxis() {
        Vec3 carrierAxis=CarrierNavigation.rotate(new Vec3(1,0,0),CarrierCargoVisualPolicy.CONTAINER_YAW);
        assertEquals(0,carrierAxis.x,1e-9,"container long axis must not run across the wing");
        assertEquals(0,carrierAxis.y,1e-9);
        assertEquals(-1,carrierAxis.z,1e-9,"container long axis must follow the carrier nose-tail axis");
    }

    @Test void expansionBaysStayAftOfTheDroneServiceApproachCorridor() {
        double serviceZ = CarrierPolicy.bayZ(0);
        double requiredSeparation = CarrierCargoVisualPolicy.BAY_HALF_Z + 1.5;
        for (int slot = 0; slot < CarrierExpansionPolicy.HARDPOINTS; slot++) {
            var pod = CarrierCargoVisualPolicy.hardpoint(slot);
            assertTrue(pod.z() - serviceZ > requiredSeparation,
                "hardpoint " + slot + " obstructs the three-block drone service approach corridor");
        }
    }
}
