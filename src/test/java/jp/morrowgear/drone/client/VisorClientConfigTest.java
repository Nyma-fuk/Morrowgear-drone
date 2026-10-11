package jp.morrowgear.drone.client;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisorClientConfigTest {
    @TempDir Path directory;
    @Test void defaultsPreserveExistingHudExactly() {
        var config = new VisorClientConfig();
		assertTrue(config.airspaceMapEnabled);
        assertEquals(1f, config.hudScale(1920, 1080));
        assertEquals(0xD91A1E1C, config.panelAlpha(0xD91A1E1C));
        assertEquals(0xBD121514, config.panelAlpha(0xBD121514));
        assertEquals(20, config.ordinaryMarkerBudget(20));
        assertEquals(20, config.ordinaryLabelBudget(20));
        assertTrue(config.emergencySummaryEnabled());
        assertEquals(VisorClientConfig.Presentation.DEFAULT, VisorClientConfig.parsePresentation(new Properties()));
    }
    @Test void largeModeRequiresBothViewportDimensions() {
        var large = new VisorClientConfig.Presentation(VisorClientConfig.Density.STANDARD, 100, true);
        assertEquals(1.5f, large.effectiveHudScale(1440, 810));
        assertEquals(1.5f, large.effectiveHudScale(1920, 1080));
        assertEquals(1f, large.effectiveHudScale(1439, 1080));
        assertEquals(1f, large.effectiveHudScale(1920, 809));
        assertEquals(1f, large.effectiveHudScale(1280, 720));
    }
    @Test void densityOnlyOffersOrdinaryBudgetsAndBackgroundOnlyChangesAlpha() {
        var minimal = new VisorClientConfig.Presentation(VisorClientConfig.Density.MINIMAL, 60, false);
        assertEquals(6, minimal.ordinaryMarkerBudget(12));
        assertEquals(4, minimal.ordinaryLabelBudget(12));
        assertEquals(0, minimal.ordinaryMarkerBudget(0));
        assertEquals(0x001A1E1C, minimal.panelAlpha(0xD91A1E1C) & 0x00FFFFFF);
        assertEquals(130, minimal.panelAlpha(0xD91A1E1C) >>> 24);
    }
    @Test void draftDoesNotChangeSharedValuesAndPersistencePreservesExistingSettings() throws Exception {
        var config = new VisorClientConfig();
		config.enabled = false; config.airspaceMapEnabled = false; config.proximityRange = 96;
        var draft = new VisorClientConfig.Presentation(VisorClientConfig.Density.MINIMAL, 80, true);
        assertTrue(config.persist(directory.resolve("visor.properties"), draft));
        assertEquals(VisorClientConfig.Presentation.DEFAULT, config.presentation());
        Properties properties = new Properties();
        try (var input = Files.newInputStream(directory.resolve("visor.properties"))) { properties.load(input); }
        assertEquals(draft, VisorClientConfig.parsePresentation(properties));
        assertEquals("false", properties.getProperty("enabled"));
		assertEquals("false", properties.getProperty("airspaceMapEnabled"));
        assertEquals("96", properties.getProperty("proximityRange"));
    }
    @Test void corruptPreferencesFallBackAndFailedSaveDoesNotChangeConfig() throws Exception {
        Properties values = new Properties();
        values.setProperty("density", "INVALID"); values.setProperty("backgroundPercent", "NaN");
        assertEquals(VisorClientConfig.Presentation.DEFAULT, VisorClientConfig.parsePresentation(values));
        var config = new VisorClientConfig();
        Path notDirectory = directory.resolve("file"); Files.createFile(notDirectory);
        assertFalse(config.persist(notDirectory.resolve("visor.properties"), VisorClientConfig.Presentation.DEFAULT));
        assertEquals(VisorClientConfig.Presentation.DEFAULT, config.presentation());
    }
}
