package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.MorrowgearDrone;
import jp.morrowgear.drone.OperationEvent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record OperationEventsPayload(UUID session, UUID owner, long serverTick, boolean snapshot,
        List<OperationEvent> events) implements CustomPacketPayload {
    public OperationEventsPayload {
        events = List.copyOf(events);
        if (serverTick < 0 || events.size() > OperationEvent.HISTORY_LIMIT
                || events.stream().anyMatch(event -> !event.owner().equals(owner))) {
            throw new IllegalArgumentException("Invalid operation history payload");
        }
    }
    public static final Type<OperationEventsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "operation_events"));
    public static final StreamCodec<ByteBuf, OperationEventsPayload> CODEC = new StreamCodec<>() {
        @Override public OperationEventsPayload decode(ByteBuf buffer) {
            UUID session = OperationWire.uuid(buffer), owner = OperationWire.uuid(buffer);
            long tick = OperationWire.time(buffer);
            boolean snapshot = buffer.readBoolean();
            int count = OperationWire.count(buffer, OperationEvent.HISTORY_LIMIT);
            List<OperationEvent> events = new ArrayList<>(count);
            for (int i = 0; i < count; i++) events.add(OperationWire.event(buffer));
            return new OperationEventsPayload(session, owner, tick, snapshot, events);
        }
        @Override public void encode(ByteBuf buffer, OperationEventsPayload value) {
            OperationWire.uuid(buffer, value.session); OperationWire.uuid(buffer, value.owner);
            buffer.writeLong(value.serverTick); buffer.writeBoolean(value.snapshot);
            ByteBufCodecs.VAR_INT.encode(buffer, value.events.size());
            value.events.forEach(event -> OperationWire.event(buffer, event));
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
