package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DockGeometryAssetTest {
    private static final Path ROOT = Path.of("src/main/resources/assets/morrowgear_drone/models/runtime");
    private record Cost(int triangles, int signals) {}

    @Test void allLodsKeepStaticPlatformInsideRotatedCullingBounds() throws Exception {
        Cost full = inspect("dock"), medium = inspect("dock_lod"), far = inspect("dock_far");
        assertTrue(medium.triangles <= 6_000 && medium.triangles < full.triangles * .20);
        assertTrue(far.triangles <= 2_000 && far.triangles < medium.triangles * .70);
        assertTrue(medium.signals > 0 && far.signals > 0);
        assertTrue(medium.triangles * 32 <= 192_000, "32 medium Docks exceed scene geometry budget");
        assertTrue(far.triangles * 32 <= 64_000, "32 far Docks exceed scene geometry budget");
        System.out.printf("Dock triangles full/medium/far: %d / %d / %d; submitted vertices: %d / %d / %d%n",
            full.triangles, medium.triangles, far.triangles, 4 * (full.triangles + 2 * full.signals),
            4 * (medium.triangles + medium.signals), 4 * (far.triangles + far.signals));
    }

    private static Cost inspect(String name) throws Exception {
        try (var in = new DataInputStream(Files.newInputStream(ROOT.resolve(name + ".mgm")))) {
            assertEquals(0x4D474D34, in.readInt());
            int vertices = in.readInt(), rotors = in.readInt(), signals = 0;
            assertTrue(vertices > 0 && vertices % 3 == 0 && vertices <= 400000);
            assertTrue(rotors >= 2 && rotors <= 8);
            for (int i = 0; i < rotors * 3; i++) assertTrue(Float.isFinite(in.readFloat()));
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < vertices; i++) {
                float[] values = new float[20];
                for (int j = 0; j < 20; j++) {
                    values[j] = in.readFloat();
                    assertTrue(Float.isFinite(values[j]));
                }
                minX = Math.min(minX, values[0]); maxX = Math.max(maxX, values[0]);
                minZ = Math.min(minZ, values[2]); maxZ = Math.max(maxZ, values[2]);
                for (int pose : new int[]{0, 6, 12}) {
                    for (int axis = 0; axis < 3; axis++) assertEquals(values[axis], values[pose + axis], 1e-5);
                    // A square X/Z envelope contains all four block-facing rotations.
                    assertTrue(Math.abs(values[pose]) + .018 <= DockGeometryPolicy.HALF_WIDTH);
                    assertTrue(Math.abs(values[pose + 2]) + .018 <= DockGeometryPolicy.HALF_WIDTH);
                    assertTrue(values[pose + 1] - .018 >= DockGeometryPolicy.MIN_Y);
                    assertTrue(values[pose + 1] + .018 <= DockGeometryPolicy.MAX_Y);
                }
                assertTrue(values[18] >= 0 && values[18] <= 1 && values[19] >= 0 && values[19] <= 1);
                in.readInt();
                assertEquals(0, in.readUnsignedByte());
                int flag = in.readUnsignedByte();
                assertTrue(flag == 0 || flag == 1);
                if (i % 3 == 0 && flag == 1) signals++;
            }
            assertTrue(minX < -2.49 && minZ < -2.49 && maxX > 2.49 && maxZ > 2.49);
            assertEquals(-1, in.read());
            return new Cost(vertices / 3, signals);
        }
    }
}
