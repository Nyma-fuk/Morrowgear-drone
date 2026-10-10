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
import net.minecraft.world.item.context.UseOnContext;
import jp.morrowgear.drone.carrier.CarrierExpansionContainer;
import jp.morrowgear.drone.carrier.CarrierExpansionItemData;
import jp.morrowgear.drone.carrier.CarrierExpansionTransactions;
import jp.morrowgear.drone.carrier.CarrierContainerFootprint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

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

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getPlayer() instanceof ServerPlayer player) || !player.isShiftKeyDown()) return InteractionResult.PASS;
		BlockPos anchor = CarrierContainerFootprint.resolveAnchor(context.getLevel(), context.getClickedPos());
		if (anchor == null || !(context.getLevel().getBlockEntity(anchor) instanceof CarrierExpansionContainer cargo))
			return InteractionResult.PASS;
		if (!cargo.mayAccess(player) || cargo.transferReserved()) return InteractionResult.FAIL;
		ItemStack recovered = cargo.recoveryItem();
		var identity=CarrierExpansionItemData.read(recovered);
		if(identity==null||!(context.getLevel() instanceof ServerLevel level))return InteractionResult.FAIL;
		var moved=CarrierExpansionTransactions.recoverItem(level,identity,anchor,player.getUUID());
		if(!moved.success()){
			player.sendSystemMessage(Component.literal("[MORROWGEAR] 回収を停止 / "+moved.detail()));
			return InteractionResult.FAIL;
		}
		CarrierContainerFootprint.removeContainer(level, anchor);
		if (!player.getInventory().add(recovered)) player.drop(recovered, false);
		context.getItemInHand().hurtAndBreak(1, player, context.getHand().asEquipmentSlot());
		return InteractionResult.SUCCESS;
	}
}
