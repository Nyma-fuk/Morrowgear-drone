package jp.morrowgear.drone.carrier;

import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Invisible interaction and collision cell belonging to one physical expansion container. */
public final class CarrierContainerPartBlock extends Block {
    public static final IntegerProperty OFFSET_X = IntegerProperty.create("offset_x", 0, 4);
    public static final IntegerProperty OFFSET_Y = IntegerProperty.create("offset_y", 0, 2);
    public static final IntegerProperty OFFSET_Z = IntegerProperty.create("offset_z", 0, 2);
    private static final VoxelShape FULL = box(0, 0, 0, 16, 16, 16);
    private static final VoxelShape TOP = box(0, 0, 0, 16, 6.4, 16);

    public CarrierContainerPartBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(OFFSET_X, CarrierContainerFootprint.ANCHOR_X_INDEX)
            .setValue(OFFSET_Y, 0)
            .setValue(OFFSET_Z, CarrierContainerFootprint.ANCHOR_Z_INDEX));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OFFSET_X, OFFSET_Y, OFFSET_Z);
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                             BlockPos pos, CollisionContext context) {
        return state.getValue(OFFSET_Y) == CarrierContainerFootprint.MAX_Y ? TOP : FULL;
    }

    @Override protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown() && stack.is(MorrowgearDrone.RECOVERY_TOOL)) return InteractionResult.PASS;
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide())
            return CarrierContainerFootprint.resolveAnchor(level, pos) == null
                ? InteractionResult.PASS : InteractionResult.SUCCESS;
        return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
            && CarrierContainerFootprint.open(serverPlayer, pos)
            ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }
}
