package jp.morrowgear.drone.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import jp.morrowgear.drone.OperationEvent;
import net.minecraft.network.codec.ByteBufCodecs;

final class OperationWire {
    private OperationWire() {}
    static UUID uuid(ByteBuf buffer) { return new UUID(buffer.readLong(), buffer.readLong()); }
    static void uuid(ByteBuf buffer, UUID value) {
        buffer.writeLong(value.getMostSignificantBits());
        buffer.writeLong(value.getLeastSignificantBits());
    }
    static String text(ByteBuf buffer, int limit) { return ByteBufCodecs.stringUtf8(limit).decode(buffer); }
    static void text(ByteBuf buffer, String value, int limit) { ByteBufCodecs.stringUtf8(limit).encode(buffer, value); }
    static long time(ByteBuf buffer) {
        long value = buffer.readLong();
        if (value < 0) throw new DecoderException("Negative operation time");
        return value;
    }
    static int count(ByteBuf buffer, int limit) {
        int count = ByteBufCodecs.VAR_INT.decode(buffer);
        if (count < 0 || count > limit) throw new DecoderException("Operation list limit exceeded");
        return count;
    }
    static OperationEvent event(ByteBuf buffer) {
        UUID id = uuid(buffer), owner = uuid(buffer), context = uuid(buffer), unit = uuid(buffer);
        long tick = time(buffer), wall = time(buffer);
        OperationEvent.Kind kind;
        try { kind = OperationEvent.Kind.valueOf(text(buffer, 32)); }
        catch (IllegalArgumentException ex) { throw new DecoderException("Unknown operation kind", ex); }
        String wing = text(buffer, OperationEvent.TEXT_LIMIT);
        String dimension = text(buffer, OperationEvent.DIMENSION_LIMIT);
        String name = text(buffer, OperationEvent.TEXT_LIMIT);
        String number = text(buffer, OperationEvent.TEXT_LIMIT);
        int count = count(buffer, OperationEvent.FLEET_LIMIT);
        if (count == 0) throw new DecoderException("Empty operation group");
        return new OperationEvent(id, owner, context, unit, tick, wall, kind, wing, dimension, name, number,
                count, buffer.readInt(), buffer.readInt(), buffer.readInt());
    }
    static void event(ByteBuf buffer, OperationEvent event) {
        uuid(buffer, event.id()); uuid(buffer, event.owner()); uuid(buffer, event.context());
        uuid(buffer, event.sourceUnit());
        buffer.writeLong(event.serverTick()); buffer.writeLong(event.occurredAtMillis());
        text(buffer, event.kind().name(), 32);
        text(buffer, event.wing(), OperationEvent.TEXT_LIMIT);
        text(buffer, event.dimension(), OperationEvent.DIMENSION_LIMIT);
        text(buffer, event.sourceName(), OperationEvent.TEXT_LIMIT);
        text(buffer, event.voiceNumber(), OperationEvent.TEXT_LIMIT);
        ByteBufCodecs.VAR_INT.encode(buffer, event.count());
        buffer.writeInt(event.x()); buffer.writeInt(event.y()); buffer.writeInt(event.z());
    }
}
