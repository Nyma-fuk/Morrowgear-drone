package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record FleetLinkRequest(long token) implements CustomPacketPayload {
	public static final Type<FleetLinkRequest> TYPE = new Type<>(
		Identifier.fromNamespaceAndPath("morrowgear_drone", "fleet_link_request"));
	public static final StreamCodec<ByteBuf, FleetLinkRequest> CODEC =
		ByteBufCodecs.LONG.map(FleetLinkRequest::new, FleetLinkRequest::token);
	@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
