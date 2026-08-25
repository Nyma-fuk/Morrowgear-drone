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

	public DockPartBlock(BlockBehaviour.Properties properties, int offsetX, int offsetZ) {
		super(properties);
		this.offsetX = offsetX;
		this.offsetZ = offsetZ;
	}

	@Override
	protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		BlockState result = super.playerWillDestroy(level, pos, state, player);
		if (level instanceof ServerLevel serverLevel) MorrowgearDrone.removeDock(serverLevel, pos.offset(-offsetX, 0, -offsetZ), true);
		return result;
	}
}
