package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierExpansionBayTest {
    @BeforeAll static void bootstrap(){
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries=net.minecraft.data.registries.VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(initializer->initializer.apply());
    }
    private static ItemStack module(UUID id, UUID owner, CarrierExpansionPolicy.Type type){
        ItemStack stack=new ItemStack(Items.CHEST);
        CarrierExpansionItemData.write(stack,new CarrierExpansionItemData.Identity(id,owner,type));
        return stack;
    }

    @Test void attachedMissileMagazineConsumesPhysicalCellsWithoutLosingIdentity(){
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
        ItemStack magazine=module(id,owner,CarrierExpansionPolicy.Type.MISSILE_BATTERY);
        var cells=new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(
            CarrierMissileContainerBlockEntity.UI_SIZE,ItemStack.EMPTY));
        cells.set(0,new ItemStack(Items.ARROW)); cells.set(7,new ItemStack(Items.ARROW));
        magazine.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(cells));
        var bay=new CarrierExpansionBay(List.of(),()->{});
        assertTrue(bay.install(2,magazine)); assertEquals(2,bay.missiles());
        assertEquals(List.of(new CarrierExpansionBay.MissileCell(2,0),
            new CarrierExpansionBay.MissileCell(2,7)),bay.availableMissiles());
        assertTrue(bay.consumeMissile(new CarrierExpansionBay.MissileCell(2,7)));
        assertEquals(List.of(new CarrierExpansionBay.MissileCell(2,0)),bay.availableMissiles());
        assertEquals(1,bay.missiles());
        assertTrue(bay.consumeMissile()); assertEquals(0,bay.missiles());
        assertFalse(bay.consumeMissile());
        assertEquals(id,CarrierExpansionItemData.read(bay.get(2)).id());
    }
    @Test void physicalModulesRemainUniqueAndRoundTripThroughEightHardpoints(){
        UUID owner=UUID.randomUUID(),cargo=UUID.randomUUID(),missile=UUID.randomUUID(); var dirty=new AtomicInteger();
        var bay=new CarrierExpansionBay(List.of(),dirty::incrementAndGet);
        assertTrue(bay.install(0,module(cargo,owner,CarrierExpansionPolicy.Type.CARGO)));
        assertFalse(bay.install(7,module(missile,owner,CarrierExpansionPolicy.Type.MISSILE_BATTERY)));
        assertTrue(bay.install(6,module(missile,owner,CarrierExpansionPolicy.Type.MISSILE_BATTERY)));
        assertFalse(bay.install(0,module(UUID.randomUUID(),owner,CarrierExpansionPolicy.Type.CARGO)));
        assertEquals(1,bay.count(CarrierExpansionPolicy.Type.CARGO));
        assertEquals(1,bay.count(CarrierExpansionPolicy.Type.MISSILE_BATTERY));
        assertEquals(0,bay.slot(cargo)); assertEquals(6,bay.slot(missile)); assertEquals(2,dirty.get());
        var restored=new CarrierExpansionBay(bay.snapshot(),()->{});
        assertEquals(cargo,CarrierExpansionItemData.read(restored.remove(0)).id());
        assertEquals(missile,CarrierExpansionItemData.read(restored.remove(6)).id());
        assertEquals(0,restored.count(CarrierExpansionPolicy.Type.CARGO));
    }

    @Test void cargoCanUseEveryStationButMissilesReserveOnlyTheFourArmoredTrunks(){
        UUID owner=UUID.randomUUID(); var bay=new CarrierExpansionBay(List.of(),()->{});
        for(int slot=0;slot<8;slot++)assertTrue(CarrierExpansionBay.compatible(slot,CarrierExpansionPolicy.Type.CARGO));
        for(int slot=0;slot<8;slot++)assertEquals((slot&1)==0,
            CarrierExpansionBay.compatible(slot,CarrierExpansionPolicy.Type.MISSILE_BATTERY));
        assertEquals(0,bay.firstFree(CarrierExpansionPolicy.Type.MISSILE_BATTERY));
        assertTrue(bay.install(0,module(UUID.randomUUID(),owner,CarrierExpansionPolicy.Type.CARGO)));
        assertEquals(2,bay.firstFree(CarrierExpansionPolicy.Type.MISSILE_BATTERY));
    }

    @Test void legacyMissileContainersMoveIntoAFreeArmoredTrunkWithoutLosingIdentity(){
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
        var saved=new java.util.ArrayList<ItemStack>(java.util.Collections.nCopies(8,ItemStack.EMPTY));
        saved.set(7,module(id,owner,CarrierExpansionPolicy.Type.MISSILE_BATTERY));
        var restored=new CarrierExpansionBay(saved,()->{});
        assertEquals(0,restored.slot(id));
        assertTrue(restored.get(7).isEmpty());
    }

    @Test void cargoContentsMoveIntoAndBackOutOfTheExactExpansionSegment(){
        var modules=new AtomicInteger(1); var cargo=new CarrierCargoStorage(List.of(),()->{},modules::get);
        var incoming=java.util.Collections.nCopies(CarrierPolicy.STORAGE_SLOTS_PER_MODULE,ItemStack.EMPTY);
        incoming=new java.util.ArrayList<>(incoming); incoming.set(0,new ItemStack(Items.DIAMOND,19));
        incoming.set(CarrierPolicy.STORAGE_SLOTS_PER_MODULE-1,new ItemStack(Items.EMERALD,7));
        cargo.importModule(0,incoming);
        assertEquals(19,cargo.getItem(CarrierPolicy.BASE_STORAGE_SLOTS).getCount());
        assertEquals(7,cargo.getItem(CarrierPolicy.BASE_STORAGE_SLOTS+CarrierPolicy.STORAGE_SLOTS_PER_MODULE-1).getCount());
        var recovered=cargo.moduleContents(0); cargo.clearModule(0); modules.set(0);
        assertEquals(19,recovered.getFirst().getCount());
        assertEquals(7,recovered.getLast().getCount());
        assertEquals(CarrierPolicy.BASE_STORAGE_SLOTS,cargo.activeSlots());
        assertEquals(0,cargo.itemCount());
    }
}
