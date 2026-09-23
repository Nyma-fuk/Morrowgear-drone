package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class RuntimeDesignV23Test {
    private static final Path ASSETS = Path.of("src/main/resources/assets/morrowgear_drone");

    @ParameterizedTest
    @ValueSource(strings = {"field", "scout", "cargo", "engineer", "security", "salvage"})
    void airframeHasBoundedGeometryTwoRotorsLandedPoseAndEmissiveSurfaces(String role) throws Exception {
        Mesh full = inspect(role), lod = inspect(role + "_lod"), far = inspect(role + "_far", role + "_lod");
        assertTrue(full.vertices > lod.vertices && lod.vertices > far.vertices, role + " staged distance simplification");
        for (Mesh mesh : List.of(full, lod, far)) {
            assertTrue(mesh.rotors.containsAll(Set.of(1, 2)), "both rotors animate");
            assertTrue(mesh.emissive > 0, "explicit luminous materials");
            assertTrue(mesh.moving > 0, "landing gear pose is present");
            assertEquals(3.0, mesh.maxX - mesh.minX, .03, "three-block span");
            assertTrue(mesh.maxZ - mesh.minZ <= 3.01, "three-block length envelope");
            assertEquals(0, mesh.landedFloor, .003, "feet meet the deck");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dock", "controller", "tactical_visor", "recovery_tool", "solar_service_station", "charging_relay", "service_light"})
    void equipmentMeshesHaveValidAtlasAndCompleteBinaryData(String name) throws Exception { inspect(name); }

    @Test void controllerScreenIsTexturedEmissiveWithoutCollapsedHousingProtrusions() throws Exception {
        Mesh controller = inspect("controller");
        assertTrue(controller.maxY <= .321, "simplification must preserve the master housing envelope");
        assertTrue(controller.texturedEmissive >= 6, "LCD must use its atlas, not a constant glow color");
    }

    @Test void dockUsesFiveBlockSpanAndPreservesLegacyRegistrations() throws Exception {
        Mesh dock = inspect("dock");
        assertEquals(5, dock.maxX - dock.minX, .01);
        assertEquals(5, dock.maxZ - dock.minZ, .01);
        for (int i = 0; i < 25; i++) assertTrue(Files.readString(ASSETS.resolve("blockstates/dock_wide_part_" + i + ".json")).contains("dock_wide_empty"));
        for (int i = 0; i < 9; i++) assertTrue(Files.exists(ASSETS.resolve("blockstates/dock_part_" + i + ".json")));
    }

    @Test void itemTexturesMatchApprovedMastersExactly() throws Exception {
        try (var paths = Files.list(Path.of("docs/design/equipment-hmi-v23/items"))) {
            var masters = paths.filter(p -> p.getFileName().toString().endsWith("-128.png")).toList();
            assertEquals(23, masters.size());
            for (Path master : masters) assertArrayEquals(Files.readAllBytes(master), Files.readAllBytes(
                ASSETS.resolve("textures/item/" + master.getFileName().toString().replace("-128", ""))));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void desktopTextUsesWholeFramebufferPixelsRegardlessOfGuiScale(int guiScale) {
        var metrics = TacticalLayoutPolicy.calculateForGui(1920 / guiScale, 1080 / guiScale, guiScale);
        assertEquals(Math.rint(metrics.scale() * guiScale), metrics.scale() * guiScale, .0001);
        assertTrue(metrics.structurallyValid());
        assertTrue(metrics.width() * metrics.scale() <= 1920.0 / guiScale + 1);
        assertTrue(metrics.height() * metrics.scale() <= 1080.0 / guiScale + 1);
    }

    private Mesh inspect(String name) throws Exception { return inspect(name, name); }

    private Mesh inspect(String name, String texture) throws Exception {
        var atlas = ImageIO.read(ASSETS.resolve("textures/runtime/" + texture + ".png").toFile());
        assertNotNull(atlas); assertTrue(atlas.getWidth() >= 1024);
        Set<Integer> pigments = new HashSet<>();
        for (int y = 0; y < atlas.getHeight(); y += 11)
            for (int x = 0; x < atlas.getWidth(); x += 11) pigments.add(atlas.getRGB(x, y));
        assertTrue(pigments.size() > 16, name + " nonblank albedo");
        Mesh mesh = new Mesh();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(ASSETS.resolve("models/runtime/" + name + ".mgm"))))) {
            assertEquals(0x4d474d34, in.readInt());
            mesh.vertices = in.readInt(); int rotors = in.readInt();
            assertTrue(mesh.vertices > 0 && mesh.vertices <= 400000 && mesh.vertices % 3 == 0);
            assertTrue(rotors >= 2 && rotors <= 8);
            for (int i = 0; i < rotors * 3; i++) assertTrue(Float.isFinite(in.readFloat()));
            for (int v = 0; v < mesh.vertices; v++) {
                float[] values = new float[20];
                for (int i = 0; i < 20; i++) { values[i] = in.readFloat(); assertTrue(Float.isFinite(values[i])); }
                mesh.minX = Math.min(mesh.minX, values[0]); mesh.maxX = Math.max(mesh.maxX, values[0]);
                mesh.minZ = Math.min(mesh.minZ, values[2]); mesh.maxZ = Math.max(mesh.maxZ, values[2]);
                mesh.maxY = Math.max(mesh.maxY, values[1]);
                mesh.landedFloor = Math.min(mesh.landedFloor, values[7]);
                if (Math.abs(values[0] - values[6]) + Math.abs(values[1] - values[7]) + Math.abs(values[2] - values[8]) > .001) mesh.moving++;
                assertTrue(values[18] >= -.001 && values[18] <= 1.001 && values[19] >= -.001 && values[19] <= 1.001);
                in.readInt(); int group = in.readUnsignedByte(), flags = in.readUnsignedByte();
                assertTrue(group <= rotors); assertTrue(flags <= 2);
                mesh.rotors.add(group); mesh.emissive += flags;
                if (flags == 2) mesh.texturedEmissive++;
            }
            assertEquals(-1, in.read(), "no truncated or trailing geometry");
        }
        return mesh;
    }

    private static final class Mesh {
        int vertices, moving, emissive, texturedEmissive;
        double maxY = Double.NEGATIVE_INFINITY;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        double landedFloor = Double.POSITIVE_INFINITY;
        Set<Integer> rotors = new HashSet<>();
    }
}
