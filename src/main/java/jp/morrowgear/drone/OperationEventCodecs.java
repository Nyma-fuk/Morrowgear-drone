package jp.morrowgear.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;

/** Shared bounded disk schema. Protocol uses the same enum names, never ordinal IDs. */
public final class OperationEventCodecs {
    private OperationEventCodecs() {}
    public static final Codec<UUID> UUID_CODEC = Codec.STRING.comapFlatMap(text -> {
        try { return DataResult.success(UUID.fromString(text)); }
        catch (IllegalArgumentException ex) { return DataResult.error(() -> "Invalid operation UUID"); }
    }, UUID::toString);
    private static final Codec<String> TEXT = text(OperationEvent.TEXT_LIMIT);
    private static final Codec<OperationEvent.Kind> KIND = TEXT.comapFlatMap(text -> {
        try { return DataResult.success(OperationEvent.Kind.valueOf(text)); }
        catch (IllegalArgumentException ex) { return DataResult.error(() -> "Unknown operation kind"); }
    }, Enum::name);
    private static final Codec<Long> TIME = Codec.LONG.validate(value -> value >= 0
            ? DataResult.success(value) : DataResult.error(() -> "Negative operation time"));
    public static final Codec<OperationEvent> EVENT = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.fieldOf("id").forGetter(OperationEvent::id),
            UUID_CODEC.fieldOf("owner").forGetter(OperationEvent::owner),
            UUID_CODEC.fieldOf("context").forGetter(OperationEvent::context),
            UUID_CODEC.fieldOf("unit").forGetter(OperationEvent::sourceUnit),
            TIME.fieldOf("tick").forGetter(OperationEvent::serverTick),
            TIME.fieldOf("time").forGetter(OperationEvent::occurredAtMillis),
            KIND.fieldOf("kind").forGetter(OperationEvent::kind),
            TEXT.fieldOf("wing").forGetter(OperationEvent::wing),
            text(OperationEvent.DIMENSION_LIMIT).fieldOf("dimension").forGetter(OperationEvent::dimension),
            TEXT.fieldOf("source").forGetter(OperationEvent::sourceName),
            TEXT.fieldOf("number").forGetter(OperationEvent::voiceNumber),
            Codec.intRange(1, OperationEvent.FLEET_LIMIT).fieldOf("count").forGetter(OperationEvent::count),
            Codec.INT.fieldOf("x").forGetter(OperationEvent::x),
            Codec.INT.fieldOf("y").forGetter(OperationEvent::y),
            Codec.INT.fieldOf("z").forGetter(OperationEvent::z)
    ).apply(instance, OperationEvent::new));

    private static Codec<String> text(int limit) {
        return Codec.STRING.validate(value -> value.length() <= limit ? DataResult.success(value)
                : DataResult.error(() -> "Operation string limit exceeded"));
    }
}
