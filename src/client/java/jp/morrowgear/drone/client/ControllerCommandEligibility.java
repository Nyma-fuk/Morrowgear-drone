package jp.morrowgear.drone.client;

import jp.morrowgear.drone.SalvageState;

/** A received towing phase is enough to withhold a request, never proof of acceptance. */
final class ControllerCommandEligibility {
    private ControllerCommandEligibility() {}
    static boolean maySubmit(SalvageState receivedState) {
        return receivedState != null && switch (receivedState) {
            case IDLE, INTERCEPT -> true;
            case HOOK, HOIST, RETURN, DELIVER, SERVICE -> false;
        };
    }
}
