package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CarrierTerrainQueryCacheTest {
    @Test void alternativeSweepsDoNotMultiplyHeightmapQueries() {
        assertEquals(1_550, CarrierTerrainQueryCache.uniqueQueriesForTest(6, 1_550));
        assertEquals(0, CarrierTerrainQueryCache.uniqueQueriesForTest(0, 1_550));
    }

    @Test void productionSweepUsesExactPerTickCache() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java"));
        assertTrue(source.contains("CarrierTerrainQueryCache.height(level, x, z)"));
    }
}
