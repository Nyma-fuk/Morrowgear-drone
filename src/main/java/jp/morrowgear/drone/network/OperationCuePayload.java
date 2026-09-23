package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.OperationEvent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Null event is an explicit stale/unauthorized response, not a new event. */
public record OperationCuePayload(UUID session, UUID eventId, OperationEvent event) implements CustomPacketPayload {
    public OperationCuePayload {
        if (event != null && !eventId.equals(event.id())) throw new IllegalArgumentException("Mismatched operation cue");
    }
    public static final Type<OperationCuePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "operation_cue"));
    public static final StreamCodec<ByteBuf, OperationCuePayload> CODEC = new StreamCodec<>() {
        @Override public OperationCuePayload decode(ByteBuf buffer) {
            UUID session = OperationWire.uuid(buffer), id = OperationWire.uuid(buffer);
            return new OperationCuePayload(session, id, buffer.readBoolean() ? OperationWire.event(buffer) : null);
        }
        @Override public void encode(ByteBuf buffer, OperationCuePayload value) {
            OperationWire.uuid(buffer, value.session); OperationWire.uuid(buffer, value.eventId);
            buffer.writeBoolean(value.event != null);
            if (value.event != null) OperationWire.event(buffer, value.event);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
