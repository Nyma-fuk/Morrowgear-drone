package jp.morrowgear.drone.carrier;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

/** Fixed-size opening data; inventory contents continue to use vanilla slot synchronization. */
public record CarrierCargoContainerPayload(long position, int page) {
    public CarrierCargoContainerPayload {
        if (page < 0 || page >= CarrierCargoContainerMenu.PAGES)
            throw new IllegalArgumentException("Invalid cargo page");
    }
    public static final StreamCodec<ByteBuf, CarrierCargoContainerPayload> CODEC = new StreamCodec<>() {
        @Override public CarrierCargoContainerPayload decode(ByteBuf buffer) {
            return new CarrierCargoContainerPayload(buffer.readLong(), buffer.readUnsignedByte());
        }
        @Override public void encode(ByteBuf buffer, CarrierCargoContainerPayload payload) {
            buffer.writeLong(payload.position()).writeByte(payload.page());
        }
    };

    public BlockPos blockPos() { return BlockPos.of(position); }
}
