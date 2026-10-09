package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DockInteractionContractTest {
    @Test void emptyHandAndNonSupplyItemsReachDefaultInteractionOnBothDockParts() throws Exception {
        for (String name : new String[]{"DockCenterBlock", "DockPartBlock"}) {
            String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/block/" + name + ".java"));
            assertTrue(source.matches("(?s).*if \\(!DockBlockEntity\\.isSupply\\(stack\\)\\) return (?:net\\.minecraft\\.world\\.)?InteractionResult\\.TRY_WITH_EMPTY_HAND;.*"), name);
            assertTrue(source.contains("dock.insertSupply(serverPlayer, stack)"), name);
            assertTrue(source.contains("!dock.isOwnedBy(serverPlayer)"), name);
            assertTrue(source.contains("player.isShiftKeyDown()"), name);
            assertTrue(source.contains("dock.commission(serverPlayer)"), name);
            assertTrue(source.contains("player.openMenu(dock)"), name);
        }
    }
}
