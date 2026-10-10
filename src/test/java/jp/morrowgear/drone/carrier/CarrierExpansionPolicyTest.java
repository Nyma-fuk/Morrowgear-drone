package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierExpansionPolicyTest {
    @Test void eightIndependentCargoModulesReachTheSpecifiedCapacity() {
        UUID owner = UUID.randomUUID(), carrier = UUID.randomUUID();
        List<CarrierExpansionPolicy.Module> modules = new ArrayList<>();
        for (int slot = 0; slot < 8; slot++) modules.add(new CarrierExpansionPolicy.Module(
            UUID.randomUUID(), owner, CarrierExpansionPolicy.Type.CARGO, CarrierExpansionPolicy.Location.ITEM,
            "", Long.MIN_VALUE, null, -1, null).install(carrier, slot));
        assertEquals(8, CarrierExpansionPolicy.cargoModuleCount(modules));
        assertEquals(17_280, CarrierExpansionPolicy.cargoCapacity(modules));
        assertEquals(8, modules.stream().map(CarrierExpansionPolicy.Module::id).distinct().count());
    }

    @Test void missileModuleUsesBayButNeverAddsCargoCapacity() {
        UUID owner = UUID.randomUUID(), carrier = UUID.randomUUID();
        var missile = new CarrierExpansionPolicy.Module(UUID.randomUUID(), owner,
            CarrierExpansionPolicy.Type.MISSILE_BATTERY, CarrierExpansionPolicy.Location.ITEM,
            "", Long.MIN_VALUE, null, -1, null).install(carrier, 0);
        assertEquals(CarrierPolicy.BASE_STORAGE_SLOTS, CarrierExpansionPolicy.cargoCapacity(List.of(missile)));
    }

    @Test void identitySurvivesCarrierGroundLiftAndItemTransitions() {
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        var item = new CarrierExpansionPolicy.Module(id, owner, CarrierExpansionPolicy.Type.CARGO,
            CarrierExpansionPolicy.Location.ITEM, "", Long.MIN_VALUE, null, -1, null);
        var result = item.install(UUID.randomUUID(), 3).place("minecraft:overworld", 42L)
            .lift(UUID.randomUUID(), "minecraft:overworld", 42L).moveToItem();
        assertEquals(id, result.id());
        assertEquals(CarrierExpansionPolicy.Location.ITEM, result.location());
    }
}
