package jp.morrowgear.drone;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class DockSupplyPolicy {
	public static final int MAGAZINE_ROUNDS = 120;
	public static final int LASER_CELL_ENERGY = 1000;
	public static final int MISSILE_PACK_ROUNDS = 5;
	public static final int INVENTORY_SIZE = 27;
	public static final int MAX_FLIGHT_CAPACITY = 18000;
	public static final int MAX_FLIGHT_RESERVE = 118000;

	private DockSupplyPolicy() {}

	/** Explicit wire indices and shortage bits must not depend on enum ordering. */
	public enum SupplyKind {
		FUEL(0, 1), GUN(1, 2), LASER(2, 4), MISSILE(3, 8), REPAIR(4, 16);

		private final int index;
		private final int mask;
		SupplyKind(int index, int mask) { this.index = index; this.mask = mask; }
		public int index() { return index; }
		public int mask() { return mask; }
	}

	public static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	public static SupplyKind kind(ItemStack stack) { return kind(itemId(stack)); }

	public static SupplyKind kind(String id) {
		if (id == null) return null;
		return switch (id) {
			case "minecraft:coal", "minecraft:charcoal", "morrowgear_drone:power_cell",
				"morrowgear_drone:standard_battery_pack", "morrowgear_drone:reinforced_battery_pack",
				"morrowgear_drone:high_density_battery_pack" -> SupplyKind.FUEL;
			case "morrowgear_drone:autocannon_magazine" -> SupplyKind.GUN;
			case "morrowgear_drone:laser_cell" -> SupplyKind.LASER;
			case "morrowgear_drone:micro_missile_pack" -> SupplyKind.MISSILE;
			case "minecraft:copper_ingot", "minecraft:iron_ingot", "morrowgear_drone:morrow_alloy" -> SupplyKind.REPAIR;
			default -> null;
		};
	}

	public static boolean isBattery(String id) {
		return "morrowgear_drone:standard_battery_pack".equals(id)
			|| "morrowgear_drone:reinforced_battery_pack".equals(id)
			|| "morrowgear_drone:high_density_battery_pack".equals(id);
	}

	public static boolean mayPlace(int slot, ItemStack stack) { return mayPlace(slot, itemId(stack)); }

	public static boolean mayPlace(int slot, String id) {
		if (slot < 0 || slot >= INVENTORY_SIZE || id == null || id.isEmpty()) return false;
		if (slot >= 18) return kind(id) != null;
		return switch (slot) {
			case 0 -> id.equals("morrowgear_drone:field_drone_unit");
			case 1, 8 -> isBattery(id);
			case 2 -> switch (id) {
				case "morrowgear_drone:scout_module", "morrowgear_drone:cargo_module",
					"morrowgear_drone:engineer_module", "morrowgear_drone:security_module",
					"morrowgear_drone:salvage_module" -> true;
				default -> false;
			};
			case 3 -> switch (id) {
				case "morrowgear_drone:autocannon_module", "morrowgear_drone:laser_module",
					"morrowgear_drone:missile_module" -> true;
				default -> false;
			};
			case 4 -> kind(id) == SupplyKind.FUEL;
			case 5 -> kind(id) == SupplyKind.REPAIR;
			case 6 -> kind(id) == SupplyKind.GUN || kind(id) == SupplyKind.LASER || kind(id) == SupplyKind.MISSILE;
			default -> false;
		};
	}

	public static int slotLimit(int slot) {
		return slot >= 0 && slot <= 3 || slot == 8 ? 1 : 64;
	}

	public static int inputSlot(SupplyKind kind) {
		return switch (kind) {
			case FUEL -> 4;
			case REPAIR -> 5;
			case GUN, LASER, MISSILE -> 6;
		};
	}

	public static boolean isServiceSlot(int slot, SupplyKind kind) {
		return kind != null && (slot == inputSlot(kind) || slot >= 18 && slot <= 26);
	}

	/** At most one pack is opened per service call. Credits are drained before opening another. */
	public static PackTransfer takePack(int credit, int requested, int unitsPerPack, boolean packAvailable) {
		if (unitsPerPack <= 0 || credit < 0 || credit >= unitsPerPack)
			throw new IllegalArgumentException("Invalid pack credit");
		if (requested <= 0) return new PackTransfer(0, credit, false);
		boolean consume = credit == 0 && packAvailable;
		int available = consume ? unitsPerPack : credit;
		int supplied = Math.min(requested, available);
		return new PackTransfer(supplied, available - supplied, consume);
	}

	public record PackTransfer(int supplied, int credit, boolean consumeItem) {}

	public record PackCredits(int weapon, int gun, int missile) {
		public PackCredits {
			weapon = Math.clamp(weapon, 0, LASER_CELL_ENERGY - 1);
			gun = Math.clamp(gun, 0, MAGAZINE_ROUNDS - 1);
			missile = Math.clamp(missile, 0, MISSILE_PACK_ROUNDS - 1);
		}

		public void write(ValueOutput output) {
			output.putInt("WeaponCredit", weapon);
			output.putInt("AutocannonCredit", gun);
			output.putInt("MissileCredit", missile);
		}

		public static PackCredits read(ValueInput input) {
			return new PackCredits(input.getIntOr("WeaponCredit", 0), input.getIntOr("AutocannonCredit", 0),
				input.getIntOr("MissileCredit", 0));
		}
	}

	/** Capacity removal moves energy into the saved reserve; it never deletes valid energy. */
	public static FlightReserve normalizeFlight(int stored, int credit, int capacity) {
		int cap = Math.clamp(capacity, 0, MAX_FLIGHT_CAPACITY);
		int boundedStored = Math.clamp(stored, 0, MAX_FLIGHT_CAPACITY);
		long total = Math.min(MAX_FLIGHT_RESERVE, (long) boundedStored + Math.max(0, credit));
		int tank = Math.min(boundedStored, cap);
		return new FlightReserve(tank, (int) total - tank);
	}

	public record FlightReserve(int stored, int credit) {}

	public static boolean completionResourceExhausted(boolean flightRequired, boolean flightAvailable,
		boolean weaponRequired, boolean weaponAvailable, boolean ammunitionRequired, boolean ammunitionAvailable) {
		return flightRequired && !flightAvailable || weaponRequired && !weaponAvailable
			|| ammunitionRequired && !ammunitionAvailable;
	}
}
