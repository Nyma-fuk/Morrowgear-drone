package jp.morrowgear.drone.client;

/** Stable native-GUI geometry and recipe-index mapping, independent of item registries. */
final class ControllerRecipeLayout {
    private ControllerRecipeLayout() {}
    record Layout(int left, int top, int cell) {
        int outputX() { return left + cell * 3 + 24; }
        int stationX() { return left + cell * 4 + 48; }
        int right() { return stationX() + cell; }
        int bottom() { return top + cell * 3; }
    }
    static Layout layout(int width, int height) {
        int cell = Math.clamp((height - 112) / 3, 22, 40);
        return new Layout((width - (cell * 5 + 48)) / 2, 54, cell);
    }
    static int shapedIndex(int column, int row, int width, int height) {
        return column >= 0 && row >= 0 && column < width && row < height ? row * width + column : -1;
    }
    static int nextCandidate(int current, int count) { return count < 1 ? 0 : Math.floorMod(current + 1, count); }
}
