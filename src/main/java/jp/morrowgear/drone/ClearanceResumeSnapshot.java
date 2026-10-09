package jp.morrowgear.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;

/** Persist only assignment identity; route and formation data remain on the drone. */
final class ClearanceResumeSnapshot {
    private ClearanceResumeSnapshot() {}

    private static final Codec<UUID> UUID_CODEC = Codec.STRING.comapFlatMap(value -> {
        try { return DataResult.success(UUID.fromString(value)); }
        catch (IllegalArgumentException error) { return DataResult.error(() -> "Invalid clearance target UUID"); }
    }, UUID::toString);

    static final Codec<DroneTaskStack.Task> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        enumCodec(DroneTaskStack.Kind.class).fieldOf("Kind").forGetter(DroneTaskStack.Task::kind),
        enumCodec(DroneMode.class).fieldOf("Mode").forGetter(DroneTaskStack.Task::mode),
        enumCodec(SalvageState.class).fieldOf("SalvageState").forGetter(DroneTaskStack.Task::salvageState),
        UUID_CODEC.optionalFieldOf("Target").forGetter(task -> Optional.ofNullable(task.targetId())),
        Codec.STRING.fieldOf("Mission").forGetter(DroneTaskStack.Task::missionId)
    ).apply(instance, (kind, mode, salvage, target, mission) ->
        new DroneTaskStack.Task(kind, mode, salvage, target.orElse(null), mission)));

    private static <E extends Enum<E>> Codec<E> enumCodec(Class<E> type) {
        return Codec.STRING.comapFlatMap(value -> {
            try { return DataResult.success(Enum.valueOf(type, value)); }
            catch (IllegalArgumentException error) { return DataResult.error(() -> "Invalid clearance " + type.getSimpleName()); }
        }, Enum::name);
    }
}
