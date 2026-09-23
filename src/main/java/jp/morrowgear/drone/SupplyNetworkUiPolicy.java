package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

/** A configuration is confirmed only by a later matching server snapshot. */
public final class SupplyNetworkUiPolicy {
    public enum State { IDLE, PENDING, CONFIRMED, UNCONFIRMED }
    public record Rule(int kind, int minimum, int priority) {
        public Rule {
            if (kind < 0 || kind >= 5 || minimum < 0 || minimum > 576 || priority < 0 || priority > 100)
                throw new IllegalArgumentException("Invalid supply UI rule");
        }
    }
    public record Configuration(long source, long dock, boolean enabled, List<Rule> rules) {
        public Configuration {
            rules = rules.stream().sorted(Comparator.comparingInt(Rule::kind)).toList();
            if (rules.size() != 5 || rules.stream().map(Rule::kind).distinct().count() != 5)
                throw new IllegalArgumentException("Five unique supply rules are required");
        }
    }
    private State state = State.IDLE;
    private Configuration pending;
    private Object previous;
    private long sentAt;

    public State state() { return state; }
    public void sent(Configuration configuration, Object previousSnapshot, long now) {
        pending = configuration; previous = previousSnapshot; sentAt = now; state = State.PENDING;
    }
    public void accept(Configuration configuration, Object snapshot) {
        if ((state == State.PENDING || state == State.UNCONFIRMED) && snapshot != null && snapshot != previous
            && pending.equals(configuration)) state = State.CONFIRMED;
    }
    public void tick(long now) {
        if (state == State.PENDING && now - sentAt >= 5000) state = State.UNCONFIRMED;
    }
    public void reset() { state = State.IDLE; pending = null; previous = null; }
    public static OptionalInt number(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > 3) return OptionalInt.empty();
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return OptionalInt.empty();
        int number = Integer.parseInt(value);
        return number <= maximum ? OptionalInt.of(number) : OptionalInt.empty();
    }
}
