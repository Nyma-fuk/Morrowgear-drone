package jp.morrowgear.drone;

import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Server container and synchronization contract only; no screen or visual assets. */
public final class DockMenu extends AbstractContainerMenu {
	public static final MenuType<DockMenu> TYPE = Registry.register(BuiltInRegistries.MENU,
		Identifier.fromNamespaceAndPath("morrowgear_drone", "dock"),
		new MenuType<>(DockMenu::new, FeatureFlags.VANILLA_SET));

	public static final int FLIGHT_POWER = 0, FLIGHT_CAPACITY = 1, FUEL_CREDIT = 2;
	public static final int WEAPON_POWER = 3, WEAPON_CAPACITY = 4, GUN_CREDIT = 5, MISSILE_CREDIT = 6;
	public static final int STOCK_START = 7, AVAILABLE_MASK = 12, SHORTAGE_MASK = 13;
	public static final int DRONE_ID = 14, READINESS = 15, DRONE_FLIGHT_PERCENT = 16;
	public static final int DRONE_WEAPON_PERCENT = 17, DRONE_GUN_ROUNDS = 18, DRONE_MISSILES = 19;
	public static final int DRONE_WEAPON_CAPACITY = 20, DRONE_GUN_CAPACITY = 21, DRONE_MISSILE_CAPACITY = 22;
	public static final int VALUE_COUNT = 23, DATA_COUNT = VALUE_COUNT * 2;
	public static final int EMPTY = 0, SERVICING = 1, READY = 2, BLOCKED = 3;
	public static final int PLAYER_START = 27, HOTBAR_START = 54, PLAYER_END = 63;
	private final Container container;
	private final ContainerData data;

	/** Call during common initialization, before registries freeze. */
	public static void register() {}

	/** Client factory: never reads a block entity or the client world. */
	public DockMenu(int syncId, Inventory inventory) {
		this(syncId, inventory, new SimpleContainer(DockSupplyPolicy.INVENTORY_SIZE), new SimpleContainerData(DATA_COUNT));
	}

	public DockMenu(int syncId, Inventory inventory, DockBlockEntity dock) {
		this(syncId, inventory, dock, new SimpleContainerData(DATA_COUNT));
	}

	public DockMenu(int syncId, Inventory inventory, Container container, ContainerData data) {
		super(TYPE, syncId);
		checkContainerSize(container, DockSupplyPolicy.INVENTORY_SIZE);
		checkContainerDataCount(data, DATA_COUNT);
		this.container = container;
		this.data = data;
		for (int slot = 0; slot < DockSupplyPolicy.INVENTORY_SIZE; slot++) {
			final int index = slot;
			addSlot(new Slot(container, slot, 8 + slot % 9 * 18, 18 + slot / 9 * 18) {
				@Override public boolean mayPlace(ItemStack stack) {
					return DockSupplyPolicy.mayPlace(index, stack) && container.canPlaceItem(index, stack);
				}
				@Override public int getMaxStackSize() {
					return Math.min(super.getMaxStackSize(), DockSupplyPolicy.slotLimit(index));
				}
				@Override public boolean mayPickup(Player player) { return DockMenu.this.stillValid(player); }
			});
		}
		addStandardInventorySlots(inventory, 8, 85);
		addDataSlots(data);
		refreshServerData();
	}

	@Override public boolean stillValid(Player player) { return container.stillValid(player); }

