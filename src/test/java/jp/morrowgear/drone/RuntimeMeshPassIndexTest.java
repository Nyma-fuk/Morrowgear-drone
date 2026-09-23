package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RuntimeMeshPassIndexTest {
    @Test void rendererReusesPoseVectorsInsteadOfAllocatingPerVertex() throws Exception {
        String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/RuntimeMesh.java"));
        assertTrue(source.contains("Vector3f transformedPosition = new Vector3f(), transformedNormal = new Vector3f()"));
        assertTrue(source.contains("pose.pose().transformPosition(x, y, z, transformedPosition)"));
        assertTrue(source.contains("pose.transformNormal(nx, ny, nz, transformedNormal)"));
        assertFalse(source.contains("out.addVertex(pose, x, y, z)"));
        assertFalse(source.contains("setNormal(pose, nx, ny, nz)"));
    }

    @Test void preindexedPassesPreserveEverySelectedTriangleInOriginalOrder() {
        Random random = new Random(73);
        for (int size : new int[]{0, 1, 5, 100, 10000}) {
            byte[] flags = new byte[size * 3];
            random.nextBytes(flags);
            for (int i = 0; i < flags.length; i++) flags[i] = (byte)Math.floorMod(flags[i], 3);
            for (int surface = 1; surface <= 2; surface++) {
                var old = new ArrayList<Integer>();
                for (int i = 0; i < flags.length; i += 3) if (flags[i] == surface) old.add(i);
                assertArrayEquals(old.stream().mapToInt(Integer::intValue).toArray(), RuntimeMeshPassIndex.triangles(flags, surface));
            }
        }
    }

    @Test void invalidTriangleBuffersCannotBeIndexed() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeMeshPassIndex.triangles(new byte[2], 1));
        assertThrows(IllegalArgumentException.class, () -> RuntimeMeshPassIndex.triangles(new byte[3], 0));
        assertThrows(IllegalArgumentException.class, () -> RuntimeMeshPassIndex.triangles(new byte[3], 3));
    }

    @Test void fixedPoseShortcutOnlyAppliesToExactAnimationEndpoints() {
        assertEquals(0, RuntimeMeshPassIndex.fixedPose(0, 0));
        assertEquals(6, RuntimeMeshPassIndex.fixedPose(1, 0));
        assertEquals(12, RuntimeMeshPassIndex.fixedPose(0, 1));
        for (float[] pose : new float[][]{{.5f, 0}, {0, .5f}, {.5f, .25f}, {1, 1}, {.999f, 0}})
            assertEquals(-1, RuntimeMeshPassIndex.fixedPose(pose[0], pose[1]));
    }

    @Test void packagedMeshesKeepTheirEmissionSurfacesAndPoseEndpoints() throws Exception {
        Path root = Path.of("src/main/resources/assets/morrowgear_drone/models/runtime");
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".mgm")).toList()) {
                try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                    assertEquals(0x4D474D34, in.readInt());
                    int count = in.readInt(), rotors = in.readInt();
                    in.skipNBytes(rotors * 12L);
                    byte[] flags = new byte[count];
                    float[] vertex = new float[20];
                    for (int i = 0; i < count; i++) {
                        for (int j = 0; j < 20; j++) vertex[j] = in.readFloat();
                        in.readInt(); in.readByte(); flags[i] = in.readByte();
                        for (int component = 0; component < 6; component++) {
                            float base = vertex[component];
                            assertEquals(base + (vertex[component + 6] - base), vertex[component + 6], .00001);
                            assertEquals(base + (vertex[component + 12] - base), vertex[component + 12], .00001);
                        }
                    }
                    for (int surface = 1; surface <= 2; surface++) {
                        int[] selected = RuntimeMeshPassIndex.triangles(flags, surface);
                        int cursor = 0;
                        for (int i = 0; i < count; i += 3)
                            if (flags[i] == surface) assertEquals(i, selected[cursor++], path.toString());
                        assertEquals(cursor, selected.length);
                    }
                    int faces = count / 3;
                    int indexed = faces + 2 * RuntimeMeshPassIndex.triangles(flags, 1).length
                        + RuntimeMeshPassIndex.triangles(flags, 2).length;
                    assertTrue(indexed <= faces * 4);
                    assertEquals(-1, in.read());
                }
            }
        }
    }
}
