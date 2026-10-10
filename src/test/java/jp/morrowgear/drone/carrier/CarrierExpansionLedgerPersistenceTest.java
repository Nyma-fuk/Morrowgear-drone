package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierExpansionLedgerPersistenceTest {
    @Test void itemGroundLiftAndCarrierLifecycleSurvivesSavedDataRoundTrip() {
        var ledger = new CarrierExpansionLedger();
        UUID owner = UUID.randomUUID();
        var created = ledger.create(owner, CarrierExpansionPolicy.Type.CARGO, "player/slot-4", 10);

        var placed = created.module().place("minecraft:overworld", 400L);
        assertEquals(CarrierExpansionLedger.Status.APPLIED, ledger.commit(
            ledger.begin(created.module().id()).orElseThrow(), placed, "block/overworld/400", 11).status());

        UUID mission = UUID.randomUUID();
        var lifted = placed.lift(mission, "minecraft:overworld", 400L);
        assertEquals(CarrierExpansionLedger.Status.APPLIED, ledger.commit(
            ledger.begin(created.module().id()).orElseThrow(), lifted, "lift/" + mission, 12).status());

        UUID carrier = UUID.randomUUID();
        var installed = lifted.install(carrier, 3);
        assertEquals(CarrierExpansionLedger.Status.APPLIED, ledger.commit(
            ledger.begin(created.module().id()).orElseThrow(), installed, "carrier/" + carrier + "/3", 13).status());

        var json = CarrierExpansionLedger.CODEC.encodeStart(JsonOps.INSTANCE, ledger).getOrThrow();
        var restored = CarrierExpansionLedger.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(installed, restored.get(created.module().id()));
        assertEquals("carrier/" + carrier + "/3", restored.entry(created.module().id()).orElseThrow().witness());
        assertTrue(restored.quarantines().isEmpty());
    }

    @Test void duplicatePhysicalWitnessQuarantinesIdentityAndBlocksMovement() {
        var ledger = new CarrierExpansionLedger();
        var item = ledger.create(UUID.randomUUID(), CarrierExpansionPolicy.Type.CARGO, "player-a/slot-0", 20).module();

        var duplicate = ledger.claimPhysical(item, "player-b/slot-7", 21);
        assertEquals(CarrierExpansionLedger.Status.QUARANTINED, duplicate.status());
        assertTrue(ledger.isQuarantined(item.id()));
        assertTrue(ledger.begin(item.id()).isEmpty());
        assertEquals(CarrierExpansionLedger.ConflictReason.DUPLICATE_WITNESS,
            ledger.quarantine(item.id()).orElseThrow().contenders().getFirst().reason());

        var json = CarrierExpansionLedger.CODEC.encodeStart(JsonOps.INSTANCE, ledger).getOrThrow();
        var restored = CarrierExpansionLedger.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertTrue(restored.isQuarantined(item.id()));
        assertEquals("player-b/slot-7",
            restored.quarantine(item.id()).orElseThrow().contenders().getFirst().witness());
    }

    @Test void confirmedDuplicateCanBeRekeyedWithoutChangingCanonicalIdentity() {
        var ledger = new CarrierExpansionLedger();
        var canonical = ledger.create(UUID.randomUUID(), CarrierExpansionPolicy.Type.MISSILE_BATTERY,
            "player-a/slot-0", 30).module();
        ledger.claimPhysical(canonical, "player-b/slot-1", 31);

        UUID replacement = UUID.randomUUID();
        var recovered = ledger.resolveRekeyContender(canonical.id(), "player-b/slot-1", replacement,
            "operator", 32);
        assertEquals(CarrierExpansionLedger.Status.APPLIED, recovered.status());
        assertEquals(replacement, recovered.module().id());
        assertEquals(canonical.owner(), recovered.module().owner());
        assertEquals(canonical.type(), recovered.module().type());
        assertEquals(canonical, ledger.get(canonical.id()));
        assertFalse(ledger.isQuarantined(canonical.id()));
        assertNotEquals(ledger.get(canonical.id()).id(), ledger.get(replacement).id());
    }

    @Test void occupiedHardpointRejectsSecondCommitWithoutLosingItsItem() {
        var ledger = new CarrierExpansionLedger();
        UUID owner = UUID.randomUUID(), carrier = UUID.randomUUID();
        var first = ledger.create(owner, CarrierExpansionPolicy.Type.CARGO, "first-item", 40).module();
        var second = ledger.create(owner, CarrierExpansionPolicy.Type.CARGO, "second-item", 41).module();
        assertTrue(ledger.commit(ledger.begin(first.id()).orElseThrow(), first.install(carrier, 2),
            "carrier/" + carrier + "/2", 42).success());

        var rejected = ledger.commit(ledger.begin(second.id()).orElseThrow(), second.install(carrier, 2),
            "carrier/" + carrier + "/2", 43);
        assertEquals(CarrierExpansionLedger.Status.LOCATION_OCCUPIED, rejected.status());
        assertEquals(CarrierExpansionPolicy.Location.ITEM, ledger.get(second.id()).location());
        assertFalse(ledger.isQuarantined(second.id()));
    }

    @Test void staleTokenCannotOverwriteACompletedTransfer() {
        var ledger = new CarrierExpansionLedger();
        var item = ledger.create(UUID.randomUUID(), CarrierExpansionPolicy.Type.CARGO, "item", 50).module();
        var stale = ledger.begin(item.id()).orElseThrow();
        assertTrue(ledger.commit(stale, item.place("minecraft:overworld", 88L), "block/88", 51).success());

        var rejected = ledger.commit(stale, item.place("minecraft:overworld", 99L), "block/99", 52);
        assertEquals(CarrierExpansionLedger.Status.STALE, rejected.status());
        assertEquals(88L, ledger.get(item.id()).position());
    }

    @Test void quarantinedIdentityCanBeRelocatedToAFreeRecoveryItem() {
        var ledger = new CarrierExpansionLedger();
        var item = ledger.create(UUID.randomUUID(), CarrierExpansionPolicy.Type.CARGO, "first", 60).module();
        var placed = item.place("minecraft:overworld", 123L);
        assertTrue(ledger.commit(ledger.begin(item.id()).orElseThrow(), placed, "block/123", 61).success());
        ledger.claimPhysical(item.place("minecraft:the_nether", 456L), "block/nether/456", 62);

        var recovered = ledger.recoverTo(item.id(), item.moveToItem(), "recovery-tool/slot-0", "operator", 63);
        assertEquals(CarrierExpansionLedger.Status.APPLIED, recovered.status());
        assertEquals(CarrierExpansionPolicy.Location.ITEM, ledger.get(item.id()).location());
        assertFalse(ledger.isQuarantined(item.id()));
    }

    @Test void ownerOrTypeForgeryIsQuarantinedRatherThanReplacingCanonicalData() {
        var ledger = new CarrierExpansionLedger();
        var canonical = ledger.create(UUID.randomUUID(), CarrierExpansionPolicy.Type.CARGO, "owner/slot", 70).module();
        var forged = new CarrierExpansionPolicy.Module(canonical.id(), UUID.randomUUID(),
            CarrierExpansionPolicy.Type.MISSILE_BATTERY, CarrierExpansionPolicy.Location.ITEM,
            "", Long.MIN_VALUE, null, -1, null);

        assertEquals(CarrierExpansionLedger.Status.QUARANTINED,
            ledger.claimPhysical(forged, "forged/slot", 71).status());
        assertEquals(canonical.owner(), ledger.get(canonical.id()).owner());
        assertEquals(canonical.type(), ledger.get(canonical.id()).type());
        assertEquals(CarrierExpansionLedger.ConflictReason.IDENTITY_MISMATCH,
            ledger.quarantine(canonical.id()).orElseThrow().contenders().getFirst().reason());
    }

    @Test void discoveredSecondIdentityAtOneBlockIsPersistedAsARecoverableCollision() {
        var ledger = new CarrierExpansionLedger();
        UUID owner = UUID.randomUUID();
        var first = ledger.create(owner, CarrierExpansionPolicy.Type.CARGO, "first", 80).module();
        assertTrue(ledger.commit(ledger.begin(first.id()).orElseThrow(),
            first.place("minecraft:overworld", 900L), "block/900", 81).success());

        UUID secondId = UUID.randomUUID();
        var second = new CarrierExpansionPolicy.Module(secondId, owner, CarrierExpansionPolicy.Type.CARGO,
            CarrierExpansionPolicy.Location.PLACED, "minecraft:overworld", 900L, null, -1, null);
        assertEquals(CarrierExpansionLedger.Status.QUARANTINED,
            ledger.claimPhysical(second, "duplicate-block/900", 82).status());

        var json = CarrierExpansionLedger.CODEC.encodeStart(JsonOps.INSTANCE, ledger).getOrThrow();
        var restored = CarrierExpansionLedger.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertTrue(restored.isQuarantined(secondId));
        assertEquals(CarrierExpansionLedger.Status.LOCATION_OCCUPIED,
            restored.resolveKeepCanonical(secondId, "operator", 83).status());
        assertEquals(CarrierExpansionLedger.Status.APPLIED,
            restored.recoverTo(secondId, second.moveToItem(), "recovery/slot", "operator", 84).status());
        assertFalse(restored.isQuarantined(secondId));
    }

    @Test void verifiedMissingPlacedBlockReleasesOnlyItsExactPhysicalLocation() {
        var ledger=new CarrierExpansionLedger();
        UUID owner=UUID.randomUUID();
        var missing=ledger.create(owner,CarrierExpansionPolicy.Type.CARGO,"item-a",90).module();
        var retained=ledger.create(owner,CarrierExpansionPolicy.Type.CARGO,"item-b",91).module();
        assertTrue(ledger.commit(ledger.begin(missing.id()).orElseThrow(),
            missing.place("minecraft:overworld",500L),"block/500",92).success());
        assertTrue(ledger.commit(ledger.begin(retained.id()).orElseThrow(),
            retained.place("minecraft:overworld",501L),"block/501",93).success());

        var released=ledger.releaseMissingPlaced("minecraft:overworld",500L,"loaded-world-air",94);
        assertEquals(CarrierExpansionLedger.Status.APPLIED,released.status());
        assertEquals(CarrierExpansionPolicy.Location.DESTROYED,ledger.get(missing.id()).location());
        assertEquals(CarrierExpansionPolicy.Location.PLACED,ledger.get(retained.id()).location());
        assertTrue(ledger.positionAvailable("minecraft:overworld",500L,UUID.randomUUID()));
        assertFalse(ledger.positionAvailable("minecraft:overworld",501L,UUID.randomUUID()));
    }
}
