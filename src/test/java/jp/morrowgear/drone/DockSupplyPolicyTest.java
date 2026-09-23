package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

final class DockSupplyPolicyTest {
	@Test void onlyDedicatedWeaponItemsAreSupplies() {
		assertEquals(SupplyKind.GUN, DockSupplyPolicy.kind("morrowgear_drone:autocannon_magazine"));
		assertEquals(SupplyKind.LASER, DockSupplyPolicy.kind("morrowgear_drone:laser_cell"));
		assertEquals(SupplyKind.MISSILE, DockSupplyPolicy.kind("morrowgear_drone:micro_missile_pack"));
		for (String id : new String[]{"minecraft:iron_nugget", "minecraft:firework_rocket", "other:laser_cell", "", null})
			assertNull(DockSupplyPolicy.kind(id));
		for (String id : new String[]{"minecraft:coal", "minecraft:charcoal", "morrowgear_drone:power_cell",
			"morrowgear_drone:standard_battery_pack", "morrowgear_drone:reinforced_battery_pack", "morrowgear_drone:high_density_battery_pack"})
			assertEquals(SupplyKind.FUEL, DockSupplyPolicy.kind(id));
	}

	@Test void packCreditsConserveEveryUnitAcrossRandomPartialRequestsAndExhaustion() {
		Random random = new Random(719242);
		for (int units : new int[]{120, 1000, 5}) {
			int credit = 0, consumed = 0, supplied = 0;
			for (int i = 0; i < 20000; i++) {
				int request = random.nextInt(units * 2 + 2) - 1;
				boolean available = random.nextBoolean();
				var transfer = DockSupplyPolicy.takePack(credit, request, units, available);
				assertTrue(transfer.supplied() >= 0 && transfer.supplied() <= Math.max(0, request));
				assertTrue(transfer.credit() >= 0 && transfer.credit() < units);
				if (transfer.consumeItem()) { assertTrue(available && credit == 0 && request > 0); consumed++; }
				credit = transfer.credit();
				supplied += transfer.supplied();
				assertEquals(consumed * units, supplied + credit);
			}
		}
	}

	@Test void nearFullDroneOpensOnePackAndPreservesTheRestForAnotherDrone() {
		for (int units : new int[]{120, 1000, 5}) {
			var first = DockSupplyPolicy.takePack(0, 1, units, true);
			assertEquals(new DockSupplyPolicy.PackTransfer(1, units - 1, true), first);
			assertEquals(new DockSupplyPolicy.PackTransfer(units - 1, 0, false),
				DockSupplyPolicy.takePack(first.credit(), Integer.MAX_VALUE, units, true));
			assertEquals(new DockSupplyPolicy.PackTransfer(0, 0, false),
				DockSupplyPolicy.takePack(0, Integer.MAX_VALUE, units, false));
			assertFalse(DockSupplyPolicy.takePack(0, 0, units, true).consumeItem());
			assertFalse(DockSupplyPolicy.takePack(0, Integer.MIN_VALUE, units, true).consumeItem());
		}
	}

	@Test void invalidCreditsFailInsteadOfCreatingEnergy() {
		assertThrows(IllegalArgumentException.class, () -> DockSupplyPolicy.takePack(-1, 1, 5, true));
		assertThrows(IllegalArgumentException.class, () -> DockSupplyPolicy.takePack(5, 1, 5, true));
		assertThrows(IllegalArgumentException.class, () -> DockSupplyPolicy.takePack(0, 1, 0, true));
	}

	@Test void capacityDowngradeAndMalformedSavesAreBoundedWithoutLosingValidReserve() {
		assertEquals(new DockSupplyPolicy.FlightReserve(4000, 16000), DockSupplyPolicy.normalizeFlight(18000, 2000, 4000));
		assertEquals(new DockSupplyPolicy.FlightReserve(0, 0), DockSupplyPolicy.normalizeFlight(-1, -10, 4000));
		assertEquals(new DockSupplyPolicy.FlightReserve(4000, 114000),
			DockSupplyPolicy.normalizeFlight(Integer.MAX_VALUE, Integer.MAX_VALUE, 4000));
		var downgraded = DockSupplyPolicy.normalizeFlight(18000, 100000, 4000);
		assertEquals(downgraded, DockSupplyPolicy.normalizeFlight(downgraded.stored(), downgraded.credit(), 4000));
	}

	@Test void flightFuelCannotHideWeaponExhaustionOrViceVersa() {
		assertTrue(DockSupplyPolicy.completionResourceExhausted(false, true, true, false, false, true));
		assertTrue(DockSupplyPolicy.completionResourceExhausted(true, false, false, true, false, true));
		assertFalse(DockSupplyPolicy.completionResourceExhausted(false, false, false, false, false, false));
		assertFalse(DockSupplyPolicy.completionResourceExhausted(true, true, true, true, true, true));
		assertTrue(DockSupplyPolicy.completionResourceExhausted(false, true, false, true, true, false));
	}

