package jp.morrowgear.drone;

import java.util.*;

/** Client observations only: loss of reception is never proof of a kill. */
public final class VisorCombatLedger {
    public static final int CAPACITY = 128;
    public record Observation(UUID target, String name, Set<String> attackers, Boolean alive, String location) {}
    public record Front(int number, UUID target, String name, Set<String> attackers, String location, boolean active) {}
    public record Event(long tick, int front, String label, String detail) {}
    private final LinkedHashMap<UUID, Front> fronts = new LinkedHashMap<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Set<UUID> confirmedDeaths = new HashSet<>();
    private int nextNumber = 1;
    public void clear() { fronts.clear(); events.clear(); confirmedDeaths.clear(); nextNumber = 1; }
    public void update(long tick, List<Observation> observations) {
        Set<UUID> seen = new HashSet<>();
        for (Observation observation : observations) {
            seen.add(observation.target());
            Front old = fronts.get(observation.target());
            boolean active = Boolean.TRUE.equals(observation.alive()) && !observation.attackers().isEmpty();
            if (old == null && !active) continue;
            if (old == null && fronts.size() >= CAPACITY) {
                UUID expired = fronts.values().stream().filter(f -> !f.active()).map(Front::target).findFirst().orElse(null);
                if (expired == null) continue;
                fronts.remove(expired); confirmedDeaths.remove(expired);
            }
            int number = old == null ? nextNumber++ : old.number();
            if (Boolean.FALSE.equals(observation.alive()) && confirmedDeaths.add(observation.target()))
                emit(tick, number, "目標撃破", observation.name() + " / 死亡確認・撃破者は未確認");
            else if (active && (old == null || !old.active())) emit(tick, number, "交戦開始", observation.name());
            else if (active && !old.attackers().containsAll(observation.attackers()))
                emit(tick, number, "増援参加", observation.attackers().size() + "機 / " + observation.name());
            else if (!active && old.active()) emit(tick, number,
                observation.alive() == null ? "追跡喪失" : observation.alive() ? "離脱" : "目標撃破",
                observation.name() + (observation.alive() == null ? " / 撃破未確認" : observation.alive() ? " / 敵残存" : " / この目標への対応終了"));
            fronts.put(observation.target(), new Front(number, observation.target(), observation.name(),
                Set.copyOf(observation.attackers()), observation.location(), active));
        }
        for (Front front : List.copyOf(fronts.values())) if (front.active() && !seen.contains(front.target())) {
            emit(tick, front.number(), "追跡喪失", front.name() + " / 最終確認 " + front.location());
            fronts.put(front.target(), new Front(front.number(), front.target(), front.name(), Set.of(), front.location(), false));
        }
        while (fronts.size() > CAPACITY) {
            UUID expired = fronts.values().stream().filter(f -> !f.active()).map(Front::target).findFirst().orElse(null);
            if (expired == null) break;
            fronts.remove(expired);
            confirmedDeaths.remove(expired);
        }
    }
    private void emit(long tick, int front, String label, String detail) {
        events.addLast(new Event(tick, front, label, detail));
        while (events.size() > CAPACITY) events.removeFirst();
    }
    public List<Front> active() { return fronts.values().stream().filter(Front::active).toList(); }
    public UUID nextActive(UUID selected) {
        List<Front> active = active();
        if (active.isEmpty()) return null;
        for (int i = 0; i < active.size(); i++) if (active.get(i).target().equals(selected))
            return active.get((i + 1) % active.size()).target();
        return active.getFirst().target();
    }
    public List<Front> page(UUID selected, int capacity) {
        if (capacity <= 0) return List.of();
        List<Front> active = active();
        int start = 0;
        for (int i = 0; i < active.size(); i++) if (active.get(i).target().equals(selected)) {
            start = i / capacity * capacity;
            break;
        }
        return List.copyOf(active.subList(start, Math.min(active.size(), start + capacity)));
    }
    public List<Event> recent(long tick, int limit) {
        List<Event> visible = events.stream().filter(e -> tick >= e.tick() && tick - e.tick() < 160).toList();
        return visible.subList(Math.max(0, visible.size() - Math.max(0, limit)), visible.size());
    }
    public int number(UUID target) { Front front = fronts.get(target); return front == null ? 0 : front.number(); }
    public List<Event> history() { return List.copyOf(events); }
}
