package jp.morrowgear.drone.carrier;

import java.util.ArrayList;
import java.util.List;
import jp.morrowgear.drone.DockSupplyPolicy;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Dedicated supply inputs, separate from the paged cargo hold. */
public final class CarrierSupplies extends SimpleContainer {
    public static final int FLIGHT = 0, WEAPON = 1, GUN = 2, MISSILES = 3, REPAIR = 4, SIZE = 5;
    public static final int CELL_ENERGY = DockSupplyPolicy.LASER_CELL_ENERGY;
    private Runnable dirty;

    public CarrierSupplies(List<ItemStack> saved, Runnable dirty) {
        super(SIZE);
        for (int i = 0; i < Math.min(saved.size(), SIZE); i++) setItem(i, saved.get(i).copy());
        this.dirty = dirty;
    }

    @Override public void setChanged() { if (dirty != null) dirty.run(); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return accepts(slot, DockSupplyPolicy.itemId(stack)); }

    public static boolean accepts(int slot, String id) {
        if (id == null) return false;
        return switch (slot) {
            case FLIGHT -> id.equals("morrowgear_drone:power_cell");
            case WEAPON -> id.equals("morrowgear_drone:laser_cell");
            case GUN -> id.equals("morrowgear_drone:autocannon_magazine");
            case MISSILES -> id.equals("morrowgear_drone:micro_missile_pack");
            case REPAIR -> DockSupplyPolicy.kind(id) == DockSupplyPolicy.SupplyKind.REPAIR;
            default -> false;
        };
    }

    public List<ItemStack> snapshot() {
        List<ItemStack> result = new ArrayList<>(SIZE);
        for (int slot = 0; slot < SIZE; slot++) result.add(getItem(slot).copy());
        return result;
    }

    /** Legacy cargo supplies remain usable; a typed input always takes precedence. */
    static Source find(CarrierShip ship, int kind) {
        if (accepts(kind, DockSupplyPolicy.itemId(ship.supplies.getItem(kind)))) return new Source(ship.supplies, kind);
        int slot = ship.cargo.findFirst(stack -> accepts(kind, DockSupplyPolicy.itemId(stack)));
        return slot < 0 ? null : new Source(ship.cargo, slot);
    }

    static boolean charge(CarrierShip ship, boolean weapon) {
        int energy = weapon ? ship.weaponEnergy : ship.energy;
        if (!canCharge(energy)) return false;
        Source source = find(ship, weapon ? WEAPON : FLIGHT);
        if (source == null) return false;
        source.consume();
        if (weapon) ship.weaponEnergy += CELL_ENERGY; else ship.energy += CELL_ENERGY;
        ship.dirty.run();
        return true;
    }

    public static boolean canCharge(int energy) { return energy >= 0 && energy <= CarrierPolicy.MAX_ENERGY - CELL_ENERGY; }

    /** Called only by an attended exterior, at most one cell per tank per second. */
    static void tick(CarrierShip ship, long tick) {
        if (ship.destroyed) return;
        CarrierPowerPolicy.regenerate(ship);
        if (tick % 20 != 0) return;
        charge(ship, false);
        charge(ship, true);
    }

    record Source(Container container, int slot) {
        ItemStack one() { return container.getItem(slot).copyWithCount(1); }
        void consume() { container.removeItem(slot, 1); }
    }
}
