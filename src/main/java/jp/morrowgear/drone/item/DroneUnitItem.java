package jp.morrowgear.drone.item;

import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.StoredDroneState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class DroneUnitItem extends Item {
	public DroneUnitItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		ItemStack stack = player.getItemInHand(hand);
		if (StoredDroneState.isStoredDrone(stack)) {
			if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendSystemMessage(
				Component.literal("[MORROWGEAR] Recovered airframe requires Dock service."));
			return InteractionResult.FAIL;
		}
		if (!(player instanceof ServerPlayer serverPlayer) || !MorrowgearDrone.deploy(serverPlayer, stack)) return InteractionResult.FAIL;
		if (!player.isCreative()) stack.shrink(1);
		return InteractionResult.SUCCESS;
	}
}