	@Override
	public void clicked(int slot, int button, ContainerInput input, Player player) {
		if (!stillValid(player)) { resetQuickCraft(); return; }
		if (slot >= slots.size() || slot < -1 && slot != SLOT_CLICKED_OUTSIDE) return;
		super.clicked(slot, button, input, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
		Slot source = slots.get(index);
		if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
		ItemStack stack = source.getItem();
		ItemStack original = stack.copy();
		if (index < PLAYER_START) {
			if (!moveItemStackTo(stack, PLAYER_START, PLAYER_END, true)) return ItemStack.EMPTY;
		} else {
			String id = DockSupplyPolicy.itemId(stack);
			boolean eligible = false;
			// Commission equipment first, then the capacitor, service inputs, and common supply buffers.
			int[] destinations = {0, 1, 2, 3, 8, 4, 5, 6};
			for (int target : destinations) {
				if (!DockSupplyPolicy.mayPlace(target, id)) continue;
				eligible = true;
				if (!slots.get(target).mayPlace(stack)) continue;
				moveItemStackTo(stack, target, target + 1, false);
				if (stack.isEmpty()) break;
			}
			if (DockSupplyPolicy.kind(id) != null) {
				eligible = true;
				for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
					for (int target = 18; target < PLAYER_START && !stack.isEmpty(); target++) {
						Slot destination = slots.get(target);
						if ((pass == 0) != destination.hasItem() || !destination.mayPlace(stack)) continue;
						moveItemStackTo(stack, target, target + 1, false);
					}
				}
			}
			if (!eligible) {
				if (index < HOTBAR_START) moveItemStackTo(stack, HOTBAR_START, PLAYER_END, false);
				else moveItemStackTo(stack, PLAYER_START, HOTBAR_START, false);
			}
		}
		if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
		if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY);
		else source.setChanged();
		source.onTake(player, stack);
		return original;
	}

	@Override public void broadcastChanges() { refreshServerData(); super.broadcastChanges(); }
	@Override public void broadcastFullState() { refreshServerData(); super.broadcastFullState(); }

	/** Values use two 16-bit words because vanilla container data packets truncate to a short. */
	public int value(int field) {
		if (field < 0 || field >= VALUE_COUNT) throw new IndexOutOfBoundsException(field);
		return (data.get(field * 2) & 0xffff) | (data.get(field * 2 + 1) & 0xffff) << 16;
	}

	public int supplyCount(SupplyKind kind) { return value(STOCK_START + kind.index()); }
	public boolean available(SupplyKind kind) { return (value(AVAILABLE_MASK) & kind.mask()) != 0; }
	public boolean shortage(SupplyKind kind) { return (value(SHORTAGE_MASK) & kind.mask()) != 0; }

	private void setValue(int field, int value) {
		data.set(field * 2, value & 0xffff);
		data.set(field * 2 + 1, value >>> 16);
	}

	private void refreshServerData() {
		if (!(container instanceof DockBlockEntity dock)) return;
		setValue(FLIGHT_POWER, dock.storedFlightPower());
		setValue(FLIGHT_CAPACITY, dock.powerCapacity());
		setValue(FUEL_CREDIT, dock.flightFuelCredit());
		setValue(WEAPON_POWER, dock.storedWeaponPower());
		setValue(WEAPON_CAPACITY, dock.weaponPowerCapacity());
		setValue(GUN_CREDIT, dock.autocannonCredit());
		setValue(MISSILE_CREDIT, dock.missileCredit());
		int available = 0;
		for (SupplyKind kind : SupplyKind.values()) {
			setValue(STOCK_START + kind.index(), dock.supplyCount(kind));
			boolean present = switch (kind) {
				case FUEL -> dock.hasFlightPowerSupply();
				case GUN -> dock.hasAutocannonAmmunition();
				case LASER -> dock.hasWeaponPowerSupply();
				case MISSILE -> dock.hasMissileAmmunition();
				case REPAIR -> dock.hasRepairMaterial();
			};
			if (present) available |= kind.mask();
		}
		setValue(AVAILABLE_MASK, available);
		DroneEntity drone = dock.dockedDrone();
		setValue(DRONE_ID, drone == null ? -1 : drone.getId());
		setValue(DRONE_FLIGHT_PERCENT, drone == null ? 0 : drone.batteryPercent());
		setValue(DRONE_WEAPON_PERCENT, drone == null ? 0 : drone.weaponPowerPercent());
		setValue(DRONE_GUN_ROUNDS, drone == null ? 0 : drone.gunAmmo());
		setValue(DRONE_MISSILES, drone == null ? 0 : drone.missiles());
		setValue(DRONE_WEAPON_CAPACITY, drone == null ? PayloadCapacity.energy(0) : drone.weaponCapacity());
		setValue(DRONE_GUN_CAPACITY, drone == null ? PayloadCapacity.gun(0) : drone.gunCapacity());
		setValue(DRONE_MISSILE_CAPACITY, drone == null ? PayloadCapacity.missiles(0) : drone.missileCapacity());
		if (drone == null) {
			setValue(READINESS, EMPTY);
			setValue(SHORTAGE_MASK, 0);
			return;
		}
		boolean security = drone.role() == DroneRole.SECURITY;
		boolean ready = security ? CombatPolicy.sortieReady(drone.securityLoadout(),
			drone.getHealth() / drone.getMaxHealth(), drone.batteryPercent(), drone.weaponPowerPercent(),
			drone.gunAmmo(), drone.missiles(), drone.laserHeat(), false)
			: DroneServicePolicy.nonCombatSortieReady(drone.getHealth() / drone.getMaxHealth(), drone.batteryPercent());
		int needed = 0;
		if (drone.batteryPercent() < DroneServicePolicy.NORMAL_SORTIE_POWER) needed |= SupplyKind.FUEL.mask();
		if (security && drone.weaponPowerPercent() < DroneServicePolicy.NORMAL_SORTIE_POWER) needed |= SupplyKind.LASER.mask();
		if (security) {
			SecurityLoadout loadout = drone.securityLoadout();
			if ((loadout == SecurityLoadout.AUTO || loadout == SecurityLoadout.AUTOCANNON)
				&& !CombatPolicy.normalPayloadReady(SecurityLoadout.AUTOCANNON, drone.gunAmmo(), 0, 0))
				needed |= SupplyKind.GUN.mask();
			if ((loadout == SecurityLoadout.AUTO || loadout == SecurityLoadout.MISSILE)
				&& !CombatPolicy.normalPayloadReady(SecurityLoadout.MISSILE, 0, drone.missiles(), 0))
				needed |= SupplyKind.MISSILE.mask();
		}
		if (drone.getHealth() < drone.getMaxHealth() || drone.lowestSubsystemCondition() < DroneSubsystemPolicy.MAX)
			needed |= SupplyKind.REPAIR.mask();
		int shortage = needed & ~available;
		setValue(SHORTAGE_MASK, shortage);
		setValue(READINESS, ready ? READY : shortage != 0 ? BLOCKED : SERVICING);
	}
}
