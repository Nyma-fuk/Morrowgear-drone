package jp.morrowgear.drone.carrier.client;

import jp.morrowgear.drone.carrier.CarrierCommandPayload;
import jp.morrowgear.drone.carrier.CarrierMenu;
import jp.morrowgear.drone.carrier.CarrierViewPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** No renderer or visual assets. Parent installs its approved screen and renderer separately. */
public final class CarrierClientApi {
    private static boolean registered;
    private CarrierClientApi() {}
    public static void register() {
        if (registered) return;
        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(CarrierViewPayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (context.player().containerMenu instanceof CarrierMenu menu) menu.acceptView(payload);
            }));
    }
    public static void send(CarrierCommandPayload command) {
        if (ClientPlayNetworking.canSend(CarrierCommandPayload.TYPE)) ClientPlayNetworking.send(command);
    }
}
