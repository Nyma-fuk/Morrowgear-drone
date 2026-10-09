package jp.morrowgear.drone.item;

import jp.morrowgear.drone.MarkedOperationCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/** Four-point 3D envelope marker. Sneak-use clears the current draft. */
public final class OperationMarkerItem extends Item {
    private static final String ROOT = "MorrowgearOperationMarker";
    public OperationMarkerItem(Properties properties) { super(properties); }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
        var stack = context.getItemInHand();
        if (player.isShiftKeyDown()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.remove(ROOT));
            player.sendSystemMessage(Component.literal("[MORROWGEAR] 採掘範囲の指定を取り消しました。"));
            return InteractionResult.SUCCESS;
        }
        String dimension = level.dimension().toString();
        CompoundTag saved = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag marker = saved.getCompound(ROOT).orElse(new CompoundTag());
        int count = marker.getIntOr("Count", 0);
        if (!dimension.equals(marker.getStringOr("Dimension", dimension)) || count < 0 || count >= 4) count = 0;
        BlockPos point = context.getClickedPos().relative(context.getClickedFace()).immutable();
        int next = count + 1;
        int slot = count;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag data = slot == 0 ? new CompoundTag() : marker.copy();
			data.putString("Dimension", dimension); data.putLong("Point" + slot, point.asLong());
            data.putInt("Count", next); root.put(ROOT, data);
        });
        player.sendSystemMessage(Component.literal("[MORROWGEAR] 採掘マーカー " + next + "/4 / " + point.toShortString()));
        if (next < 4) return InteractionResult.SUCCESS;
        CompoundTag completed = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            .getCompound(ROOT).orElse(new CompoundTag());
        BlockPos min = point, max = point;
        for (int i = 0; i < 4; i++) {
            BlockPos value = BlockPos.of(completed.getLongOr("Point" + i, point.asLong()));
            min = new BlockPos(Math.min(min.getX(), value.getX()), Math.min(min.getY(), value.getY()), Math.min(min.getZ(), value.getZ()));
            max = new BlockPos(Math.max(max.getX(), value.getX()), Math.max(max.getY(), value.getY()), Math.max(max.getZ(), value.getZ()));
        }
        boolean started = MarkedOperationCoordinator.start(player, min, max);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.remove(ROOT));
        return started ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }
}