	@Test void slotMapProtectsRecoveryAndInstalledEquipment() {
		String[] items = {"morrowgear_drone:field_drone_unit", "morrowgear_drone:standard_battery_pack",
			"morrowgear_drone:scout_module", "morrowgear_drone:laser_module", "minecraft:coal", "minecraft:iron_ingot",
			"morrowgear_drone:autocannon_magazine", "morrowgear_drone:laser_cell", "morrowgear_drone:micro_missile_pack"};
		for (int slot = -1; slot <= 27; slot++) {
			for (String item : items) {
				if (slot < 0 || slot >= 27 || slot == 7 || slot >= 9 && slot <= 17)
					assertFalse(DockSupplyPolicy.mayPlace(slot, item), slot + " " + item);
			}
		}
		assertTrue(DockSupplyPolicy.mayPlace(0, items[0]));
		assertTrue(DockSupplyPolicy.mayPlace(1, items[1]));
		assertTrue(DockSupplyPolicy.mayPlace(8, items[1]));
		assertTrue(DockSupplyPolicy.mayPlace(2, items[2]));
		assertTrue(DockSupplyPolicy.mayPlace(3, items[3]));
		assertTrue(DockSupplyPolicy.mayPlace(4, items[4]));
		assertTrue(DockSupplyPolicy.mayPlace(5, items[5]));
		for (int i = 6; i < items.length; i++) assertTrue(DockSupplyPolicy.mayPlace(6, items[i]));
		assertFalse(DockSupplyPolicy.mayPlace(4, items[7]));
		assertFalse(DockSupplyPolicy.mayPlace(6, items[4]));
		assertFalse(DockSupplyPolicy.isServiceSlot(1, SupplyKind.FUEL));
		assertFalse(DockSupplyPolicy.isServiceSlot(8, SupplyKind.FUEL));
		for (SupplyKind kind : SupplyKind.values()) {
			assertTrue(DockSupplyPolicy.isServiceSlot(DockSupplyPolicy.inputSlot(kind), kind));
			for (int slot = 18; slot < 27; slot++) assertTrue(DockSupplyPolicy.isServiceSlot(slot, kind));
		}
	}

	@Test void supplyKindWireValuesAreStable() {
		assertArrayEquals(new SupplyKind[]{SupplyKind.FUEL, SupplyKind.GUN, SupplyKind.LASER, SupplyKind.MISSILE, SupplyKind.REPAIR}, SupplyKind.values());
		for (SupplyKind kind : SupplyKind.values()) assertEquals(1 << kind.index(), kind.mask());
	}

	@Test void partialPackSaveReloadPreservesCreditsAndLegacyStartsWithNoWeaponGrants() {
		var credit = new DockSupplyPolicy.PackCredits(999, 119, 4);
		for (int reload = 0; reload < 100; reload++) {
			TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
			credit.write(output);
			credit = readCredits(output.buildResult());
			assertEquals(new DockSupplyPolicy.PackCredits(999, 119, 4), credit);
		}
		assertEquals(new DockSupplyPolicy.PackCredits(0, 0, 0), readCredits(new CompoundTag()));
		CompoundTag malformed = new CompoundTag();
		malformed.putInt("WeaponCredit", Integer.MAX_VALUE);
		malformed.putInt("AutocannonCredit", -1);
		malformed.putInt("MissileCredit", Integer.MAX_VALUE);
		assertEquals(new DockSupplyPolicy.PackCredits(999, 0, 4), readCredits(malformed));
		malformed.putString("WeaponCredit", "invalid");
		assertEquals(0, readCredits(malformed).weapon());
	}

	@Test void everyPartialPackRemainderSurvivesSaveReloadThenDrainsExactly() {
		for (int used = 1; used <= 1000; used++) {
			var transfer = DockSupplyPolicy.takePack(0, used, 1000, true);
			var credits = new DockSupplyPolicy.PackCredits(transfer.credit(), 119, 4);
			TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
			credits.write(output);
			var restored = readCredits(output.buildResult());
			assertEquals(1000, transfer.supplied() + DockSupplyPolicy.takePack(restored.weapon(), 1000, 1000, false).supplied());
			assertEquals(119, DockSupplyPolicy.takePack(restored.gun(), 120, 120, false).supplied());
			assertEquals(4, DockSupplyPolicy.takePack(restored.missile(), 5, 5, false).supplied());
		}
	}

	private static DockSupplyPolicy.PackCredits readCredits(CompoundTag tag) {
		return DockSupplyPolicy.PackCredits.read(TagValueInput.create(ProblemReporter.DISCARDING,
			HolderLookup.Provider.create(Stream.empty()), tag));
	}
}
