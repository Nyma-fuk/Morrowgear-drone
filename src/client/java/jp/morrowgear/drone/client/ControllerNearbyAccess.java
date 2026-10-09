package jp.morrowgear.drone.client;

import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.PowerLossPolicy;
import jp.morrowgear.drone.block.DockBlockEntity;
import jp.morrowgear.drone.carrier.CarrierEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Uses ordinary interaction packets; never fabricates a client-side container. */
final class ControllerNearbyAccess {
    private ControllerNearbyAccess() {}

    private static String common(Minecraft client) {
        if (client.player == null || client.level == null || client.gameMode == null) return "未接続";
        if (client.player.isSpectator()) return "観戦中は操作できません";
        if (client.player.isShiftKeyDown()) return "しゃがみ操作を解除してください";
        if (!client.player.getMainHandItem().is(MorrowgearDrone.CONTROLLER)) return "メインハンドにコントローラーが必要です";
        return "";
    }

    static String entityReason(Minecraft client, Entity entity) {
        String reason = common(client);
        if (!reason.isEmpty()) return reason;
        if (entity == null || entity.isRemoved()) return "対象を受信していません";
        if (!client.player.isWithinEntityInteractionRange(entity, 0)) return "通常の操作距離まで近づいてください";
        if (!client.player.hasLineOfSight(entity)) return "対象との間に障害物があります";
        if (entity instanceof CarrierEntity carrier && !carrier.isOwnedBy(client.player.getUUID())) return "所有権がありません";
        if (entity instanceof DroneEntity drone) {
            if (!drone.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())) return "所有権がありません";
            if (!drone.isPowerLost()) return "現地回収は電力喪失機が対象です";
            if (client.player.distanceTo(drone) > PowerLossPolicy.MANUAL_RECOVERY_MAX_DISTANCE) return "現地回収には4m以内への接近が必要です";
        }
        return "";
    }

    static boolean interact(Minecraft client, Entity entity) {
        if (!entityReason(client, entity).isEmpty()) return false;
        client.gameMode.interact(client.player, entity, new EntityHitResult(entity, entity.getBoundingBox().getCenter()), InteractionHand.MAIN_HAND);
        return true;
    }

    private static BlockHitResult dockHit(Minecraft client, BlockPos pos) {
        var shape = client.level.getBlockState(pos).getShape(client.level, pos);
        Vec3 target = shape.isEmpty() ? Vec3.atCenterOf(pos) : shape.bounds().getCenter().add(Vec3.atLowerCornerOf(pos));
        return client.level.clip(new ClipContext(client.player.getEyePosition(), target,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
    }

    static String dockReason(Minecraft client, BlockPos pos) {
        String reason = common(client);
        if (!reason.isEmpty()) return reason;
        if (!(client.level.getBlockEntity(pos) instanceof DockBlockEntity dock)) return "Dock未受信、または撤去済み";
        if (!dock.matchesOwner(client.player.getUUID(), client.player.getScoreboardName())) return "所有権がありません";
        if (!client.player.isWithinBlockInteractionRange(pos, 0)) return "現地Dockの操作距離まで近づいてください";
        BlockHitResult hit = dockHit(client, pos);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return "Dockとの間に障害物があります";
        return "";
    }

    static boolean openDock(Minecraft client, BlockPos pos) {
        if (!dockReason(client, pos).isEmpty()) return false;
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, dockHit(client, pos));
        return true;
    }
}
