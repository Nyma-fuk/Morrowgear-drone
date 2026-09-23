import java.util.UUID;
import jp.morrowgear.drone.CarrierUiPolicy;
import jp.morrowgear.drone.CarrierUiPolicy.Snapshot;

public class CarrierUiPolicyChecks {
    private static final UUID SHIP = new UUID(0, 1), OLD = new UUID(0, 2), NEW = new UUID(0, 3);
    private static Snapshot snapshot(UUID generation, int ticks, boolean owner, int mode, int minY) {
        return new Snapshot(SHIP, owner, false, mode, false, generation, "test:world", -1, 0, minY, 94, 1, ticks);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static CarrierUiPolicy confirm() {
        CarrierUiPolicy p = new CarrierUiPolicy();
        p.accept(snapshot(OLD, 0, true, 0, -64), 1000);
        p.select(-1, 0, "test:world"); p.request(1, 1000);
        p.accept(snapshot(NEW, 200, true, 0, -64), 1010);
        return p;
    }
    public static void main(String[] args) {
        CarrierUiPolicy p = confirm();
        switch (args[0]) {
            case "consent" -> {
                check(!p.canStart(1010), "Opening confirmation must not consent");
                p.consent(true); check(p.canStart(1010), "Explicit consent must enable matching fresh range");
                p.sent(1020); check(!p.canStart(1021), "Second start must be suppressed");
            }
            case "expiry" -> {
                p.consent(true); p.accept(snapshot(NEW, 1, true, 0, -64), 1020);
                p.tick(1070); check(p.expired() && !p.canStart(1070), "Expired preview must reconfirm");
            }
            case "heartbeat" -> {
                p.consent(true); p.tick(2600);
                check(p.expired() && !p.canStart(2600), "Missing heartbeat must fail closed");
            }
            case "generation" -> {
                p.consent(true); p.accept(snapshot(OLD, 200, true, 0, -64), 1100);
                check(p.expired() && !p.canStart(1100), "Different generation must revoke consent");
            }
            case "range" -> {
                p.consent(true); p.accept(snapshot(NEW, 190, true, 0, 0), 1100);
                check(p.expired() && !p.canStart(1100), "Height change in same generation must revoke consent");
            }
            case "guest" -> {
                p.consent(true); p.accept(snapshot(NEW, 190, false, 0, -64), 1100);
                check(!p.canStart(1100), "Ownership loss must reject start");
            }
            case "cancel" -> {
                p.cancel(); p.accept(snapshot(NEW, 190, true, 0, -64), 1100); p.consent(true);
                check(!p.canStart(1100), "Cancelled snapshot cannot revive confirmation");
            }
            case "mode" -> {
                p.consent(true); p.accept(snapshot(NEW, 190, true, 2, -64), 1100);
                check(!p.canStart(1100), "Attack cannot coexist with mining activation");
            }
            case "saved" -> {
                p.cancel(); p.accept(snapshot(OLD, 0, true, 0, 0), 1200); p.requestSaved(1, 1200);
                p.accept(snapshot(OLD, 200, true, 0, 0), 1250);
                check(p.phase() == CarrierUiPolicy.Phase.CONFIRM, "Saved range retains server generation");
                check(!p.canStart(1250), "Saved range must require fresh consent");
            }
            case "negative" -> {
                check(CarrierUiPolicy.chunkAt(49, 0, 100, 0, 1) == -1, "Negative world coordinate floors");
                check(CarrierUiPolicy.chunkAt(34, 0, 100, 0, 1) == -1, "Negative boundary stays in chunk");
                check(CarrierUiPolicy.chunkAt(33, 0, 100, 0, 1) == -2, "Next negative chunk");
            }
            case "slots" -> {
                check(!CarrierUiPolicy.inventoryVisible(false, true, 720), "Hidden cargo must reject slots");
                check(!CarrierUiPolicy.inventoryVisible(true, false, 720), "Supply details must hide slots");
                check(CarrierUiPolicy.inventoryVisible(true, true, 180), "Small GUI must provide inventory subsets");
                for (int i = 0; i < 95; i++) {
                    check(CarrierUiPolicy.slotVisible(i, 240, false), "GUI240 must show every native slot");
                    check(CarrierUiPolicy.slotVisible(i, 180, false) == (i < 59), "Cargo subset must hide player slots");
                    check(CarrierUiPolicy.slotVisible(i, 180, true) == (i >= 54), "Player subset must hide cargo slots");
                }
                check(!CarrierUiPolicy.slotVisible(95, 240, false), "Invalid slot ID must remain hidden");
                check(!CarrierUiPolicy.slotVisible(-1, 240, false), "Negative slot ID must remain hidden");
            }
            case "slot_bounds" -> {
                for (int height : new int[] {166, 180, 229, 230, 240, 300, 314, 360, 720})
                    for (boolean playerPage : new boolean[] {false, true})
                        for (int i = 0; i < 95; i++) {
                            if (!CarrierUiPolicy.slotVisible(i, height, playerPage)) continue;
                            int y = i < 54 ? 18 + i / 9 * 18 : i < 59 ? 126
                                : i < 86 ? 150 + (i - 59) / 9 * 18 : 208;
                            int top = CarrierUiPolicy.inventoryTop(height, playerPage) + y;
                            check(top - 1 >= 0 && top + 17 <= height, "Native slot border clips at height " + height + " slot " + i);
                        }
            }
            case "subset_coverage" -> {
                for (int i = 0; i < 95; i++) {
                    boolean cargo = CarrierUiPolicy.slotVisible(i, 180, false);
                    boolean player = CarrierUiPolicy.slotVisible(i, 180, true);
                    check(cargo || player, "A native slot is inaccessible in both small pages");
                    check((cargo && player) == (i >= 54 && i < 59), "Only supply slots should repeat between pages");
                }
                check(CarrierUiPolicy.inventoryTop(240, false) == CarrierUiPolicy.inventoryTop(240, true),
                    "Resizing to GUI240 must restore the full native origin regardless of previous page");
            }
            case "layout" -> {
                for (int[] size : new int[][] {{320,180},{320,240},{640,360},{1280,720}})
                    for (boolean confirmation : new boolean[] {false,true}) {
                        var r = CarrierUiPolicy.mapRect(size[0], size[1], confirmation);
                        check(r.x() >= 0 && r.y() >= 0 && r.right() <= size[0] && r.bottom() < size[1] - 24, "Map overlaps fixed controls");
                        check(r.width() >= 90 && r.height() >= 40, "Map becomes unreadable");
                    }
            }
            default -> throw new AssertionError("Unknown test");
        }
    }
}
