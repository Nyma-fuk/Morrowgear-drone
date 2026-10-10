package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity;
import jp.morrowgear.drone.network.SupplyNetworkStatusPayload;
import jp.morrowgear.drone.network.SupplyNetworkStatusRequest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

public final class SupplyNetworkTransport {
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private SupplyNetworkTransport() {}
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(SupplyNetworkStatusPayload.TYPE, SupplyNetworkStatusPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SupplyNetworkStatusRequest.TYPE, SupplyNetworkStatusRequest.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_REQUEST.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_REQUEST.clear());
        ServerPlayNetworking.registerGlobalReceiver(SupplyNetworkStatusRequest.TYPE, (request, context) -> context.server().execute(() -> {
            var player = context.player();
            long tick = context.server().getTickCount();
            Long previous = LAST_REQUEST.get(player.getUUID());
            if (previous != null && tick >= previous && tick - previous < 20
                || !ServerPlayNetworking.canSend(player, SupplyNetworkStatusPayload.TYPE)) return;
            LAST_REQUEST.put(player.getUUID(), tick);
            var docks = SupplyNetworkRuntime.summary(player.level(), player.getUUID()).stream().map(summary ->
                new SupplyNetworkStatusPayload.DockStatus(summary.configuration().route().source(),
                    summary.configuration().route().dock(), summary.configuration().enabled(), summary.status(),
                    summary.stock().stream().map(stock -> new SupplyNetworkStatusPayload.Stock(
                        new SupplyNetworkPolicy.Rule(stock.kind(), stock.minimum(), stock.target(), stock.priority()),
                        stock.stock(), stock.incoming(), stock.missing())).toList(),
                    summary.jobs().stream().map(job -> new SupplyNetworkStatusPayload.Delivery(job.drone(), job.status(),
                        job.kind(), job.remaining())).toList())).toList();
            ServerPlayNetworking.send(player, new SupplyNetworkStatusPayload(request.requestId(), player.getUUID(),
                player.level().dimension().identifier().toString(), docks, logisticsNodes(player)));
        }));
    }

    /** Nearby discovery is deliberately shallow. Saved route sources remain visible without revealing other containers. */
    private static List<SupplyNetworkStatusPayload.LogisticsNode> logisticsNodes(net.minecraft.server.level.ServerPlayer player) {
        var level = player.level();
        BlockPos origin = player.blockPosition();
        Map<Long, Boolean> registered = new LinkedHashMap<>();
        for (long position : SupplyNetworkSavedData.get(level).nodes(player.getUUID(),
            level.dimension().identifier().toString())) registered.put(position, true);
        for (var summary : SupplyNetworkRuntime.summary(level, player.getUUID()))
			if (summary.configuration().route().source() != SupplyNetworkRegistry.Route.AUTO_SOURCE)
				registered.put(summary.configuration().route().source(), true);

        Map<Long, SupplyNetworkStatusPayload.LogisticsNode> nodes = new LinkedHashMap<>();
        for (long packed : registered.keySet()) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.hasChunkAt(pos)) {
                nodes.put(packed, new SupplyNetworkStatusPayload.LogisticsNode(packed, "登録物流ノード",
                    "未読込", Math.min(4096, (int)Math.round(Math.sqrt(pos.distSqr(origin)))),
                    Math.clamp(pos.getY() - origin.getY(), -128, 127), true));
                continue;
            }
            var block = level.getBlockEntity(pos);
            if (block instanceof Container container && supported(block, player.getUUID()))
                nodes.put(packed, node(origin, pos, container, block.getBlockState().getBlock().getName().getString(), true));
        }

        int radius = SupplyNetworkPolicy.DISCOVERY_RADIUS;
        int minChunkX = (origin.getX() - radius) >> 4, maxChunkX = (origin.getX() + radius) >> 4;
        int minChunkZ = (origin.getZ() - radius) >> 4, maxChunkZ = (origin.getZ() + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            if (!level.hasChunk(chunkX, chunkZ)) continue;
            for (var block : level.getChunk(chunkX, chunkZ).getBlockEntities().values()) {
                if (!(block instanceof Container container) || !supported(block, player.getUUID())) continue;
                BlockPos pos = block.getBlockPos();
                int dy = pos.getY() - origin.getY();
                long dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ();
                if (Math.abs(dy) > SupplyNetworkPolicy.DISCOVERY_VERTICAL
                    || dx * dx + dz * dz > (long)radius * radius) continue;
                if (block instanceof BaseContainerBlockEntity base && base.isLocked()) continue;
                nodes.putIfAbsent(pos.asLong(), node(origin, pos, container,
                    block.getBlockState().getBlock().getName().getString(), false));
            }
        }
        return nodes.values().stream().sorted(Comparator
            .comparing(SupplyNetworkStatusPayload.LogisticsNode::registered).reversed()
            .thenComparingInt(SupplyNetworkStatusPayload.LogisticsNode::distance)
            .thenComparingLong(SupplyNetworkStatusPayload.LogisticsNode::position))
            .limit(SupplyNetworkPolicy.MAX_DISCOVERED_NODES).toList();
    }

    private static boolean supported(net.minecraft.world.level.block.entity.BlockEntity block, UUID owner) {
        return block instanceof ChestBlockEntity || block instanceof BarrelBlockEntity || block instanceof ShulkerBoxBlockEntity
            || block instanceof CarrierCargoContainerBlockEntity cargo && cargo.logisticsAccessible(owner);
    }

    private static SupplyNetworkStatusPayload.LogisticsNode node(BlockPos origin, BlockPos pos, Container container,
        String name, boolean registered) {
        double distance = Math.sqrt(pos.distSqr(origin));
        return new SupplyNetworkStatusPayload.LogisticsNode(pos.asLong(), name, contents(container),
            Math.min(4096, (int)Math.round(distance)), Math.clamp(pos.getY() - origin.getY(), -128, 127), registered);
    }

    private static String contents(Container container) {
        if (container instanceof CarrierCargoContainerBlockEntity cargo) return cargo.indexedContentsSummary();
        Map<String, Integer> totals = new LinkedHashMap<>();
        int total = 0;
        for (int slot = 0; slot < Math.min(container.getContainerSize(), SupplyNetworkPolicy.MAX_SOURCE_SLOTS); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            total += stack.getCount();
            totals.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
        }
        if (totals.isEmpty()) return "空";
        List<Map.Entry<String, Integer>> top = new ArrayList<>(totals.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey));
        String summary = top.stream().limit(3).map(entry -> entry.getKey() + " x" + entry.getValue())
            .collect(java.util.stream.Collectors.joining(" / "));
        return clip(top.size() > 3 ? summary + " / 他" + (top.size() - 3) + "種 / 計" + total : summary, 48);
    }

    private static String clip(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(1, maximum - 1)) + "…";
    }
}
