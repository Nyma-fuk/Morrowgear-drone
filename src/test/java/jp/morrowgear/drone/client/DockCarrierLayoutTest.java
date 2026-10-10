package jp.morrowgear.drone.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DockCarrierLayoutTest {
    @Test void dockNativeInventoryNeverCrossesResourcePaneAtCommonGuiScales() {
        for (int width : new int[]{320, 426, 480, 520, 600, 640, 960, 1280, 1920}) {
            int inventory = DockCarrierLayout.dockInventoryX(width);
            int details = DockCarrierLayout.dockDetailsX(width);
            assertTrue(inventory >= 0);
            assertTrue(inventory + 176 <= details - 6, "width=" + width);
            assertTrue(width - details - 6 >= 132);
            for (int height : new int[]{180, 240, 270, 360, 720, 1080}) {
                int y = DockCarrierLayout.dockInventoryY(height);
                assertTrue(y >= 0 && y + 166 <= height);
            }
        }
    }

    @Test void fourSupplyAndHandlingTabsFitWithoutUsingHiddenInventoryColumn() {
        for (int width : new int[]{320, 426, 640, 960, 1280, 1920}) {
            int x = 6, tab = DockCarrierLayout.supplyTabWidth(width, x, 4);
            assertTrue(tab >= 72);
            assertTrue(x + 4 * tab + 12 <= width - 6);
        }
    }
}
