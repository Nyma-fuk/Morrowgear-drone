package jp.morrowgear.drone.carrier;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Stable contract shared by carrier storage, menus, rendering and future modules. */
public final class CarrierExpansionPolicy {
    public static final int HARDPOINTS = 8;
    public static final int CARGO_SLOTS = CarrierPolicy.STORAGE_SLOTS_PER_MODULE;

    private CarrierExpansionPolicy() {}

    public enum Type {
        CARGO("carrier_cargo_module"),
        MISSILE_BATTERY("carrier_missile_module");

        private final String itemPath;
        Type(String itemPath) { this.itemPath = itemPath; }
        public String itemPath() { return itemPath; }
        public static Type byName(String value) {
            if (value == null) return CARGO;
            try { return valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return CARGO; }
        }
    }

    public enum Location { ITEM, CARRIER, PLACED, LIFTED, DESTROYED }

    public record Module(UUID id, UUID owner, Type type, Location location, String dimension,
                         long position, UUID carrier, int hardpoint, UUID liftMission) {
        public Module {
            if (id == null || owner == null || type == null || location == null)
                throw new IllegalArgumentException("Incomplete carrier expansion identity");
            dimension = dimension == null ? "" : dimension;
            hardpoint = location == Location.CARRIER ? checkedHardpoint(hardpoint) : -1;
            carrier = location == Location.CARRIER ? java.util.Objects.requireNonNull(carrier) : null;
            liftMission = location == Location.LIFTED ? java.util.Objects.requireNonNull(liftMission) : null;
        }

        public Module moveToItem() {
            return new Module(id, owner, type, Location.ITEM, "", Long.MIN_VALUE, null, -1, null);
        }
        public Module install(UUID ship, int slot) {
            return new Module(id, owner, type, Location.CARRIER, "", Long.MIN_VALUE, ship, slot, null);
        }
        public Module place(String dimension, long position) {
            return new Module(id, owner, type, Location.PLACED, dimension, position, null, -1, null);
        }
        public Module lift(UUID mission, String dimension, long position) {
            return new Module(id, owner, type, Location.LIFTED, dimension, position, null, -1, mission);
        }
        public Module destroy() {
            return new Module(id, owner, type, Location.DESTROYED, "", Long.MIN_VALUE, null, -1, null);
        }
    }

    public static int checkedHardpoint(int hardpoint) {
        if (hardpoint < 0 || hardpoint >= HARDPOINTS) throw new IllegalArgumentException("Invalid hardpoint " + hardpoint);
        return hardpoint;
    }

    public static int cargoModuleCount(List<Module> modules) {
        return (int) modules.stream().filter(module -> module.type() == Type.CARGO
            && module.location() == Location.CARRIER).count();
    }

    public static int cargoCapacity(List<Module> modules) {
        return CarrierPolicy.BASE_STORAGE_SLOTS + cargoModuleCount(modules) * CARGO_SLOTS;
    }
}
