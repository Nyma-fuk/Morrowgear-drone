package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VisorCombatLayoutTest {
    @Test void deathRequiresPositiveEvidenceAndUnloadingIsUnknown() {
        assertNull(VisorCombatLayout.life(false, true, false));
        assertNull(VisorCombatLayout.life(false, false, false));
        assertEquals(Boolean.TRUE, VisorCombatLayout.life(false, false, true));
        assertEquals(Boolean.FALSE, VisorCombatLayout.life(true, true, false));
    }

    @Test void bearingsUseMinecraftWorldAxesAndRealDistance() {
        assertEquals("北 10m", VisorCombatLayout.location(0, 0, -10));
        assertEquals("東 10m", VisorCombatLayout.location(10, 0, 0));
        assertEquals("南 10m", VisorCombatLayout.location(0, 0, 10));
        assertEquals("西 10m", VisorCombatLayout.location(-10, 0, 0));
        assertEquals("北東 14m", VisorCombatLayout.location(10, 0, -10));
        assertEquals("直近 10m", VisorCombatLayout.location(0, 10, 0));
        assertEquals("東 5m", VisorCombatLayout.location(3, 4, 0));
    }

    @Test void panelsRespectCenterAndVanillaHudAtApprovedViewports() {
        for (int[] viewport : new int[][]{{1280, 720}, {1920, 1080}}) {
            int w = viewport[0], h = viewport[1];
            var summary = VisorCombatLayout.summary(w, 100);
            var feed = VisorCombatLayout.feed(w, 100);
            var inspection = VisorCombatLayout.inspection(w, h, 296);
            var center = new VisorCombatLayout.Box(w * 3 / 10, h * 3 / 10, w * 4 / 10, h * 4 / 10);
            assertFalse(summary.intersects(feed));
            assertFalse(feed.intersects(inspection));
            assertFalse(summary.intersects(inspection));
            for (var panel : new VisorCombatLayout.Box[]{summary, feed, inspection}) {
                assertFalse(panel.intersects(center));
                assertTrue(panel.x() >= 0 && panel.x() + panel.width() <= w);
                assertTrue(panel.y() + panel.height() <= h * 81 / 100);
            }
            assertEquals(202, summary.height());
            assertEquals(186, feed.height());
        }
    }
}
