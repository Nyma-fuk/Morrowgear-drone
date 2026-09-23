package jp.morrowgear.drone.item;

import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

public final class DockKitItem extends Item {
	public DockKitItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		BlockPos center = context.getClickedPos().relative(context.getClickedFace());
		if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
		if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;

		for (int i = 0; i < MorrowgearDrone.WIDE_DOCK_PARTS.length; i++) {
			BlockPos part = center.offset(i % 5 - 2, 0, i / 5 - 2);
			if (!level.getBlockState(part).isAir()) {
				player.sendSystemMessage(Component.literal("[MORROWGEAR] Dockの設置には5×5の空間が必要です。"));
				return InteractionResult.FAIL;
			}
		}

		for (int i = 0; i < MorrowgearDrone.WIDE_DOCK_PARTS.length; i++) {
			BlockPos part = center.offset(i % 5 - 2, 0, i / 5 - 2);
			level.setBlockAndUpdate(part, MorrowgearDrone.WIDE_DOCK_PARTS[i].defaultBlockState());
		}
		if (level.getBlockEntity(center) instanceof DockBlockEntity dock) dock.initialize(player);
		if (!player.isCreative()) context.getItemInHand().shrink(1);
		player.sendSystemMessage(Component.literal("[MORROWGEAR] " + DockBlockEntity.idFor(center) + " を登録しました。"));
		return InteractionResult.SUCCESS;
	}
}
