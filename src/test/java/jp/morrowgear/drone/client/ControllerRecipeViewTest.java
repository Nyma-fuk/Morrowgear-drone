package jp.morrowgear.drone.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerRecipeViewTest {
    @Test void receivedEmptyAndUnresolvedSlotsRemainDistinct() {
        var blank = ControllerRecipeView.Slot.blank();
        var unresolved = new ControllerRecipeView.Slot(false, List.of());
        assertTrue(blank.empty());
        assertFalse(blank.unresolved());
        assertFalse(unresolved.empty());
        assertTrue(unresolved.unresolved());
    }
    @Test void controllerRecipeKeepsActualThreeByThreePositionsAndEmptyBottomCorners() throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of(
            "src/main/resources/data/morrowgear_drone/recipe/controller.json"))).getAsJsonObject();
        var pattern = json.getAsJsonArray("pattern");
        var keys = json.getAsJsonObject("key");
        List<String> cells = new ArrayList<>();
        for (int row = 0; row < 3; row++) for (int column = 0; column < 3; column++) {
            int index = ControllerRecipeLayout.shapedIndex(column, row, 3, 3);
            char symbol = pattern.get(index / 3).getAsString().charAt(index % 3);
            cells.add(symbol == ' ' ? "" : keys.get(Character.toString(symbol)).getAsString());
        }
        assertEquals(List.of("minecraft:glass_pane", "minecraft:copper_ingot", "minecraft:glass_pane",
            "minecraft:redstone", "morrowgear_drone:basic_control_board", "minecraft:redstone",
            "", "minecraft:copper_ingot", ""), cells);
        assertEquals(1, json.getAsJsonObject("result").get("count").getAsInt());
    }
    @Test void smallerShapedRecipesPadOnlyOutsideTheirReceivedDimensions() {
        assertEquals(0, ControllerRecipeLayout.shapedIndex(0, 0, 2, 2));
        assertEquals(3, ControllerRecipeLayout.shapedIndex(1, 1, 2, 2));
        assertEquals(-1, ControllerRecipeLayout.shapedIndex(2, 0, 2, 2));
        assertEquals(-1, ControllerRecipeLayout.shapedIndex(0, 2, 2, 2));
        assertEquals(-1, ControllerRecipeLayout.shapedIndex(-1, 0, 2, 2));
    }
    @Test void candidateCyclingWrapsWithoutTouchingEmptyOrSingleSlots() {
        assertEquals(0, ControllerRecipeLayout.nextCandidate(0, 0));
        assertEquals(0, ControllerRecipeLayout.nextCandidate(0, 1));
        int index = 0;
        for (int i = 0; i < 12; i++) {
            index = ControllerRecipeLayout.nextCandidate(index, 3);
            assertEquals((i + 1) % 3, index);
        }
    }
    @Test void gridOutputAndStationFitEverySupportedNativeViewport() {
        for (int[] screen : new int[][] {{320, 180}, {426, 240}, {640, 360}, {960, 540}, {1920, 1080}}) {
            var layout = ControllerRecipeLayout.layout(screen[0], screen[1]);
            assertTrue(layout.left() >= 12);
            assertTrue(layout.right() <= screen[0] - 12);
            assertTrue(layout.bottom() < screen[1] - 44);
            assertTrue(layout.outputX() > layout.left() + layout.cell() * 3);
            assertTrue(layout.stationX() > layout.outputX() + layout.cell());
            assertTrue(layout.cell() >= 22);
        }
    }
    @Test void screenUsesRealItemsCountsAndPreservesChoicesByRecipeIdentity() throws Exception {
        String screen = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/ControllerRecipeScreen.java"));
        assertTrue(screen.contains("g.item(stack, 0, 0)"));
        assertTrue(screen.contains("g.itemDecorations(font, stack, 0, 0)"));
        assertTrue(screen.contains("output.getCount()"));
        assertTrue(screen.contains("candidates.computeIfAbsent(recipe.identity()"));
        assertTrue(screen.contains("ControllerRecipeLayout.nextCandidate("));
        assertTrue(screen.contains("minecraft.setScreenAndShow(parent)"));
        assertFalse(screen.contains("ClientPlayNetworking"));
        String data = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/ControllerEquipmentData.java"));
        assertTrue(data.contains("getRecipeBook().getCollections()"));
        assertTrue(data.contains("display instanceof SlotDisplay.Empty"));
        assertTrue(data.contains("display.resolveForStacks(context)"));
        assertFalse(data.contains("Files.read"));
        String dashboard = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java"));
        assertTrue(dashboard.contains("new ControllerRecipeScreen(client.gui.screen(), recipes)"));
    }
}
