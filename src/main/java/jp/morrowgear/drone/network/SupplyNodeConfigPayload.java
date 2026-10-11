package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import jp.morrowgear.drone.SupplyNetworkRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Owner-authenticated registration for one explicitly selected logistics container. */
public record SupplyNodeConfigPayload(long position, boolean registered) implements CustomPacketPayload {
	public static final Type<SupplyNodeConfigPayload> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath("morrowgear_drone", "supply_node_config"));
	public static final StreamCodec<ByteBuf, SupplyNodeConfigPayload> CODEC = new StreamCodec<>() {
		@Override public SupplyNodeConfigPayload decode(ByteBuf buffer) {
			return new SupplyNodeConfigPayload(buffer.readLong(), buffer.readBoolean());
		}
		@Override public void encode(ByteBuf buffer, SupplyNodeConfigPayload payload) {
			buffer.writeLong(payload.position()).writeBoolean(payload.registered());
		}
	};

	public void apply(ServerPlayer player) {
		SupplyNetworkRuntime.setLogisticsNode(player, BlockPos.of(position), registered);
	}

	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
