package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SalvageTransferGuidanceTest {
    @Test void transferGoalIsStableAndIncludesLoadHeight() {
        Vec3 dock = new Vec3(23.5, 81.5, -40.5);
        Vec3 goal = SalvageTowPolicy.transferPosition(dock, 1.7);
        assertEquals(dock.x, goal.x);
        assertEquals(dock.z, goal.z);
        assertEquals(87.35, goal.y, .00001);
        assertEquals(goal, SalvageTowPolicy.transferPosition(dock, 1.7));
    }
    @Test void arrivalDoesNotAcceptLowerNormalDockLaneOrOvershoot() {
        Vec3 goal = SalvageTowPolicy.transferPosition(Vec3.ZERO, 1.7);
        assertFalse(SalvageTowPolicy.alignedForTransfer(goal.add(0,-3,0),goal,0));
        assertFalse(SalvageTowPolicy.alignedForTransfer(goal,goal,.6));
        assertTrue(SalvageTowPolicy.alignedForTransfer(goal.add(.3,0,0),goal,.1));
    }
    @Test void largerLoadRaisesDestinationNotJustLastRoutePoint() {
        Vec3 small = SalvageTowPolicy.transferPosition(Vec3.ZERO, 1);
        Vec3 large = SalvageTowPolicy.transferPosition(Vec3.ZERO, 3);
        assertEquals(2,large.y-small.y,.00001);
    }
}
