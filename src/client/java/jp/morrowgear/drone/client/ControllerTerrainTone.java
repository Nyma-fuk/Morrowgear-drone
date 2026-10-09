package jp.morrowgear.drone.client;

/** Display-only equivalent of the approved charcoal overlay on an opaque terrain pixel. */
final class ControllerTerrainTone {
    private ControllerTerrainTone() {}
    static int darken(int terrain) {
        int red = ((terrain >> 16 & 255) * 102 + 18 * 153 + 127) / 255;
        int green = ((terrain >> 8 & 255) * 102 + 21 * 153 + 127) / 255;
        int blue = ((terrain & 255) * 102 + 20 * 153 + 127) / 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}
