package jp.morrowgear.drone.item;

import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class RecoveryToolItem extends Item {
	public RecoveryToolItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
		InteractionHand hand) {
		if (!(target instanceof DroneEntity drone) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!MorrowgearDrone.recoverPowerLost(serverPlayer, drone)) return InteractionResult.FAIL;
		stack.hurtAndBreak(1, serverPlayer, hand.asEquipmentSlot());
		return InteractionResult.SUCCESS;
	}
}
