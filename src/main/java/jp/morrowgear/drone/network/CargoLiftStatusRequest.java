package jp.morrowgear.drone.network;

import jp.morrowgear.drone.MorrowgearDrone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CargoLiftStatusRequest() implements CustomPacketPayload {
    public static final Type<CargoLiftStatusRequest> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(MorrowgearDrone.MOD_ID, "cargo_lift_status_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CargoLiftStatusRequest> CODEC = StreamCodec.unit(
        new CargoLiftStatusRequest());
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
