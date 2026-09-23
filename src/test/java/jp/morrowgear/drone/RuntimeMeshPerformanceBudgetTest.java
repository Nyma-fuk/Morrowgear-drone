package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RuntimeMeshPerformanceBudgetTest {
    private static final Path ROOT = Path.of("src/main/resources/assets/morrowgear_drone/models/runtime");
    private static final String[] ROLES = {"field", "scout", "cargo", "engineer", "security", "salvage"};

    @Test
    void aircraftMeshesHaveRealNearAndDistantBudgets() throws Exception {
        int worstNear = 0, worstDistant = 0, worstFar = 0;
        for (String role : ROLES) {
            int near = triangles(role), distant = triangles(role + "_lod"), far = triangles(role + "_far");
            assertTrue(near <= 35_000, role + " near mesh exceeds the per-aircraft frame budget: " + near);
            assertTrue(distant <= 28_000, role + " distant mesh exceeds the per-aircraft frame budget: " + distant);
            assertTrue(distant < near, role + " LOD is not meaningfully smaller than its near mesh");
            assertTrue(far <= 4_000, role + " far mesh exceeds the per-aircraft frame budget: " + far);
            assertTrue(far <= distant * .35, role + " far LOD reduction is too small: " + far + "/" + distant);
            worstNear = Math.max(worstNear, near);
            worstDistant = Math.max(worstDistant, distant);
            worstFar = Math.max(worstFar, far);
        }
        int carrier = triangles("carrier_lod"), carrierFar = triangles("carrier_far");
        assertTrue(carrier + worstDistant * 5 <= 155_000,
            "the observed one-carrier/five-aircraft scene exceeds its geometry budget");
        assertTrue(carrierFar <= 5_000 && carrierFar <= carrier * .40,
            "carrier far LOD is not bounded: " + carrierFar + "/" + carrier);
        assertTrue(carrierFar + 8 * 4_000 <= 37_000,
            "far one-carrier/eight-aircraft scene exceeds its strict geometry budget");
        assertTrue(carrier + worstNear + worstDistant + 22 * worstFar <= 160_000,
            "dense close carrier/24-aircraft adaptive scene exceeds its geometry budget");
    }

    @Test
    void distantAircraftDoesNotSubmitTheSecondSortedGlowShell() throws Exception {
        String renderer = Files.readString(Path.of(
            "src/client/java/jp/morrowgear/drone/client/DroneRenderer.java"));
        String mesh = Files.readString(Path.of(
            "src/client/java/jp/morrowgear/drone/client/RuntimeMesh.java"));
        String policy = Files.readString(Path.of(
            "src/client/java/jp/morrowgear/drone/client/RuntimeGeometryBudgetPolicy.java"));
        assertTrue(policy.contains("FULL_DISTANCE_SQUARED = 12 * 12"));
        assertTrue(policy.contains("MEDIUM_DISTANCE_SQUARED = 24 * 24"));
        assertTrue(renderer.contains("RuntimeMesh.load(name + \"_far\", name + \"_lod\")"));
        assertTrue(renderer.contains("RuntimeGeometryBudgetController.drone(state.entityId, camera.pos)"));
        assertTrue(renderer.contains("state.combatState.active(), !distant, !distant)"));
        assertTrue(mesh.contains("powered && halo && signalTriangles.length > 0"));
        assertTrue(mesh.contains("powered && texturedGlow && texturedTriangles.length > 0"));
    }

    private static int triangles(String name) throws Exception {
        try (var input = new DataInputStream(Files.newInputStream(ROOT.resolve(name + ".mgm")))) {
            assertTrue(input.readInt() == 0x4D474D34, name + " is not MGM4");
            int vertices = input.readInt();
            assertTrue(vertices > 0 && vertices % 3 == 0, name + " has invalid triangle records");
            return vertices / 3;
        }
    }
}
