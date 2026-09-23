package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Packaged geometry and wiring checks; visual fidelity still requires rendered comparison. */
class CarrierPresentationIntegrationTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/morrowgear_drone");
    @Test void nearAndFarMeshesShareTheApprovedPhysicalContract() throws Exception {
        int near = mesh("carrier", 25_000);
        int far = mesh("carrier_lod", 15_000);
        assertTrue(far < near);
    }
    private int mesh(String name, int triangleBudget) throws Exception {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(ASSETS.resolve("models/runtime/" + name + ".mgm"))))) {
            assertEquals(0x4D474D34, in.readInt());
            int count = in.readInt();
            assertTrue(count > 0 && count % 3 == 0 && count <= triangleBudget * 3);
            assertEquals(2, in.readInt());
            for (int i = 0; i < 6; i++) assertTrue(Float.isFinite(in.readFloat()));
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            Set<String> points = new HashSet<>(), mirrored = new HashSet<>();
            int cyan = 0;
            for (int i = 0; i < count; i++) {
                float[] vertex = new float[20];
                for (int j = 0; j < 20; j++) { vertex[j] = in.readFloat(); assertTrue(Float.isFinite(vertex[j])); }
                for (int j = 0; j < 6; j++) {
                    assertEquals(vertex[j], vertex[j + 6]); assertEquals(vertex[j], vertex[j + 12]);
                }
                for (int j = 0; j < 3; j++) { min[j] = Math.min(min[j], vertex[j]); max[j] = Math.max(max[j], vertex[j]); }
                points.add(point(vertex[0], vertex[1], vertex[2]));
                mirrored.add(point(-vertex[0], vertex[1], vertex[2]));
                assertTrue(vertex[18] >= 0 && vertex[18] <= 1 && vertex[19] >= 0 && vertex[19] <= 1);
                in.readInt(); assertEquals(0, in.readUnsignedByte());
                int flag = in.readUnsignedByte(); assertTrue(flag <= 2); if (flag == 1) cyan++;
            }
            assertArrayEquals(new float[]{-13, 0, -25}, min, .01f);
            assertArrayEquals(new float[]{13, 11, 25}, max, .01f);
            assertEquals(points, mirrored, "The model must remain bilaterally symmetric");
            assertTrue(cyan > 0); assertEquals(-1, in.read());
            var atlas = ImageIO.read(ASSETS.resolve("textures/runtime/" + name + ".png").toFile());
            assertNotNull(atlas); assertTrue(atlas.getWidth() >= 1024 && atlas.getHeight() >= 1024);
            return count / 3;
        }
    }
    private static String point(float x, float y, float z) {
        return Math.round(x * 1000) + ":" + Math.round(y * 1000) + ":" + Math.round(z * 1000);
    }
    @Test void beamVisibilityIncludesItsSpanAndUsesDepthTestedPipelines() throws Exception {
        String renderer = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/CarrierRenderer.java"));
        String renderTypes = Files.readString(Path.of("src/client/java/net/minecraft/client/renderer/rendertype/MorrowgearRenderTypes.java"));
        assertTrue(renderer.contains("getBoundingBoxForCulling"));
        assertTrue(renderer.contains("entity.beamPaths()"));
        assertTrue(renderer.contains("path.origin(), path.target()"));
        assertTrue(renderer.contains("path.toRenderLocal(origin)"));
        assertTrue(renderer.contains("MorrowgearRenderTypes.energyBeam()"));
        assertTrue(renderer.contains("MorrowgearRenderTypes.visibleEnergyCore()"));
        assertFalse(renderer.contains("NO_DEPTH"));
        assertEquals(2, renderTypes.split("CompareOp.GREATER_THAN_OR_EQUAL", -1).length - 1);
        assertFalse(renderTypes.contains("CompareOp.ALWAYS_PASS"));
        assertFalse(renderTypes.contains("CompareOp.LESS_THAN_OR_EQUAL"));
    }
    @Test void boardingMarkerUsesTheServerPadAndOnlyShowsToItsOwner() throws Exception {
        String renderer = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/CarrierRenderer.java"));
        assertTrue(renderer.contains("entity.visualBoardingPad()"));
        assertTrue(renderer.contains("entity.isOwnedBy(player.getUUID())"));
        assertTrue(renderer.contains("eye.distanceToSqr(state.boardingPad) < 96 * 96"));
        assertFalse(renderer.contains("Heightmap"), "The renderer must not invent a different boarding surface");
    }
}
