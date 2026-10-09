package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CarrierMiningPresentationContractTest {
    private static String source(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test void rendererUsesSyncedOperationAndProceduralBoundedGeometryWithoutExteriorAssetChanges() throws Exception {
        String entity = source("src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java");
        String renderer = source("src/client/java/jp/morrowgear/drone/client/CarrierRenderer.java");
        String geometry = source("src/client/java/jp/morrowgear/drone/client/CarrierEffectGeometry.java");
        assertTrue(entity.contains("MINING_SURFACE_Y"));
        assertTrue(entity.contains("op.surfaceY()"));
        assertTrue(renderer.contains("CarrierMiningEffectPolicy.scanChunk"));
        assertTrue(renderer.contains("CarrierMiningEffectPolicy.RING_EMITTERS"));
        assertTrue(renderer.contains("wingScanOrigin"));
        assertTrue(renderer.contains("CarrierEffectGeometry.sphere"));
		assertTrue(renderer.contains("visual.plasmaProgress()"));
		assertTrue(renderer.contains("CarrierMiningEffectPolicy.emitterIntensity"));
        assertTrue(renderer.contains("CarrierEffectGeometry.releaseRing"));
        assertTrue(renderer.contains("CarrierEffectGeometry.circleOutline"));
        assertTrue(geometry.contains("radius > 32"), "release geometry must remain bounded around the 3x3 impact envelope");
        assertFalse(renderer.contains("setBlock"));
        assertFalse(renderer.contains("Heightmap"));
		assertFalse(renderer.contains("CarrierEffectGeometry.workColumn"),
			"the released mining core must fall as a sphere, not turn into a vertical beam");
    }
}
