package jp.morrowgear.drone;

import java.util.HashSet;
import java.util.Set;

/** A newly opened or rebuilt control surface requires an explicit choice, not a held activation key. */
public final class CarrierUiInputPolicy {
    private boolean chosen;
    private final Set<Integer> held = new HashSet<>();
    public void rebuilt() { chosen = false; }
    public void choose() { chosen = true; }
    public static boolean activation(int key) { return key == 257 || key == 335 || key == 32; }
    public boolean press(int key, boolean focused) {
        if (!activation(key)) return true;
        return held.add(key) && chosen && focused;
    }
    public void release(int key) { held.remove(key); }
    public static boolean controlClick(boolean onControl, boolean doubleClick) { return !onControl || !doubleClick; }
}
