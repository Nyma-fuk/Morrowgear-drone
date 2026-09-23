package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierCabinControlsContractTest {
    @Test void chunkHeightPointsAtTheVisibleSurfaceForCommonColumns() {
        assertTrue(CarrierExteriorSnapshot.surfaceBlockY(64) == 64, "single-layer grass surface");
        assertTrue(CarrierExteriorSnapshot.surfaceBlockY(63) == 63, "water surface");
        assertTrue(CarrierExteriorSnapshot.surfaceBlockY(80) == 80, "building roof");
    }

    @Test void missingExteriorIsSafeAndCanExplicitlyClearAStaleCabinMap() {
        assertTrue(CarrierExteriorSnapshot.terrain(null) == CarrierViewPayload.Terrain.EMPTY);
        assertTrue(CarrierExteriorSnapshot.candidates(null, UUID.randomUUID()).isEmpty());
        var clear = CarrierViewPayload.Terrain.unavailable("minecraft:overworld");
        assertTrue(clear.specified());
        assertFalse(clear.available());
        assertTrue(CarrierViewPayload.Terrain.EMPTY != clear);
    }

    @Test void exteriorSnapshotIsBoundedAndNeverLoadsTerrainChunks() throws Exception {
        String source = source("src/main/java/jp/morrowgear/drone/carrier/CarrierExteriorSnapshot.java");
        assertTrue(source.contains("TERRAIN_SIDE = 49"));
        assertTrue(source.contains("TERRAIN_SPACING = 4"));
        assertTrue(source.contains("CANDIDATE_RADIUS = 64"));
        assertTrue(source.contains("MAX_CANDIDATES = 32"));
        assertTrue(source.contains("getChunkNow"));
        assertFalse(source.contains("getChunk("));
        assertTrue(source.contains(".limit(MAX_CANDIDATES)"));
    }

    @Test void cabinMenuKeepsAccessGateAndRefreshesExteriorAtTwoSeconds() throws Exception {
        String source = source("src/main/java/jp/morrowgear/drone/carrier/CarrierMenu.java");
        assertTrue(source.contains("if (!accessible(player, id)) return;"));
        assertTrue(source.contains("now - lastExteriorSync >= 40"));
        assertTrue(source.contains("Terrain.unavailable(ship.exterior.dimension())"));
        assertTrue(source.contains("CarrierExteriorSnapshot.terrain(exterior)"));
        assertTrue(source.contains("CarrierExteriorSnapshot.candidates(exterior, ship.owner)"));
        assertFalse(source.contains("reserveBay"));
    }

    @Test void cabinScreenUsesSnapshotCandidatesAndMovesSupplyHintBelowButtons() throws Exception {
        String map = source("src/client/java/jp/morrowgear/drone/client/CarrierMapView.java");
        String screen = source("src/client/java/jp/morrowgear/drone/client/CarrierScreen.java");
        assertTrue(map.contains("exteriorSnapshot.available()"));
        assertTrue(map.contains("snapshot.packedAt(wx, wz)"));
        assertTrue(screen.contains("for (var candidate : view().candidates())"));
        assertTrue(screen.contains("y + 108"));
        assertFalse(screen.contains("y + 62, width - (small() ? 14 : 208)"));
        assertTrue(screen.contains("補給候補なし（半径64ブロック）"));
    }

    @Test void expiredConfirmationStaysOffTheInteractiveSelectionMap() throws Exception {
        String screen = source("src/client/java/jp/morrowgear/drone/client/CarrierScreen.java");
        assertTrue(screen.contains("confirmation.phase() == Phase.EXPIRED_CONFIRM"));
        assertTrue(screen.contains("攻撃範囲を再確認"));
        assertTrue(screen.contains("confirmation.phase() != Phase.SELECT) return false"));
        assertTrue(screen.contains("搭乗地点から離れすぎています"));
        assertTrue(screen.contains("CarrierPolicy.withinBoardingEnvelope"));
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
