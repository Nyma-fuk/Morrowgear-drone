import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jp.morrowgear.drone.SupplyNetworkUiPolicy;
import jp.morrowgear.drone.SupplyNetworkUiPolicy.Configuration;
import jp.morrowgear.drone.SupplyNetworkUiPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkUiPolicy.State;

public final class SupplyNetworkUiChecks {
    private static final List<Rule> RULES = List.of(new Rule(0, 16, 100), new Rule(1, 8, 80),
        new Rule(2, 8, 70), new Rule(3, 4, 60), new Rule(4, 8, 90));
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        SupplyNetworkUiPolicy policy = new SupplyNetworkUiPolicy();
        Configuration wanted = new Configuration(1, 2, true, RULES);
        Object previous = new Object();
        policy.sent(wanted, previous, 1000);
        switch (args[0]) {
            case "numbers" -> {
                for (String invalid : new String[] {"", "-1", "1.0", " 1", "1 ", "+1", "1e2", "9999", "abc"})
                    check(SupplyNetworkUiPolicy.number(invalid, 576).isEmpty(), "Malformed number accepted: " + invalid);
                check(SupplyNetworkUiPolicy.number("576", 576).orElseThrow() == 576, "Maximum stock rejected");
                check(SupplyNetworkUiPolicy.number("577", 576).isEmpty(), "Overstock accepted");
                check(SupplyNetworkUiPolicy.number("100", 100).orElseThrow() == 100, "Maximum priority rejected");
                check(SupplyNetworkUiPolicy.number("101", 100).isEmpty(), "Excess priority accepted");
                check(SupplyNetworkUiPolicy.number("0", 576).orElseThrow() == 0, "Zero stock rejected");
            }
            case "canonical" -> {
                List<Rule> reverse = new ArrayList<>(RULES); Collections.reverse(reverse);
                check(wanted.equals(new Configuration(1, 2, true, reverse)), "Wire order should not alter configuration identity");
                try { new Configuration(1, 2, true, List.of(RULES.get(0))); throw new AssertionError("Missing rules accepted"); }
                catch (IllegalArgumentException expected) {}
                try { new Configuration(1, 2, true, Collections.nCopies(5, RULES.get(0))); throw new AssertionError("Duplicate kinds accepted"); }
                catch (IllegalArgumentException expected) {}
            }
            case "fresh_snapshot" -> {
                policy.accept(wanted, previous); check(policy.state() == State.PENDING, "Cached pre-send snapshot cannot acknowledge save");
                policy.accept(wanted, new Object()); check(policy.state() == State.CONFIRMED, "Later matching snapshot must confirm saved state");
            }
            case "target" -> {
                policy.accept(new Configuration(1, 3, true, RULES), new Object());
                check(policy.state() == State.PENDING, "Different Dock must not acknowledge save");
                policy.accept(new Configuration(3, 2, true, RULES), new Object());
                check(policy.state() == State.PENDING, "Different source must not acknowledge save");
            }
            case "changed_rules" -> {
                var rules = new ArrayList<>(RULES); rules.set(0, new Rule(0, 17, 100));
                policy.accept(new Configuration(1, 2, true, rules), new Object());
                check(policy.state() == State.PENDING, "Mismatching stock target must not acknowledge save");
                rules.set(0, new Rule(0, 16, 99)); policy.accept(new Configuration(1, 2, true, rules), new Object());
                check(policy.state() == State.PENDING, "Mismatching priority must not acknowledge save");
            }
            case "enabled" -> {
                policy.accept(new Configuration(1, 2, false, RULES), new Object());
                check(policy.state() == State.PENDING, "Disabled route must not acknowledge enabling");
            }
            case "timeout" -> {
                policy.tick(5999); check(policy.state() == State.PENDING, "Premature timeout");
                policy.tick(6000); check(policy.state() == State.UNCONFIRMED, "No server response cannot imply success");
                policy.accept(wanted, previous); check(policy.state() == State.UNCONFIRMED, "Old snapshot cannot confirm after timeout");
                policy.accept(wanted, new Object()); check(policy.state() == State.CONFIRMED, "Late matching update may confirm");
            }
            case "reset" -> {
                policy.reset(); policy.accept(wanted, new Object());
                check(policy.state() == State.IDLE, "Editing a new draft must not reuse old acknowledgement");
            }
            default -> throw new AssertionError("Unknown check");
        }
    }
}
