package jp.morrowgear.drone.client;

import java.nio.file.Files;
import java.nio.file.Path;
import jp.morrowgear.drone.TacticalLayoutPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerPreferencesTest {
    @TempDir Path directory;
    @Test void autoAtDesktopMaximumNeverShrinksWhenPlusIsPressed() {
        var value = new ControllerPreferences();
        assertEquals(2, value.effectivePixelScale(2));
        assertFalse(value.stepPixelScale(1, 2));
        assertEquals(0, value.pixelScale);
        assertEquals(2, value.effectivePixelScale(2));
        assertTrue(value.stepPixelScale(-1, 2));
        assertEquals(1, value.pixelScale);
        assertTrue(value.stepPixelScale(1, 2));
        assertEquals(2, value.pixelScale);
        assertTrue(value.automaticScale(true, 2));
        assertEquals(0, value.pixelScale);
        assertEquals(2, value.effectivePixelScale(2));
    }
    @Test void repeatedChangesAndAutomaticCheckboxReturnToOriginalSetting() throws Exception {
        var value = new ControllerPreferences();
        for (int i = 0; i < 8; i++) {
            assertTrue(value.stepPixelScale(1, 4));
            assertEquals(3, value.pixelScale);
            assertTrue(value.stepPixelScale(-1, 4));
            assertEquals(2, value.pixelScale);
            assertTrue(value.automaticScale(true, 4));
            assertEquals(0, value.pixelScale);
            assertTrue(value.automaticScale(false, 4));
            assertEquals(2, value.pixelScale);
            assertTrue(value.automaticScale(true, 4));
        }
        Path file = directory.resolve("roundtrip.properties");
        assertTrue(value.save(file));
        assertEquals(0, ControllerPreferences.load(file).pixelScale);
    }
    @Test void stepsUseEffectiveClampedScaleAndNeverEnterAuto() {
        var value = new ControllerPreferences();
        value.pixelScale = 4;
        assertFalse(value.stepPixelScale(1, 2));
        assertEquals(4, value.pixelScale);
        assertTrue(value.stepPixelScale(-1, 2));
        assertEquals(1, value.pixelScale);
        assertFalse(value.stepPixelScale(-1, 2));
        assertFalse(value.stepPixelScale(0, 2));
        assertEquals(1, value.pixelScale);
        assertTrue(value.stepPixelScale(1, 2));
        assertEquals(2, value.pixelScale);
    }
    @Test void markerStepsAreMonotonicBoundedAndReversible() {
        var value = new ControllerPreferences();
        int originalRadius = value.markerRadius();
        assertTrue(value.stepMarkerSize(1));
        assertTrue(value.markerRadius() > originalRadius);
        assertFalse(value.stepMarkerSize(1));
        assertTrue(value.stepMarkerSize(-1));
        assertEquals(originalRadius, value.markerRadius());
        assertTrue(value.stepMarkerSize(-1));
        assertTrue(value.markerRadius() < originalRadius);
        assertFalse(value.stepMarkerSize(-1));
        assertTrue(value.stepMarkerSize(1));
        assertEquals(originalRadius, value.markerRadius());
        assertEquals(0, value.pixelScale);
    }
    @Test void absentPreferencesKeepOriginalDefaults() {
        var value = ControllerPreferences.load(directory.resolve("missing.properties"));
        assertEquals(0, value.pixelScale);
        assertEquals(1, value.markerSize);
        assertEquals(3, value.markerRadius());
        assertEquals(10, value.markerHitRadius());
    }
    @Test void settingsPersistIndependentlyOfVisorProperties() throws Exception {
        Path file = directory.resolve("controller.properties");
        var value = new ControllerPreferences();
        value.pixelScale = 3; value.markerSize = 2;
        assertTrue(value.save(file));
        var restored = ControllerPreferences.load(file);
        assertEquals(3, restored.pixelScale);
        assertEquals(2, restored.markerSize);
        assertFalse(Files.readString(file).contains("proximityRange"));
    }
    @Test void malformedOrOutOfBoundsValuesFallBackPerField() throws Exception {
        Path file = directory.resolve("invalid.properties");
        Files.writeString(file, "pixelScale=999\nmarkerSize=0\n");
        var value = ControllerPreferences.load(file);
        assertEquals(0, value.pixelScale); assertEquals(0, value.markerSize);
        Files.writeString(file, "pixelScale=2\nmarkerSize=nan\n");
        value = ControllerPreferences.load(file);
        assertEquals(2, value.pixelScale); assertEquals(1, value.markerSize);
    }
    @Test void failedPersistenceDoesNotPretendToSucceed() {
        assertFalse(new ControllerPreferences().save(directory));
    }
    @Test void markersHaveMonotonicSizeAndEnclosingHitArea() {
        var value = new ControllerPreferences();
        int previous = 0;
        for (int size = 0; size < 3; size++) {
            value.markerSize = size;
            assertTrue(value.markerRadius() > previous);
            assertTrue(value.markerHitRadius() > value.markerRadius() + 3);
            previous = value.markerRadius();
        }
    }
    @Test void requestedScalePreservesIntegralPixelsAndFitsAllSupportedLayouts() {
        int[][] displays = {{800, 450}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
        for (int[] display : displays) for (int gui = 1; gui <= 4; gui++) for (int requested = 0; requested <= 4; requested++) {
            int width = (int)Math.ceil(display[0] / (double)gui);
            int height = (int)Math.ceil(display[1] / (double)gui);
            var m = TacticalLayoutPolicy.calculateForGui(width, height, gui, requested);
            assertTrue(m.structurallyValid());
            assertEquals(Math.rint(m.scale() * gui), m.scale() * gui, .0001);
            assertTrue(m.width() * m.scale() <= width + 1);
            assertTrue(m.height() * m.scale() <= height + 1);
            assertTrue(m.header() + 38 + 5 * 44 + 42 < m.bottomTop());
            for (int i = 0; i < 7; i++) {
                var button = ControllerLayoutPolicy.quickCommand(m.width(), m.bottomTop(), i);
                assertTrue(button.right() <= m.width());
                assertTrue(button.bottom() <= m.height());
            }
        }
        var small = TacticalLayoutPolicy.calculateForGui(1920, 1080, 1, 1);
        var standard = TacticalLayoutPolicy.calculateForGui(1920, 1080, 1, 2);
        assertEquals(1, small.scale());
        assertEquals(2, standard.scale());
    }
    @Test void deliveryNavigationRechecksIdentityFreshnessAndWorld() throws Exception {
        String screen = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/ControllerDeliveryScreen.java"));
        assertTrue(screen.contains("UUID identity = displayed.get(slot).identity()"));
        assertTrue(screen.contains("source.get().rows().stream().noneMatch(row -> row.identity().equals(identity))"));
        assertTrue(screen.contains("button.active = reason.isEmpty()"));
        assertTrue(screen.contains("jump.test(identity)"));
        assertFalse(screen.contains("ClientPlayNetworking"));
        String dashboard = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java"));
        assertTrue(dashboard.contains("client.level == level && deliveryJumpReason(identity, dimension).isEmpty()"));
        assertTrue(dashboard.contains("focusReport(identity, dimension)"));
        assertTrue(dashboard.contains("d.getUUID().equals(identity)"));
        assertTrue(dashboard.contains("preferences.markerHitRadius()"));
        assertTrue(dashboard.contains("preferences.save()"));
    }
}
