package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class WeaponModulePolicyTest {
	@Test
	void everyInstalledPhysicalModuleCanEnterItsWeaponState() {
		assertEquals(CombatWeapon.AUTOCANNON, CombatPolicy.weaponFor(SecurityLoadout.AUTOCANNON,
			0, 1, 1.0f, 20.0f, CombatPolicy.GUN_CAPACITY, CombatPolicy.MISSILE_CAPACITY, 0));
		assertEquals(CombatWeapon.LASER, CombatPolicy.weaponFor(SecurityLoadout.LASER,
			0, 1, 1.0f, 20.0f, CombatPolicy.GUN_CAPACITY, CombatPolicy.MISSILE_CAPACITY, 0));
		assertEquals(CombatWeapon.MISSILE, CombatPolicy.weaponFor(SecurityLoadout.MISSILE,
			0, 1, 1.0f, 20.0f, CombatPolicy.GUN_CAPACITY, CombatPolicy.MISSILE_CAPACITY, 0));
	}

	@Test void survivalRequiresADockedSecurityAircraftAndThePhysicalModule() {
		assertEquals(WeaponModulePolicy.Result.NOT_SECURITY,
			WeaponModulePolicy.evaluate(false, true, false, true, SecurityLoadout.AUTO, SecurityLoadout.AUTOCANNON));
		assertEquals(WeaponModulePolicy.Result.NOT_DOCKED,
			WeaponModulePolicy.evaluate(true, false, false, true, SecurityLoadout.AUTO, SecurityLoadout.AUTOCANNON));
		assertEquals(WeaponModulePolicy.Result.MODULE_MISSING,
			WeaponModulePolicy.evaluate(true, true, false, false, SecurityLoadout.AUTO, SecurityLoadout.LASER));
		assertEquals(WeaponModulePolicy.Result.ALLOWED,
			WeaponModulePolicy.evaluate(true, true, false, true, SecurityLoadout.AUTO, SecurityLoadout.MISSILE));
	}

	@Test void autoRemainsALegacyStateRatherThanAFreeUniversalModule() {
		assertEquals(WeaponModulePolicy.Result.PHYSICAL_MODULE_REQUIRED,
			WeaponModulePolicy.evaluate(true, true, false, true, SecurityLoadout.LASER, SecurityLoadout.AUTO));
		assertEquals(WeaponModulePolicy.Result.ALLOWED,
			WeaponModulePolicy.evaluate(true, true, true, false, SecurityLoadout.AUTO, SecurityLoadout.LASER));
	}
}
