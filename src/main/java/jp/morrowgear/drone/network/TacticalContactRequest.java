package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record TacticalContactRequest(long token) implements CustomPacketPayload {
    public static final Type<TacticalContactRequest> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath("morrowgear_drone", "tactical_contact_request"));
    public static final StreamCodec<ByteBuf, TacticalContactRequest> CODEC =
        ByteBufCodecs.LONG.map(TacticalContactRequest::new, TacticalContactRequest::token);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
