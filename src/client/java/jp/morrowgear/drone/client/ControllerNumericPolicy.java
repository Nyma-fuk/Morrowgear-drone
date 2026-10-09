package jp.morrowgear.drone.client;

import java.util.OptionalInt;

final class ControllerNumericPolicy {
    private ControllerNumericPolicy() {}
    static int formWidth(int screenWidth, double guiScale) {
        return Math.max(160, Math.min(screenWidth - 24, (int)(720 / Math.max(1, guiScale))));
    }
    static boolean withinRouteRange(int x, int z, int playerX, int playerZ) {
        return Math.abs((long)x - playerX) <= 512 && Math.abs((long)z - playerZ) <= 512;
    }
    static OptionalInt parse(String value, int minimum, int maximum) {
        try {
            int number = Integer.parseInt(value.strip());
            return number < minimum || number > maximum ? OptionalInt.empty() : OptionalInt.of(number);
        } catch (NumberFormatException | NullPointerException ignored) {
            return OptionalInt.empty();
        }
    }
    static int movedStart(int start, int first, int second) {
        return start == first ? second : start == second ? first : start;
    }
    static int removedStart(int start, int removed) {
        return start == removed ? -1 : start > removed ? start - 1 : start;
    }
}
