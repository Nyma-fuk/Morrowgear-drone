package jp.morrowgear.drone.client;

import jp.morrowgear.drone.TacticalLayoutPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerLayoutPolicyTest {
    @Test void navigationAndRouteControlsStayInsideEverySupportedScale() {
        int[][] frames = {{960, 540}, {1280, 720}, {1920, 1080}, {2560, 1440}};
        for (int[] frame : frames) for (int scale = 1; scale <= 4; scale++) {
            var metrics = TacticalLayoutPolicy.calculateForGui(frame[0] / scale, frame[1] / scale, scale);
            assertEquals(Math.rint(metrics.scale() * scale), metrics.scale() * scale, .0001,
                "Desktop glyph pixels remain integral");
            if (frame[0] == 1920) {
                assertEquals(320, metrics.rightWidth() * metrics.scale() * scale, .001);
                assertTrue(metrics.header() * metrics.scale() * scale <= 80.001);
            }
            int previousBottom = metrics.header();
            for (int tab = 0; tab < ControllerLayoutPolicy.NAV_COUNT; tab++) {
                var bounds = ControllerLayoutPolicy.navigation(metrics.height(), metrics.header(), tab);
                assertTrue(bounds.y() >= previousBottom);
                assertTrue(bounds.bottom() < metrics.bottomTop());
                assertTrue(bounds.height() >= 25);
                previousBottom = bounds.bottom();
            }
            var map = new ControllerLayoutPolicy.Box(metrics.leftWidth(), metrics.header(), metrics.mapWidth(), metrics.mapHeight());
            var editor = ControllerLayoutPolicy.routeEditor(map);
            assertTrue(map.contains(editor));
            assertTrue(editor.y() >= map.y() + 60);
            int previousRight = editor.x();
            for (int button = 0; button < 6; button++) {
                var bounds = ControllerLayoutPolicy.routeButton(map, button);
                assertTrue(editor.contains(bounds));
                assertTrue(bounds.x() >= previousRight);
                assertTrue(bounds.width() >= 33, "Three Japanese glyphs plus horizontal padding");
                previousRight = bounds.right();
            }
            var footer = new ControllerLayoutPolicy.Box(0, metrics.bottomTop(), metrics.width(), metrics.height() - metrics.bottomTop());
            for (int button = 0; button < 7; button++) {
                var bounds = ControllerLayoutPolicy.quickCommand(metrics.width(), metrics.bottomTop(), button);
                assertTrue(footer.contains(bounds));
                assertTrue(bounds.width() >= 70);
            }
            for (int button = 0; button < 3; button++) {
                var bounds = ControllerLayoutPolicy.altitudeButton(map, button);
                assertTrue(editor.contains(bounds));
                assertTrue(bounds.y() >= ControllerLayoutPolicy.routeButton(map, 0).bottom() + 4);
                assertTrue(bounds.bottom() <= editor.bottom() - 16);
            }
        }
    }

    public static void main(String[] args) {
        new ControllerLayoutPolicyTest().navigationAndRouteControlsStayInsideEverySupportedScale();
        System.out.println("ControllerLayoutPolicy: 16 viewport/GUI-scale combinations passed (static bounds only)");
    }
}
