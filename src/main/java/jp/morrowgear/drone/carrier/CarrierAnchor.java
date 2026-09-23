package jp.morrowgear.drone.carrier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public record CarrierAnchor(String dimension, int x, int y, int z) {
    public static final Codec<CarrierAnchor> CODEC = RecordCodecBuilder.create(i -> i.group(
        Identifier.CODEC.xmap(Identifier::toString, Identifier::parse).fieldOf("dimension").forGetter(CarrierAnchor::dimension),
        Codec.intRange(-29_999_984, 29_999_984).fieldOf("x").forGetter(CarrierAnchor::x),
        Codec.intRange(-2048, 2047).fieldOf("y").forGetter(CarrierAnchor::y),
        Codec.intRange(-29_999_984, 29_999_984).fieldOf("z").forGetter(CarrierAnchor::z)
    ).apply(i, CarrierAnchor::new));

    public static CarrierAnchor at(Entity entity) {
        return new CarrierAnchor(entity.level().dimension().identifier().toString(),
            entity.blockPosition().getX(), entity.blockPosition().getY(), entity.blockPosition().getZ());
    }
    public BlockPos pos() { return new BlockPos(x, y, z); }
    public ServerLevel level(MinecraftServer server) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
    }
}
