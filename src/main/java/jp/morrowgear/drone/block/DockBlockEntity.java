package jp.morrowgear.drone.block;

import java.util.UUID;

import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class DockBlockEntity extends BaseContainerBlockEntity {
	public static final int SLOT_DRONE = 0;
	public static final int SLOT_BATTERY = 1;
	public static final int SLOT_ROLE_MODULE = 2;
	public static final int SLOT_WEAPON_MODULE = 3;
	public static final int SLOT_POWER_INPUT = 4;
	public static final int SLOT_REPAIR = 5;
	public static final int SLOT_AMMUNITION = 6;
	public static final int SLOT_RECOVERY_OUTPUT = 7;
	public static final int SLOT_RESERVED = 8;
	public static final int RECOVERY_BUFFER_START = 9;
	public static final int RECOVERY_BUFFER_END = 17;
	public static final int SUPPLY_BUFFER_START = 18;
	public static final int SUPPLY_BUFFER_END = 26;
	private static final int INVENTORY_SIZE = 27;
	private static final int COMPATIBILITY_RESERVE = 2000;
	private NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
	private int storedPower = COMPATIBILITY_RESERVE;
	private UUID owner = new UUID(0, 0);
	private String ownerName = "";
	private Direction facing = Direction.NORTH;

	public DockBlockEntity(BlockPos pos, BlockState state) {
		super(MorrowgearDrone.DOCK_BLOCK_ENTITY, pos, state);
	}

	public void initialize(ServerPlayer player) {
		this.owner = player.getUUID();
		this.ownerName = player.getScoreboardName();
		this.facing = player.getDirection();
		setChanged();
		if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
	}

	public boolean isOwnedBy(UUID playerId) {
		return owner.equals(playerId);
	}

	public boolean matchesOwner(UUID playerId, String playerName) {
		return isOwnedBy(playerId) || (!ownerName.isBlank() && ownerName.equalsIgnoreCase(playerName));
	}

	public boolean isOwnedBy(ServerPlayer player) {
		if (isOwnedBy(player.getUUID())) return true;
		boolean sameNamedOwner = !ownerName.isBlank() && ownerName.equalsIgnoreCase(player.getScoreboardName());
		boolean legacySingleplayer = player.level().getServer().isSingleplayer()
			&& player.level().getServer().getPlayerList().getPlayers().size() == 1;
		if (!sameNamedOwner && !legacySingleplayer) return false;
		initialize(player);
		return true;
	}

	public void adoptOwner(ServerPlayer player) {
		isOwnedBy(player);
	}

	public Direction facing() {
		return facing;
	}

	public int storedPower() {
		return storedPower;
	}

	public void setStoredPowerForVerification(int power) {
		storedPower = Math.max(0, Math.min(powerCapacity(), power));
		setChanged();
	}

	public int powerCapacity() {
		ItemStack capacitor = items.get(SLOT_RESERVED);
		if (capacitor.is(MorrowgearDrone.HIGH_DENSITY_BATTERY_PACK)) return 18000;
		if (capacitor.is(MorrowgearDrone.REINFORCED_BATTERY_PACK)) return 10000;
		if (capacitor.is(MorrowgearDrone.STANDARD_BATTERY_PACK)) return 6000;
		return 4000;
	}

	public boolean provideCharge(int amount) {
		if (amount <= 0) return true;
		refillPowerFromInput(amount);
		if (storedPower < amount) return false;
		storedPower -= amount;
		setChanged();
		return true;
	}

	public int provideWeaponCharge(int requested) {
		int amount = Math.max(0, requested);
		if (amount == 0) return 0;
		refillPowerFromInput(2);
		int supplied = Math.min(amount, storedPower / 2);
		if (supplied <= 0) return 0;
		storedPower -= supplied * 2;
		setChanged();
		return supplied;
	}

	public float provideRepair(float missingHealth) {
		if (missingHealth <= 0.0f) return 0.0f;
		ItemStack material = serviceStack(SLOT_REPAIR, DockBlockEntity::isRepairMaterial);
		float repair = repairValue(material);
		if (repair <= 0.0f) return 0.0f;
		material.shrink(1);
		setChanged();
		return Math.min(missingHealth, repair);
	}

	public int provideSubsystemRepair(int missingCondition) {
		if (missingCondition <= 0) return 0;
		ItemStack material = serviceStack(SLOT_REPAIR, DockBlockEntity::isRepairMaterial);
		float value = repairValue(material);
		if (value <= 0.0f) return 0;
		material.shrink(1);
		setChanged();
		return Math.min(missingCondition, Math.round(value * 20.0f));
	}

	public int provideAutocannonRounds(int missingRounds) {
		return consumeAmmunition(Items.IRON_NUGGET, missingRounds, 24);
	}

	public int provideMissiles(int missingMissiles) {
		return consumeAmmunition(Items.FIREWORK_ROCKET, missingMissiles, 1);
	}

	public boolean hasRepairMaterial() {
		return !serviceStack(SLOT_REPAIR, DockBlockEntity::isRepairMaterial).isEmpty();
	}

	public boolean hasPowerSupply() {
		// One residual energy unit cannot satisfy either the two-unit weapon conversion
		// or the five-unit flight charging quantum. Treat it as exhausted so a docked
		// aircraft can make a resource-limited sortie decision instead of waiting forever.
		return storedPower >= 2 || !serviceStack(SLOT_POWER_INPUT, stack -> dockEnergy(stack) > 0).isEmpty();
	}

	public boolean hasAutocannonAmmunition() {
		return !serviceStack(SLOT_AMMUNITION, stack -> stack.is(Items.IRON_NUGGET)).isEmpty();
	}

	public boolean hasMissileAmmunition() {
		return !serviceStack(SLOT_AMMUNITION, stack -> stack.is(Items.FIREWORK_ROCKET)).isEmpty();
	}

	private int consumeAmmunition(net.minecraft.world.item.Item expected, int missing, int unitsPerItem) {
		if (missing <= 0) return 0;
		ItemStack ammunition = serviceStack(SLOT_AMMUNITION, stack -> stack.is(expected));
		if (!ammunition.is(expected)) return 0;
		ammunition.shrink(1);
		setChanged();
		return Math.min(missing, unitsPerItem);
	}

	private static float repairValue(ItemStack stack) {
		if (stack.is(MorrowgearDrone.MORROW_ALLOY)) return 20.0f;
		if (stack.is(Items.IRON_INGOT)) return 8.0f;
		if (stack.is(Items.COPPER_INGOT)) return 4.0f;
		return 0.0f;
	}

	private static boolean isRepairMaterial(ItemStack stack) {
		return repairValue(stack) > 0.0f;
	}

	private void refillPowerFromInput(int minimumRequired) {
		if (!jp.morrowgear.drone.DockServicePolicy.shouldLoadNextPowerCell(storedPower, minimumRequired)) return;
		ItemStack input = serviceStack(SLOT_POWER_INPUT, stack -> dockEnergy(stack) > 0);
		int energy = dockEnergy(input);
		if (energy <= 0 || input.isEmpty()) return;
		input.shrink(1);
		storedPower = Math.min(powerCapacity(), storedPower + energy);
		setChanged();
	}

	private static int dockEnergy(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		int battery = MorrowgearDrone.batteryEnergyForItem(stack.getItem());
		if (battery > 0) return battery;
		if (stack.is(Items.COAL)) return 1000;
		if (stack.is(Items.CHARCOAL)) return 800;
		return 0;
	}

	private ItemStack serviceStack(int preferredSlot, java.util.function.Predicate<ItemStack> predicate) {
		ItemStack preferred = items.get(preferredSlot);
		if (predicate.test(preferred)) return preferred;
		for (int slot = SUPPLY_BUFFER_START; slot <= SUPPLY_BUFFER_END; slot++) {
			ItemStack candidate = items.get(slot);
			if (predicate.test(candidate)) return candidate;
		}
		return ItemStack.EMPTY;
	}

	public boolean commission(ServerPlayer player) {
		if (!isOwnedBy(player)) return false;
		ItemStack chassis = items.get(SLOT_DRONE);
		if (!chassis.is(MorrowgearDrone.DRONE_UNIT)) return false;
		if (!MorrowgearDrone.deployAtDock(player, chassis, worldPosition,
			items.get(SLOT_BATTERY), items.get(SLOT_ROLE_MODULE), items.get(SLOT_WEAPON_MODULE))) return false;
		chassis.shrink(1);
		if (!items.get(SLOT_BATTERY).isEmpty()) items.get(SLOT_BATTERY).shrink(1);
		if (!items.get(SLOT_ROLE_MODULE).isEmpty()) items.get(SLOT_ROLE_MODULE).shrink(1);
		if (!items.get(SLOT_WEAPON_MODULE).isEmpty()) items.get(SLOT_WEAPON_MODULE).shrink(1);
		setChanged();
		return true;
	}

	public boolean hasRecoveryCapacity() {
		if (items.get(SLOT_RECOVERY_OUTPUT).isEmpty()) return true;
		for (int slot = RECOVERY_BUFFER_START; slot <= RECOVERY_BUFFER_END; slot++) {
			if (items.get(slot).isEmpty()) return true;
		}
		return false;
	}

	public boolean placeRecoveryOutput(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		if (items.get(SLOT_RECOVERY_OUTPUT).isEmpty()) {
			items.set(SLOT_RECOVERY_OUTPUT, stack.copy());
			setChanged();
			return true;
		}
		for (int slot = RECOVERY_BUFFER_START; slot <= RECOVERY_BUFFER_END; slot++) {
			if (!items.get(slot).isEmpty()) continue;
			items.set(slot, stack.copy());
			setChanged();
			return true;
		}
		setChanged();
		return false;
	}

	@Override
	public int getContainerSize() {
		return INVENTORY_SIZE;
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	protected Component getDefaultName() {
		return Component.translatable("container.morrowgear_drone.dock_service");
	}

	@Override
	protected AbstractContainerMenu createMenu(int syncId, Inventory inventory) {
		return new ChestMenu(MenuType.GENERIC_9x3, syncId, inventory, this, 3);
	}

	@Override
	public boolean stillValid(Player player) {
		return matchesOwner(player.getUUID(), player.getScoreboardName()) && super.stillValid(player);
	}

	public String dockId() {
		return idFor(worldPosition);
	}

	public static String idFor(BlockPos pos) {
		return "DOCK-" + coordinateLabel(pos.getX()) + "-" + coordinateLabel(pos.getZ());
	}

	private static String coordinateLabel(int coordinate) {
		return (coordinate < 0 ? "N" : "P") + Integer.toString(Math.abs(coordinate), 36).toUpperCase();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("Owner", owner.toString());
		output.putString("OwnerName", ownerName);
		output.putString("Facing", facing.getSerializedName());
		output.putInt("StoredPower", storedPower);
		ContainerHelper.saveAllItems(output, items);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		try {
			owner = UUID.fromString(input.getStringOr("Owner", "00000000-0000-0000-0000-000000000000"));
		} catch (IllegalArgumentException ignored) {
			owner = new UUID(0, 0);
		}
		ownerName = input.getStringOr("OwnerName", "");
		facing = Direction.byName(input.getStringOr("Facing", "north"));
		if (facing == null || facing.getAxis().isVertical()) facing = Direction.NORTH;
		storedPower = Math.max(0, input.getIntOr("StoredPower", COMPATIBILITY_RESERVE));
		items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}
}
