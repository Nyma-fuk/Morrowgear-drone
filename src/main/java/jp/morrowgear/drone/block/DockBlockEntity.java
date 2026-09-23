package jp.morrowgear.drone.block;

import java.util.UUID;

import jp.morrowgear.drone.DockMenu;
import jp.morrowgear.drone.DockSupplyPolicy;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.DroneEntity;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
	private int storedPower;
	private int fuelCredit;
	private int weaponCredit;
	private int autocannonCredit;
	private int missileCredit;
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

	public UUID ownerId() { return owner; }

	public boolean matchesOwner(UUID playerId, String playerName) {
		return isOwnedBy(playerId) || (!ownerName.isBlank() && ownerName.equalsIgnoreCase(playerName));
	}

	public boolean isOwnedBy(ServerPlayer player) {
		if (isOwnedBy(player.getUUID())) return true;
		boolean sameNamedOwner = !ownerName.isBlank() && ownerName.equalsIgnoreCase(player.getScoreboardName());
		boolean legacySingleplayer = owner.equals(new UUID(0, 0)) && player.level().getServer().isSingleplayer()
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
		normalizePowerCapacity();
		return storedPower;
	}

	public int storedFlightPower() { return storedPower(); }
	public int flightFuelCredit() { normalizePowerCapacity(); return fuelCredit; }
	public int storedWeaponPower() { return weaponCredit; }
	public int weaponPowerCapacity() { return DockSupplyPolicy.LASER_CELL_ENERGY; }
	public int autocannonCredit() { return autocannonCredit; }
	public int missileCredit() { return missileCredit; }

	public void setStoredPowerForVerification(int power) {
		storedPower = Math.max(0, Math.min(powerCapacity(), power));
		fuelCredit = 0;
		setChanged();
	}

	public void setSupplyCreditsForVerification(int weapon, int gun, int missile) {
		DockSupplyPolicy.PackCredits credits = new DockSupplyPolicy.PackCredits(weapon, gun, missile);
		weaponCredit = credits.weapon();
		autocannonCredit = credits.gun();
		missileCredit = credits.missile();
		setChanged();
	}

	public void restoreServiceSnapshotForVerification(CompoundTag tag, HolderLookup.Provider registries) {
		loadAdditional(net.minecraft.world.level.storage.TagValueInput.create(
			net.minecraft.util.ProblemReporter.DISCARDING, registries, tag));
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
		if (amount > powerCapacity()) return false;
		refillPowerFromInput(amount);
		if (storedPower < amount) return false;
		storedPower -= amount;
		setChanged();
		return true;
	}

	public int provideWeaponCharge(int requested) {
		DockSupplyPolicy.PackTransfer transfer = consumePack(SupplyKind.LASER, weaponCredit,
			requested, DockSupplyPolicy.LASER_CELL_ENERGY);
		weaponCredit = transfer.credit();
		if (transfer.supplied() > 0) setChanged();
		return transfer.supplied();
	}

	public int provideFlightCharge(int requested) {
		int amount = Math.max(0, requested);
		if (amount == 0) return 0;
		refillPowerFromInput(amount);
		int supplied = Math.min(amount, storedPower);
		if (supplied > 0) { storedPower -= supplied; setChanged(); }
		return supplied;
	}

	public float provideRepair(float missingHealth) {
		if (!Float.isFinite(missingHealth) || missingHealth <= 0.0f) return 0.0f;
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
		DockSupplyPolicy.PackTransfer transfer = consumePack(SupplyKind.GUN, autocannonCredit,
			missingRounds, DockSupplyPolicy.MAGAZINE_ROUNDS);
		autocannonCredit = transfer.credit();
		if (transfer.supplied() > 0) setChanged();
		return transfer.supplied();
	}

	public int provideMissiles(int missingMissiles) {
		DockSupplyPolicy.PackTransfer transfer = consumePack(SupplyKind.MISSILE, missileCredit,
			missingMissiles, DockSupplyPolicy.MISSILE_PACK_ROUNDS);
		missileCredit = transfer.credit();
		if (transfer.supplied() > 0) setChanged();
		return transfer.supplied();
	}

	public boolean hasRepairMaterial() {
		return !serviceStack(SLOT_REPAIR, DockBlockEntity::isRepairMaterial).isEmpty();
	}

	public boolean hasPowerSupply() {
		return hasFlightPowerSupply();
	}

	public boolean hasFlightPowerSupply() {
		return storedPower() > 0 || fuelCredit > 0 || supplyCount(SupplyKind.FUEL) > 0;
	}

	public boolean hasWeaponPowerSupply() {
		return weaponCredit > 0 || supplyCount(SupplyKind.LASER) > 0;
	}

	public boolean completionResourceExhausted(boolean flightRequired, boolean weaponRequired,
		boolean ammunitionRequired, boolean ammunitionAvailable) {
		return DockSupplyPolicy.completionResourceExhausted(flightRequired, hasFlightPowerSupply(),
			weaponRequired, hasWeaponPowerSupply(), ammunitionRequired, ammunitionAvailable);
	}

	public boolean hasAutocannonAmmunition() {
		return autocannonCredit > 0 || supplyCount(SupplyKind.GUN) > 0;
	}

	public boolean hasMissileAmmunition() {
		return missileCredit > 0 || supplyCount(SupplyKind.MISSILE) > 0;
	}

	private DockSupplyPolicy.PackTransfer consumePack(SupplyKind kind, int credit, int missing, int unitsPerItem) {
		ItemStack pack = serviceStack(SLOT_AMMUNITION, stack -> DockSupplyPolicy.kind(stack) == kind);
		DockSupplyPolicy.PackTransfer transfer = DockSupplyPolicy.takePack(credit, missing, unitsPerItem, !pack.isEmpty());
		if (transfer.consumeItem()) pack.shrink(1);
		return transfer;
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
		normalizePowerCapacity();
		int target = Math.clamp(minimumRequired, 0, powerCapacity());
		// The largest tank needs at most 23 charcoal items; work stays bounded even for huge requests.
		while (storedPower < target) {
			if (fuelCredit == 0) {
				ItemStack input = serviceStack(SLOT_POWER_INPUT, stack -> dockEnergy(stack) > 0);
				int energy = dockEnergy(input);
				if (energy <= 0 || input.isEmpty()) return;
				input.shrink(1);
				fuelCredit = energy;
			}
			int transfer = Math.min(fuelCredit, powerCapacity() - storedPower);
			storedPower += transfer;
			fuelCredit -= transfer;
			setChanged();
		}
	}

	private void normalizePowerCapacity() {
		DockSupplyPolicy.FlightReserve reserve = DockSupplyPolicy.normalizeFlight(storedPower, fuelCredit, powerCapacity());
		if (storedPower == reserve.stored() && fuelCredit == reserve.credit()) return;
		storedPower = reserve.stored();
		fuelCredit = reserve.credit();
		super.setChanged();
	}

	@Override
	public void setChanged() {
		normalizePowerCapacity();
		super.setChanged();
	}

	public static boolean isSupply(ItemStack stack) {
		return DockSupplyPolicy.kind(stack) != null;
	}

	public boolean insertSupply(ServerPlayer player, ItemStack held) {
		if (!isOwnedBy(player) || !stillValid(player) || !isSupply(held)) return false;
		int inserted = insertNetworkSupply(held);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + dockId() + (inserted > 0
			? " / SUPPLY +" + inserted : " / SUPPLY BUFFER FULL")));
		return true;
	}

	/** Physical items in service input/buffer slots only; opened-pack credits are reported separately. */
	public int supplyCount(SupplyKind kind) {
		int count = 0;
		for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
			ItemStack stack = items.get(slot);
			if (DockSupplyPolicy.isServiceSlot(slot, kind) && DockSupplyPolicy.kind(stack) == kind)
				count += stack.getCount();
		}
		return count;
	}

	/** Server-thread API. Shrinks the caller's stack by the returned count; zero leaves it unchanged.
	 * The logistics caller must authorize the route/owner before calling this method. */
	public int insertNetworkSupply(ItemStack stack) {
		if (!isSupply(stack) || isRemoved() || level != null && level.isClientSide()) return 0;
		for (ItemStack existing : items) if (existing == stack) return 0;
		int original = stack.getCount();
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			for (int slot = SUPPLY_BUFFER_START; slot <= SUPPLY_BUFFER_END && !stack.isEmpty(); slot++) {
				ItemStack existing = items.get(slot);
				if ((pass == 0) == existing.isEmpty()) continue;
				if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) continue;
				int limit = Math.min(getMaxStackSize(), stack.getMaxStackSize());
				int transfer = Math.min(stack.getCount(), limit - existing.getCount());
				if (transfer <= 0) continue;
				if (existing.isEmpty()) items.set(slot, stack.copyWithCount(transfer));
				else existing.grow(transfer);
				stack.shrink(transfer);
			}
		}
		int inserted = original - stack.getCount();
		if (inserted > 0) setChanged();
		return inserted;
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
		// Legacy misplaced stacks remain extractable but recovery and installed equipment are never fuel.
		for (int slot = SUPPLY_BUFFER_START; slot <= SUPPLY_BUFFER_END; slot++) {
			ItemStack candidate = items.get(slot);
			if (predicate.test(candidate)) return candidate;
		}
		return ItemStack.EMPTY;
	}

	public boolean commission(ServerPlayer player) {
		if (!isOwnedBy(player) || !stillValid(player)) return false;
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
		return new DockMenu(syncId, inventory, this);
	}

	@Override
	public boolean canOpen(Player player) {
		return stillValid(player) && super.canOpen(player);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return DockSupplyPolicy.mayPlace(slot, stack);
	}

	@Override
	public boolean stillValid(Player player) {
		return level != null && player.level() == level && isOwnedBy(player.getUUID())
			&& player.distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 64.0 && super.stillValid(player);
	}

	public DroneEntity dockedDrone() {
		if (level == null || level.isClientSide()) return null;
		return level.getEntitiesOfClass(DroneEntity.class, new AABB(worldPosition).inflate(3),
			drone -> drone.isAlive() && drone.isOwnedBy(owner) && drone.isDocked()
				&& drone.hasDock() && drone.dockPos().equals(worldPosition)
				&& drone.position().distanceToSqr(Vec3.atCenterOf(worldPosition)) <= 4)
			.stream().min(java.util.Comparator.comparingInt(DroneEntity::getId)).orElse(null);
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
		normalizePowerCapacity();
		super.saveAdditional(output);
		output.putString("Owner", owner.toString());
		output.putString("OwnerName", ownerName);
		output.putString("Facing", facing.getSerializedName());
		output.putInt("StoredPower", storedPower);
		output.putInt("FuelCredit", fuelCredit);
		new DockSupplyPolicy.PackCredits(weaponCredit, autocannonCredit, missileCredit).write(output);
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
		storedPower = input.getIntOr("StoredPower", COMPATIBILITY_RESERVE);
		fuelCredit = input.getIntOr("FuelCredit", 0);
		DockSupplyPolicy.PackCredits credits = DockSupplyPolicy.PackCredits.read(input);
		weaponCredit = credits.weapon();
		autocannonCredit = credits.gun();
		missileCredit = credits.missile();
		items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
		normalizePowerCapacity();
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
