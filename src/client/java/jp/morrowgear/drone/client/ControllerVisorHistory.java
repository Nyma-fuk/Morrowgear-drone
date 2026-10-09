package jp.morrowgear.drone.client;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Bound by the client initializer to the existing HUD instance, not a new empty ledger. */
public final class ControllerVisorHistory {
    private static Consumer<Minecraft> opener;
    private ControllerVisorHistory() {}
    public static void bind(Consumer<Minecraft> open) { opener = java.util.Objects.requireNonNull(open); }
    static boolean available() { return opener != null; }
    static void open(Minecraft client) { if (opener != null) opener.accept(client); }
}
