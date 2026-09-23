package jp.morrowgear.drone.block;

import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class DockPartBlock extends Block {
	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 5.5, 16);
	private final int offsetX;
	private final int offsetZ;
	private final VoxelShape shape;

	public DockPartBlock(BlockBehaviour.Properties properties, int offsetX, int offsetZ) {
		this(properties, offsetX, offsetZ, 3);
	}

	public DockPartBlock(BlockBehaviour.Properties properties, int offsetX, int offsetZ, int footprint) {
		super(properties);
		this.offsetX = offsetX;
		this.offsetZ = offsetZ;
		this.shape = footprint == 3 ? SHAPE : Block.box(0, 0, 0, 16,
			Math.max(Math.abs(offsetX), Math.abs(offsetZ)) == 2 ? 6.65 : 5.048, 16);
	}

	@Override
	protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape;
	}

	@Override
	protected net.minecraft.world.InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
		BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
		net.minecraft.world.phys.BlockHitResult hit) {
		if (!DockBlockEntity.isSupply(stack)) return net.minecraft.world.InteractionResult.PASS;
		if (level.isClientSide()) return net.minecraft.world.InteractionResult.SUCCESS;
		return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
			&& level.getBlockEntity(pos.offset(-offsetX, 0, -offsetZ)) instanceof DockBlockEntity dock
			&& dock.insertSupply(serverPlayer, stack)
			? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.FAIL;
	}

	@Override
	protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
		Player player, net.minecraft.world.phys.BlockHitResult hit) {
		if (!(level.getBlockEntity(pos.offset(-offsetX, 0, -offsetZ)) instanceof DockBlockEntity dock))
			return net.minecraft.world.InteractionResult.PASS;
		if (level.isClientSide()) return net.minecraft.world.InteractionResult.SUCCESS;
		if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) || !dock.isOwnedBy(serverPlayer))
			return net.minecraft.world.InteractionResult.FAIL;
		if (player.isShiftKeyDown()) dock.commission(serverPlayer);
		else player.openMenu(dock);
		return net.minecraft.world.InteractionResult.SUCCESS;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		BlockState result = super.playerWillDestroy(level, pos, state, player);
		if (level instanceof ServerLevel serverLevel) MorrowgearDrone.removeDock(serverLevel, pos.offset(-offsetX, 0, -offsetZ), true);
		return result;
	}
}
