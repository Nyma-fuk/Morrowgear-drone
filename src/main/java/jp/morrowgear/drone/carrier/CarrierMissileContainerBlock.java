package jp.morrowgear.drone.carrier;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class CarrierMissileContainerBlock extends BaseEntityBlock {
    public static final MapCodec<CarrierMissileContainerBlock> CODEC = simpleCodec(CarrierMissileContainerBlock::new);
    private static final VoxelShape SHAPE = box(0, 0, 0, 16, 16, 16);
    public CarrierMissileContainerBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CarrierMissileContainerBlockEntity(pos, state);
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                             BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CarrierMissileContainerBlockEntity magazine
            && placer instanceof ServerPlayer player && level instanceof ServerLevel serverLevel) {
            magazine.initialize(player, stack);
            var identity=new CarrierExpansionItemData.Identity(magazine.moduleId(),magazine.ownerId(),magazine.moduleType());
            var registered=CarrierExpansionTransactions.placed(serverLevel,identity,pos);
            if(!registered.success()){
                magazine.setTransferReserved(true);
                player.sendSystemMessage(Component.literal("[MORROWGEAR] コンテナID競合 / "+registered.detail()));
            }
        }
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                          Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        return player instanceof ServerPlayer serverPlayer && CarrierContainerFootprint.open(serverPlayer, pos)
            ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }
    @Override protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown() && stack.is(jp.morrowgear.drone.MorrowgearDrone.RECOVERY_TOOL))
            return InteractionResult.PASS;
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) { return state; }
}
