package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SupplyNetworkStatusRequest(int requestId) implements CustomPacketPayload {
    public static final Type<SupplyNetworkStatusRequest> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("morrowgear_drone", "supply_network_status_request"));
    public static final StreamCodec<ByteBuf, SupplyNetworkStatusRequest> CODEC = StreamCodec.composite(
        ByteBufCodecs.INT, SupplyNetworkStatusRequest::requestId, SupplyNetworkStatusRequest::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
