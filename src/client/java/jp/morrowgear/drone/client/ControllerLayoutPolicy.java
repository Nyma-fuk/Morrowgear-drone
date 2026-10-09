package jp.morrowgear.drone.client;

/** Fixed virtual dimensions shared by the native drawing and portable checks. */
final class ControllerLayoutPolicy {
    static final int NAV_WIDTH = 100;
    static final int NAV_COUNT = 11;
    private ControllerLayoutPolicy() {}
    record Box(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(Box other) {
            return other.x >= x && other.y >= y && other.right() <= right() && other.bottom() <= bottom();
        }
    }
    static Box navigation(int height, int header, int position) {
        int step = Math.min(32, (height - header - 84) / NAV_COUNT);
        return new Box(5, header + 6 + position * step, NAV_WIDTH - 10, step - 3);
    }
    static Box routeEditor(Box map) { return new Box(map.x + 8, map.bottom() - 122, map.width - 16, 88); }
    static Box quickCommand(int width, int bottomTop, int index) {
        int start = NAV_WIDTH + 88;
        int buttonWidth = (width - start - 40) / 7;
        return new Box(start + index * (buttonWidth + 5), bottomTop + 9, buttonWidth, 27);
    }
    static Box routeButton(Box map, int index) {
        Box area = routeEditor(map);
        int width = (area.width - 12) / 6;
        return new Box(area.x + 6 + width * index, area.y + 23, width - 3, 20);
    }
    static Box altitudeButton(Box map, int index) {
        Box area = routeEditor(map);
        return new Box(area.right() - 106 + index * 34, area.y + 48, 30, 18);
    }
}
