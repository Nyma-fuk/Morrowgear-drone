package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DockGeometryPolicyTest {
    @Test void distanceUsesEntirePlatformIncludingCornersAndVerticalExtent() {
        assertEquals(0, DockGeometryPolicy.distanceSquared(2.5, .41, -2.5));
        assertEquals(0, DockGeometryPolicy.distanceSquared(-2.5, 0, 2.5));
        assertEquals(9, DockGeometryPolicy.distanceSquared(5.52, .2, 0), 1e-10);
        assertEquals(4, DockGeometryPolicy.distanceSquared(0, 2.44, 0), 1e-10);
        assertEquals(4, DockGeometryPolicy.distanceSquared(0, -2.02, 0), 1e-10);
        assertEquals(18, DockGeometryPolicy.distanceSquared(-5.52, .2, 5.52), 1e-10);
    }

    @Test void everyDistanceRetainsAMeshAndThresholdsAreInclusive() {
        assertEquals(DockGeometryPolicy.Detail.FULL, DockGeometryPolicy.detail(0));
        assertEquals(DockGeometryPolicy.Detail.FULL, DockGeometryPolicy.detail(36));
        assertEquals(DockGeometryPolicy.Detail.MEDIUM, DockGeometryPolicy.detail(Math.nextUp(36.0)));
        assertEquals(DockGeometryPolicy.Detail.MEDIUM, DockGeometryPolicy.detail(400));
        assertEquals(DockGeometryPolicy.Detail.FAR, DockGeometryPolicy.detail(Math.nextUp(400.0)));
        assertEquals(DockGeometryPolicy.Detail.FAR, DockGeometryPolicy.detail(64 * 64));
    }

    @Test void edgeRemainsInRangeWhenCenterIsOutsideVanillaRange() {
        assertTrue(DockGeometryPolicy.distanceSquared(65, .2, 0) < 64 * 64);
        assertTrue(DockGeometryPolicy.distanceSquared(67, .2, 0) > 64 * 64);
    }
}
