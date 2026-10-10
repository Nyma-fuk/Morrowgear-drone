package jp.morrowgear.drone.carrier;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Ground magazine. Twenty physical cells occupy the first twenty slots of a three-row service UI. */
public final class CarrierMissileContainerBlockEntity extends BaseContainerBlockEntity implements CarrierExpansionContainer {
    public static final int UI_SIZE = 27;
    private NonNullList<ItemStack> items = NonNullList.withSize(UI_SIZE, ItemStack.EMPTY);
    private UUID moduleId = UUID.randomUUID();
    private UUID owner = new UUID(0, 0);
    private boolean transferReserved;

    public CarrierMissileContainerBlockEntity(BlockPos pos, BlockState state) {
        super(CarrierModule.MISSILE_CONTAINER_BLOCK_ENTITY, pos, state);
    }

    public void initialize(ServerPlayer player, ItemStack source) {
        initialize(player.getUUID(), source);
    }
    public void initialize(UUID placedOwner, ItemStack source) {
        owner = placedOwner;
        var identity = CarrierExpansionItemData.read(source);
        if (identity != null && identity.owner().equals(owner)
            && identity.type() == CarrierExpansionPolicy.Type.MISSILE_BATTERY) moduleId = identity.id();
        source.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        setChanged();
        if (level instanceof ServerLevel serverLevel) CarrierContainerFootprint.ensure(serverLevel, getBlockPos());
    }

    @Override public UUID moduleId() { return moduleId; }
    @Override public UUID ownerId() { return owner; }
    @Override public CarrierExpansionPolicy.Type moduleType() { return CarrierExpansionPolicy.Type.MISSILE_BATTERY; }
    @Override public boolean transferReserved() { return transferReserved; }
    @Override public void setTransferReserved(boolean value) { transferReserved = value; setChanged(); }
    @Override public boolean mayAccess(ServerPlayer player) { return owner.equals(player.getUUID()) && !transferReserved; }
    @Override public void setLevel(net.minecraft.world.level.Level value) {
        super.setLevel(value);
        if(value instanceof ServerLevel serverLevel)CarrierContainerFootprint.ensureAfterLoad(serverLevel,getBlockPos());
        if(value instanceof ServerLevel serverLevel&&!owner.equals(new UUID(0,0))){
            var identity=new CarrierExpansionItemData.Identity(moduleId,owner,moduleType());
            if(!CarrierExpansionTransactions.placed(serverLevel,identity,getBlockPos()).success())transferReserved=true;
        }
    }
    public int missiles() {
        int total = 0;
        for (int i = 0; i < CarrierMissileMagazinePolicy.CAPACITY; i++) if (!items.get(i).isEmpty()) total++;
        return total;
    }

    @Override public ItemStack recoveryItem() {
        ItemStack result = new ItemStack(CarrierModule.MISSILE_MODULE_ITEM);
        CarrierExpansionItemData.write(result, new CarrierExpansionItemData.Identity(moduleId, owner,
            CarrierExpansionPolicy.Type.MISSILE_BATTERY));
        result.set(net.minecraft.core.component.DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        return result;
    }

    @Override protected Component getDefaultName() { return Component.translatable("container.morrowgear_drone.carrier_missile"); }
    @Override public int getContainerSize() { return UI_SIZE; }
    @Override public int getMaxStackSize() { return 1; }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> value) { items = value; }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < CarrierMissileMagazinePolicy.CAPACITY
            && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                .equals(CarrierMissileMagazinePolicy.RELOAD_ITEM);
    }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return ChestMenu.threeRows(id, inventory, this);
    }
    @Override public boolean stillValid(Player player) { return super.stillValid(player); }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output); ContainerHelper.saveAllItems(output, items);
        output.putString("ModuleId", moduleId.toString()); output.putString("Owner", owner.toString());
        output.putBoolean("TransferReserved", transferReserved);
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input); items = NonNullList.withSize(UI_SIZE, ItemStack.EMPTY); ContainerHelper.loadAllItems(input, items);
        try { moduleId = UUID.fromString(input.getStringOr("ModuleId", UUID.randomUUID().toString())); }
        catch (IllegalArgumentException ignored) { moduleId = UUID.randomUUID(); }
        try { owner = UUID.fromString(input.getStringOr("Owner", new UUID(0, 0).toString())); }
        catch (IllegalArgumentException ignored) { owner = new UUID(0, 0); }
        transferReserved = false;
    }
}
