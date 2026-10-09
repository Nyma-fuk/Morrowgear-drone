package jp.morrowgear.drone.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerDisplayTextTest {
    @Test void recoveryBeaconLegacyDimensionMatchesCurrentLevel() {
        assertTrue(ControllerDisplayText.sameDimension(
            "ResourceKey[minecraft:dimension / minecraft:overworld]", "minecraft:overworld"));
        assertTrue(ControllerDisplayText.sameDimension(
            "minecraft:overworld", "ResourceKey[minecraft:dimension / minecraft:overworld]"));
        assertTrue(ControllerDisplayText.sameDimension("overworld", "minecraft:overworld"));
        assertTrue(ControllerDisplayText.sameDimension(
            "ResourceKey[minecraft:dimension / mod:moon]", "mod:moon"));
    }
    @Test void recoveryBeaconDoesNotMatchOtherOrUnknownDimensions() {
        assertFalse(ControllerDisplayText.sameDimension(
            "ResourceKey[minecraft:dimension / minecraft:the_nether]", "minecraft:overworld"));
        assertFalse(ControllerDisplayText.sameDimension("ResourceKey[broken]", "minecraft:overworld"));
        assertFalse(ControllerDisplayText.sameDimension(null, null));
        assertFalse(ControllerDisplayText.sameDimension("", ""));
        assertFalse(ControllerDisplayText.sameDimension("invalid", "invalid"));
    }
    @Test void dimensionNamesSupportCurrentAndLegacyRecords() {
        assertEquals("オーバーワールド", ControllerDisplayText.dimensionName("minecraft:overworld"));
        assertEquals("ネザー", ControllerDisplayText.dimensionName("ResourceKey[minecraft:dimension / minecraft:the_nether]"));
        assertEquals("ジ・エンド", ControllerDisplayText.dimensionName("the_end"));
        assertEquals("minecraft:overworld", ControllerDisplayText.dimensionId("ResourceKey[minecraft:dimension / minecraft:overworld]"));
    }
    @Test void unknownDimensionDoesNotLeakJavaRepresentationOrInventOverworld() {
        assertEquals("次元不明", ControllerDisplayText.dimensionName("ResourceKey[broken]"));
        assertEquals("次元不明", ControllerDisplayText.dimensionName(null));
        assertEquals("mod:moon", ControllerDisplayText.dimensionName("mod:moon"));
        assertTrue(ControllerDisplayText.dimensionName("mod:" + "x".repeat(50)).length() <= 32);
    }
    @Test void prefixIsRemovedButUserAssignedNamesRemain() {
        assertEquals("0A9F", ControllerDisplayText.unitName("MG-DRN-0A9F", "MG-DRN-0A9F"));
        assertEquals("北側の護衛", ControllerDisplayText.unitName("北側の護衛", "MG-DRN-0A9F"));
    }
    @Test void everyActionHasADistinctShortLabel() {
        var actions = java.util.List.of("follow", "return", "dock", "standby", "orbit", "decommission");
        assertEquals(actions.size(), actions.stream().map(ControllerDisplayText::action).distinct().count());
        assertEquals("追従", ControllerDisplayText.action("follow"));
        assertEquals("集合", ControllerDisplayText.action("return"));
        assertTrue(actions.stream().map(ControllerDisplayText::action).allMatch(label -> label.length() <= 6));
    }
}
