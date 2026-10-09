package jp.morrowgear.drone.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerTerrainToneTest {
    @Test void matchesCharcoalOverlayWithoutLosingOpacity() {
        assertEquals(0xFF121514, ControllerTerrainTone.darken(0xFF121514));
        assertEquals(0xFF717372, ControllerTerrainTone.darken(0xFFFFFFFF));
        assertEquals(0xFF0B730C, ControllerTerrainTone.darken(0xFF00FF00));
    }
    @Test void preservesTerrainDifferencesRatherThanReplacingWithFlatFill() {
        assertNotEquals(ControllerTerrainTone.darken(0xFF204020), ControllerTerrainTone.darken(0xFF406040));
        assertEquals(255, ControllerTerrainTone.darken(0xFF446688) >>> 24);
    }
    @Test void unreceivedPatternBypassesToneAndMarkersDrawAfterTerrain() throws Exception {
        String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java"));
        int unknown = source.indexOf("if (height == Integer.MIN_VALUE)");
        int tone = source.indexOf("ControllerTerrainTone.darken", unknown);
        String branch = source.substring(unknown, tone);
        assertTrue(branch.contains("(columnIndex + row) % 6 < 2 ? BORDER : BG"));
        assertTrue(branch.contains("continue;"));
        int render = source.indexOf("private void drawMap(");
        assertTrue(source.indexOf("drawTerrain(g, map)", render) < source.indexOf("drawPatrolRoutes(g, map, drones)", render));
        assertTrue(source.indexOf("drawTerrain(g, map)", render) < source.indexOf("drawDiamond(g, point.x", render));
    }
}
