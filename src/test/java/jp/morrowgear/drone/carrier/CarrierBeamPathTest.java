package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierBeamPathTest {
    @Test void worldContractRetainsVisibleNonzeroEndpointsAtLargeCoordinates() {
        Vec3 carrier = new Vec3(29_000_000.5, 180, -29_000_000.5);
        Vec3 origin = CarrierBeamPath.emitter(carrier, 137);
        Vec3 target = new Vec3(28_999_984.25, 64.75, -28_999_991.5);
        Vec3 encoded = CarrierBeamPath.encodeTarget(origin, target);
        CarrierBeamPath decoded = CarrierBeamPath.decode(origin,
            new Vec3((float) encoded.x, (float) encoded.y, (float) encoded.z));

        assertTrue(decoded.visibleDownwardRay());
        assertTrue(decoded.origin().distanceToSqr(decoded.target()) > 1);
        assertEquals(target.x, decoded.target().x, 1.0e-4);
        assertEquals(target.y, decoded.target().y, 1.0e-4);
        assertEquals(target.z, decoded.target().z, 1.0e-4);
    }

    @Test void renderConversionMovesBothWorldEndpointsByExactlyTheSameOrigin() {
        CarrierBeamPath path = new CarrierBeamPath(new Vec3(80.5, 150.4, -20.5), new Vec3(83.5, 70.5, -18.5));
        Vec3 renderOrigin = new Vec3(80.25, 150.5, -20.75);
        var local = path.toRenderLocal(renderOrigin);
        assertEquals(path.origin(), local.origin().add(renderOrigin));
        assertEquals(path.target(), local.target().add(renderOrigin));
        assertTrue(local.origin().distanceToSqr(local.target()) > 1);
    }

    @Test void zeroUpwardAndNonfiniteRaysAreNeverPresented() {
        Vec3 origin = new Vec3(0, 100, 0);
        assertFalse(new CarrierBeamPath(origin, origin).visibleDownwardRay());
        assertFalse(new CarrierBeamPath(origin, origin.add(0, 1, 0)).visibleDownwardRay());
        assertFalse(new CarrierBeamPath(origin, new Vec3(Double.NaN, 0, 0)).visibleDownwardRay());
        assertTrue(new CarrierBeamPath(origin, origin.add(.01, -1, 0)).visibleDownwardRay());
    }
}
