package jp.morrowgear.drone.client;

import jp.morrowgear.drone.DroneRole;
import jp.morrowgear.drone.CombatState;
import jp.morrowgear.drone.CombatWeapon;
import jp.morrowgear.drone.SalvageState;
import java.util.UUID;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;

public final class DroneRenderState extends EntityRenderState {
	public boolean docked;
	public boolean powerLost;
	public float gearDeployment;
	public float equipmentDeployment;
	public float heading;
	public float flightPitch;
	public float flightRoll;
	public DroneRole role = DroneRole.FIELD;
	public UUID entityId;
	public CombatState combatState = CombatState.IDLE;
	public CombatWeapon combatWeapon = CombatWeapon.NONE;
	public int combatCharge;
	public int combatShotAge = 1000;
	public long combatStateAge;
	public Vec3 combatTargetOffset;
	public SalvageState salvageState = SalvageState.IDLE;
	public Vec3 salvageTargetOffset;
}
