package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** No owner field: authorization is exclusively against the authenticated connection sender. */
public record OperationCueRequestPayload(UUID session, UUID eventId) implements CustomPacketPayload {
    public static final Type<OperationCueRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "operation_cue_request"));
    public static final StreamCodec<ByteBuf, OperationCueRequestPayload> CODEC = new StreamCodec<>() {
        @Override public OperationCueRequestPayload decode(ByteBuf buffer) {
            return new OperationCueRequestPayload(OperationWire.uuid(buffer), OperationWire.uuid(buffer));
        }
        @Override public void encode(ByteBuf buffer, OperationCueRequestPayload value) {
            OperationWire.uuid(buffer, value.session); OperationWire.uuid(buffer, value.eventId);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
