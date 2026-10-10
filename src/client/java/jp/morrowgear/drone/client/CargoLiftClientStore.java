package jp.morrowgear.drone.client;

import java.util.List;
import jp.morrowgear.drone.network.CargoLiftStatusPayload;

final class CargoLiftClientStore {
    private static List<CargoLiftStatusPayload.Row> rows = List.of();
    private CargoLiftClientStore() {}
    static void accept(CargoLiftStatusPayload payload) { rows = payload.rows(); }
    static List<CargoLiftStatusPayload.Row> rows() { return rows; }
    static void clear() { rows = List.of(); }
}
