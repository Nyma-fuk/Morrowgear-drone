package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class DockGeometryCullingTest {
    @Test void visibleEdgeSurvivesCenterOutsideFrustumAtWorldCoordinates() {
        var frustum = new Frustum(new Matrix4f(), new Matrix4f());
        frustum.prepare(1000, 64, -2000);
        assertFalse(frustum.isVisible(new AABB(1002.5, 64, -2000.5, 1003.5, 65, -1999.5)));
        assertTrue(frustum.isVisible(bounds(1003, 64, -2000)));
        assertFalse(frustum.isVisible(bounds(1005, 64, -2000)));
        assertTrue(frustum.isVisible(bounds(1000, 64, -2003)));
        assertFalse(frustum.isVisible(bounds(1000, 64, -2005)));
        assertTrue(frustum.isVisible(bounds(1000, 64, -2000)));
    }

    private static AABB bounds(double x, double y, double z) {
        return new AABB(x - DockGeometryPolicy.HALF_WIDTH, y + DockGeometryPolicy.MIN_Y,
            z - DockGeometryPolicy.HALF_WIDTH, x + DockGeometryPolicy.HALF_WIDTH,
            y + DockGeometryPolicy.MAX_Y, z + DockGeometryPolicy.HALF_WIDTH);
    }
}
