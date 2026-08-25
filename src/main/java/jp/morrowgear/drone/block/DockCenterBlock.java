package jp.morrowgear.drone.block;

import com.mojang.serialization.MapCodec;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class DockCenterBlock extends BaseEntityBlock {
	public static final MapCodec<DockCenterBlock> CODEC = simpleCodec(DockCenterBlock::new);
	private static final VoxelShape SHAPE = box(0, 0, 0, 16, 4.5, 16);

	public DockCenterBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DockBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		BlockState result = super.playerWillDestroy(level, pos, state, player);
		if (level instanceof ServerLevel serverLevel) MorrowgearDrone.removeDock(serverLevel, pos, true);
		return result;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof DockBlockEntity dock)) return InteractionResult.PASS;
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) || !dock.isOwnedBy(serverPlayer))
			return InteractionResult.FAIL;
		if (player.isShiftKeyDown()) {
			boolean commissioned = dock.commission(serverPlayer);
			serverPlayer.sendSystemMessage(Component.literal(commissioned
				? "[MORROWGEAR] DOCK / AIRFRAME ONLINE"
				: "[MORROWGEAR] DOCK / CHASSIS REQUIRED"));
			return InteractionResult.SUCCESS;
		}
		player.openMenu(dock);
		return InteractionResult.SUCCESS;
	}
}
