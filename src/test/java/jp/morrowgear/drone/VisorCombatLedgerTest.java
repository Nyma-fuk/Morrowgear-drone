package jp.morrowgear.drone;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class VisorCombatLedgerTest {
    @Test void selectedFrontCanBeReachedBeyondTheFirstPageWithoutRenumbering() {
        var ledger = new VisorCombatLedger();
        var observations = new ArrayList<VisorCombatLedger.Observation>();
        for (int i = 0; i < 8; i++) observations.add(new VisorCombatLedger.Observation(
            new UUID(0, i + 1), "Target", Set.of("D" + i), true, "北"));
        ledger.update(0, observations);
        UUID selected = null;
        for (int i = 1; i <= 8; i++) {
            selected = ledger.nextActive(selected);
            assertEquals(new UUID(0, i), selected);
            UUID current = selected;
            assertTrue(ledger.page(selected, 3).stream().anyMatch(f -> f.target().equals(current)));
            assertTrue(ledger.page(selected, 3).size() <= 3);
            assertEquals(i, ledger.number(selected));
        }
        assertEquals(new UUID(0, 1), ledger.nextActive(selected));
        assertTrue(ledger.page(selected, 0).isEmpty());
        ledger.update(5, List.of());
        assertNull(ledger.nextActive(selected));
        assertTrue(ledger.page(selected, 3).isEmpty());
    }
    @Test void activeFrontsAndHistoryStayBoundedAndKeepExistingNumbers() {
        var ledger = new VisorCombatLedger();
        var observations = new ArrayList<VisorCombatLedger.Observation>();
        for (int i = 0; i < 200; i++) observations.add(new VisorCombatLedger.Observation(new UUID(0, i), "Target", Set.of("D" + i), true, "北"));
        ledger.update(0, observations);
        assertEquals(128, ledger.active().size());
        assertEquals(128, ledger.history().size());
        assertEquals(1, ledger.number(new UUID(0, 0)));
        ledger.update(5, observations.reversed());
        assertEquals(128, ledger.active().size());
        assertEquals(1, ledger.number(new UUID(0, 0)));
    }
    @Test void deathCanBeConfirmedAfterReceptionWasLostButOnlyOnce() {
        var ledger = new VisorCombatLedger(); var id = UUID.randomUUID();
        ledger.update(0, List.of(new VisorCombatLedger.Observation(id, "Target", Set.of("D"), true, "北")));
        ledger.update(5, List.of(new VisorCombatLedger.Observation(id, "Target", Set.of(), null, "北")));
        assertEquals("追跡喪失", ledger.history().getLast().label());
        ledger.update(10, List.of(new VisorCombatLedger.Observation(id, "Target", Set.of(), false, "北")));
        ledger.update(15, List.of(new VisorCombatLedger.Observation(id, "Target", Set.of(), false, "北")));
        assertEquals(3, ledger.history().size());
        assertEquals("目標撃破", ledger.history().getLast().label());
        assertTrue(ledger.history().getLast().detail().contains("撃破者は未確認"));
    }
    @Test void feedHasExactEightSecondBoundaryAndHistoryIsASnapshot() {
        var ledger = new VisorCombatLedger(); var id = UUID.randomUUID();
        ledger.update(20, List.of(new VisorCombatLedger.Observation(id, "Target", Set.of("D"), true, "北")));
        var history = ledger.history();
        assertEquals(1, ledger.recent(179, 3).size());
        assertTrue(ledger.recent(180, 3).isEmpty());
        assertTrue(ledger.recent(19, 3).isEmpty());
        assertTrue(ledger.recent(20, 0).isEmpty());
        ledger.clear();
        assertEquals(1, history.size());
        assertTrue(ledger.history().isEmpty());
    }
    @Test void stableNumbersAndReinforcement() {
        var ledger=new VisorCombatLedger();var a=UUID.randomUUID();var b=UUID.randomUUID();
        ledger.update(0,List.of(new VisorCombatLedger.Observation(a,"A",Set.of("D1"),true,"北 10m"),new VisorCombatLedger.Observation(b,"B",Set.of("D2"),true,"西 20m")));
        ledger.update(1,List.of(new VisorCombatLedger.Observation(b,"B",Set.of("D2"),true,"西 15m"),new VisorCombatLedger.Observation(a,"A",Set.of("D1","D3"),true,"北 30m")));
        assertEquals(1,ledger.number(a));assertEquals(2,ledger.number(b));assertEquals("増援参加",ledger.history().getLast().label());
    }
    @Test void disappearedTargetIsNotAKillAndFeedExpiresButHistoryRemains() {
        var ledger=new VisorCombatLedger();var a=UUID.randomUUID();
        ledger.update(0,List.of(new VisorCombatLedger.Observation(a,"A",Set.of("D1"),true,"北 10m")));
        ledger.update(10,List.of());assertEquals("追跡喪失",ledger.history().getLast().label());
        assertTrue(ledger.active().isEmpty());assertTrue(ledger.recent(200,3).isEmpty());assertEquals(2,ledger.history().size());
    }
    @Test void confirmedDeathAndWithdrawalDiffer() {
        for(boolean alive:new boolean[]{true,false}){var ledger=new VisorCombatLedger();var a=UUID.randomUUID();
            ledger.update(0,List.of(new VisorCombatLedger.Observation(a,"A",Set.of("D1"),true,"北")));
            ledger.update(5,List.of(new VisorCombatLedger.Observation(a,"A",Set.of(),alive,"北")));
            assertEquals(alive?"離脱":"目標撃破",ledger.history().getLast().label());}
    }
}
