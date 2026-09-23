package jp.morrowgear.drone;

import java.util.Arrays;
import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class CombatPolicy {
	static final int FLARE_ENTRY_TICKS = 18;
	static final int LASER_BASE_CHARGE_TICKS = 64;
	static final float LASER_BASE_PULSE_DAMAGE = 1.35f;
	static final float LASER_SYNC_GAIN_PER_ADDITIONAL_UNIT = 0.34f;
	static final int GUN_CAPACITY = 240;
	static final int AUTOCANNON_FIRE_INTERVAL_TICKS = 2;
	static final int MISSILE_CAPACITY = 45;
	static final int LASER_SWITCH_HEAT = 900;
	static final int LASER_FIRE_HEAT_PER_TICK = 7;
	static final int LASER_FIRE_POWER_PER_TICK = 3;
	static final int LASER_SYNC_WINDOW_TICKS = 24;
	static final int LASER_RELEASE_TIMEOUT_TICKS = 60;
	static final double LASER_FORMATION_MAX_GAP_ERROR = 0.38;
	static final int AUTO_WEAPON_RESERVE = 15;
	static final int URGENT_SORTIE_POWER = 65;
	static final double COMMITTED_TRACKING_RANGE = 128.0;
	static final double CAS_PATH_SPEED = 0.90;
	static final long CAS_ACCELERATION_TICKS = 36L;
	static final double CAS_TRAIL_DISTANCE = 6.0;
	static final double CAS_LONGITUDINAL_RADIUS = 52.0;
	static final double CAS_LATERAL_RADIUS = 11.5;
	static final double CAS_LANE_SPACING = 3.4;
	static final double CAS_BREAKAWAY_EXIT_DISTANCE = 17.0;
	static final double CAS_ATTACK_HEIGHT = 12.0;
	static final double LASER_INGRESS_HEIGHT = 20.0;
	static final double LASER_INGRESS_RADIUS = 18.0;
	static final double LASER_INGRESS_DISTANCE = 26.0;
	private static final int CAS_ARC_SAMPLES = 1024;
	private static final double[] CAS_ARC_LENGTHS = buildCasArcLengths();
	private static final double CAS_ARC_TOTAL = CAS_ARC_LENGTHS[CAS_ARC_SAMPLES];

	private CombatPolicy() {}

	static boolean refreshCommittedContact(CombatState state, double distanceSquared, boolean lineOfSight) {
		return state != null && state.active() && lineOfSight && distanceSquared >= 0.0
			&& distanceSquared <= COMMITTED_TRACKING_RANGE * COMMITTED_TRACKING_RANGE;
	}

	static int requiredAttackers(int contacts, float targetHealth, int available) {
		if (contacts <= 0 || available <= 0) return 0;
		int durability = Math.max(0, (int)Math.ceil(targetHealth / 20.0f) - 1);
		return Math.min(available, Math.min(8, 1 + Math.min(3, contacts - 1) + durability));
	}

	static float laserPulseDamage(int synchronizedUnits) {
		int count = Mth.clamp(synchronizedUnits, 1, 8);
		return LASER_BASE_PULSE_DAMAGE * (1.0f
			+ LASER_SYNC_GAIN_PER_ADDITIONAL_UNIT * (count - 1));
	}

	static CombatWeapon weaponFor(int slot, int count, float ownerHealthRatio,
		float targetHealth, int gunAmmo, int missiles, int laserHeat) {
		int pattern = Math.floorMod(slot, Math.max(1, Math.min(4, count)));
		if (pattern == 1 && laserHeat < 650) return CombatWeapon.LASER;
		if (pattern == 2 && missiles > 0 && targetHealth >= 12.0f) return CombatWeapon.MISSILE;
		if (gunAmmo > 0) return CombatWeapon.AUTOCANNON;
		if (laserHeat < 650) return CombatWeapon.LASER;
		if (missiles > 0) return CombatWeapon.MISSILE;
		return CombatWeapon.NONE;
	}

	static CombatWeapon weaponFor(SecurityLoadout loadout, int slot, int count,
		float ownerHealthRatio, float targetHealth, int gunAmmo, int missiles, int laserHeat) {
		if (loadout == null || loadout == SecurityLoadout.AUTO) {
			return weaponFor(slot, count, ownerHealthRatio, targetHealth, gunAmmo, missiles, laserHeat);
		}
		return switch (loadout) {
			case UNARMED -> CombatWeapon.NONE;
			case AUTOCANNON -> gunAmmo > 0 ? CombatWeapon.AUTOCANNON : CombatWeapon.NONE;
			case LASER -> laserHeat < LASER_SWITCH_HEAT ? CombatWeapon.LASER : CombatWeapon.NONE;
			case MISSILE -> missiles > 0 ? CombatWeapon.MISSILE : CombatWeapon.NONE;
			case AUTO -> throw new IllegalStateException("AUTO handled above");
		};
	}

	static boolean hasUsableWeapon(SecurityLoadout loadout, int gunAmmo, int missiles, int laserHeat) {
		SecurityLoadout selected = loadout == null ? SecurityLoadout.AUTO : loadout;
		return switch (selected) {
			case UNARMED -> false;
			case AUTO -> gunAmmo > 0 || missiles > 0 || laserHeat < LASER_SWITCH_HEAT;
			case AUTOCANNON -> gunAmmo > 0;
			case LASER -> laserHeat < LASER_SWITCH_HEAT;
			case MISSILE -> missiles > 0;
		};
	}

	static int flightReserveForDock(double distance) {
		return DroneServicePolicy.flightReserveForDock(distance);
	}

	static boolean needsWeaponService(SecurityLoadout loadout, int weaponPower,
		int gunAmmo, int missiles, int laserHeat) {
		return weaponServiceNeed(loadout, weaponPower, gunAmmo, missiles, laserHeat)
			!= DroneServicePolicy.Need.NONE;
	}

	static DroneServicePolicy.Need weaponServiceNeed(SecurityLoadout loadout, int weaponPower,
		int gunAmmo, int missiles, int laserHeat) {
		if (weaponPower < AUTO_WEAPON_RESERVE) return DroneServicePolicy.Need.WEAPON_POWER;
		SecurityLoadout selected = loadout == null ? SecurityLoadout.AUTO : loadout;
		return switch (selected) {
			case UNARMED -> DroneServicePolicy.Need.NONE;
			case AUTOCANNON -> gunAmmo <= 0
				? DroneServicePolicy.Need.AUTOCANNON_AMMO : DroneServicePolicy.Need.NONE;
			case MISSILE -> missiles <= 0
				? DroneServicePolicy.Need.MISSILE_AMMO : DroneServicePolicy.Need.NONE;
			case LASER -> DroneServicePolicy.Need.NONE;
			case AUTO -> hasUsableWeapon(selected, gunAmmo, missiles, laserHeat)
				? DroneServicePolicy.Need.NONE : DroneServicePolicy.Need.WEAPON_REARM;
		};
	}

	static boolean sortieReady(SecurityLoadout loadout, float healthRatio,
		int flightPower, int weaponPower,
		int gunAmmo, int missiles, int laserHeat, boolean urgent) {
		int minimumPower = urgent ? URGENT_SORTIE_POWER : DroneServicePolicy.NORMAL_SORTIE_POWER;
		if (healthRatio < DroneServicePolicy.MINIMUM_SORTIE_HEALTH_RATIO
			|| flightPower < minimumPower || weaponPower < minimumPower) return false;
		if (urgent) return hasUsableWeapon(loadout, gunAmmo, missiles, laserHeat);
		SecurityLoadout selected = loadout == null ? SecurityLoadout.AUTO : loadout;
		return switch (selected) {
			case UNARMED -> false;
			case AUTO -> gunAmmo >= (int)Math.ceil(GUN_CAPACITY * 0.6) && missiles >= 2;
			case AUTOCANNON -> gunAmmo >= (int)Math.ceil(GUN_CAPACITY * 0.6);
			case LASER -> laserHeat < LASER_SWITCH_HEAT;
			case MISSILE -> missiles >= 2;
		};
	}

	static boolean resourceLimitedSortieReady(SecurityLoadout loadout, float healthRatio,
		int flightPower, int weaponPower, int gunAmmo, int missiles, int laserHeat,
		boolean liveEngagement, boolean dockResourceExhausted) {
		return liveEngagement && dockResourceExhausted
			&& sortieReady(loadout, healthRatio, flightPower, weaponPower,
				gunAmmo, missiles, laserHeat, true);
	}

	static boolean normalPayloadReady(SecurityLoadout loadout, int gunAmmo,
		int missiles, int laserHeat) {
		SecurityLoadout selected = loadout == null ? SecurityLoadout.AUTO : loadout;
		return switch (selected) {
			case UNARMED -> false;
			case AUTO -> gunAmmo >= (int)Math.ceil(GUN_CAPACITY * 0.6) && missiles >= 2;
			case AUTOCANNON -> gunAmmo >= (int)Math.ceil(GUN_CAPACITY * 0.6);
			case LASER -> laserHeat < LASER_SWITCH_HEAT;
			case MISSILE -> missiles >= 2;
		};
	}

	static boolean autocannonFireTick(int tickCount) {
		return Math.floorMod(tickCount, AUTOCANNON_FIRE_INTERVAL_TICKS) == 0;
	}

	static Vec3 casFormation(Vec3 target, Vec3 attackAxis, int elementIndex, int elementCount,
		long passTicks) {
		return casFormation(target, attackAxis, elementIndex, elementCount, passTicks,
			AirspaceSlot.single());
	}

	static Vec3 casFormation(Vec3 target, Vec3 attackAxis, int elementIndex, int elementCount,
		long passTicks, AirspaceSlot airspace) {
		Vec3 forward = rotateHorizontal(horizontalUnit(attackAxis), airspace.axisAngle());
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		double phase = casPhase(passTicks, elementIndex, airspace);
		double longitudinal = Math.sin(phase) * CAS_LONGITUDINAL_RADIUS;
		double lateral = Math.sin(phase) * Math.cos(phase) * CAS_LATERAL_RADIUS;
		double lane = Mth.clamp(elementIndex - (Math.max(1, elementCount) - 1) / 2.0,
			-2.5, 2.5) * CAS_LANE_SPACING;
		double layer = Math.floorDiv(Math.max(0, elementIndex), 4);
		double normalizedDistance = Math.abs(longitudinal) / CAS_LONGITUDINAL_RADIUS;
		double attackHeight = CAS_ATTACK_HEIGHT + Math.pow(normalizedDistance, 1.55) * 18.0;
		return target.add(forward.scale(longitudinal)).add(right.scale(lateral + lane))
			.add(0, attackHeight + airspace.heightOffset() + layer * 0.7, 0);
	}

	static Vec3 casFormationVelocity(Vec3 target, Vec3 attackAxis, int elementIndex,
		int elementCount, long passTicks) {
		return casFormationVelocity(target, attackAxis, elementIndex, elementCount, passTicks,
			AirspaceSlot.single());
	}

	static Vec3 stabilizeCasCenter(Vec3 previous, Vec3 observed, Vec3 targetVelocity) {
		if (previous == null) return observed;
		Vec3 velocity = targetVelocity == null ? Vec3.ZERO : targetVelocity;
		if (velocity.length() > 0.8) velocity = velocity.normalize().scale(0.8);
		Vec3 predicted = observed.add(velocity.scale(2.0));
		Vec3 delta = predicted.subtract(previous);
		Vec3 horizontal = delta.multiply(1, 0, 1);
		if (horizontal.length() > 0.72) horizontal = horizontal.normalize().scale(0.72);
		double vertical = Mth.clamp(delta.y, -0.35, 0.35);
		return previous.add(horizontal.x, vertical, horizontal.z);
	}

	static Vec3 casBreakawayPoint(Vec3 origin, Vec3 escapeDirection, int elementIndex,
		int elementCount) {
		Vec3 forward = horizontalUnit(escapeDirection);
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		double lane = (elementIndex - (Math.max(1, elementCount) - 1) / 2.0) * 5.0;
		return origin.add(forward.scale(25.0)).add(right.scale(lane)).add(0, 9.0, 0);
	}

	static double casSlotTolerance(Vec3 targetVelocity, double trackingError) {
		double speed = targetVelocity == null ? 0.0 : targetVelocity.multiply(1, 0, 1).length();
		return Mth.clamp(4.5 + speed * 5.0 + Math.max(0.0, trackingError - 2.0) * 0.18,
			4.5, 8.0);
	}

	static boolean mobileCasTarget(Vec3 targetVelocity, double trackingError) {
		return (targetVelocity != null && targetVelocity.multiply(1, 0, 1).length() >= 0.12)
			|| trackingError >= 2.5;
	}

	static Vec3 casFormationVelocity(Vec3 target, Vec3 attackAxis, int elementIndex,
		int elementCount, long passTicks, AirspaceSlot airspace) {
		return casFormation(target, attackAxis, elementIndex, elementCount, passTicks + 1, airspace)
			.subtract(casFormation(target, attackAxis, elementIndex, elementCount, passTicks, airspace));
	}

	static boolean casGunWindow(long passTicks, int elementIndex) {
		return casGunWindow(passTicks, elementIndex, AirspaceSlot.single());
	}

	static boolean casGunWindow(long passTicks, int elementIndex, AirspaceSlot airspace) {
		double phase = casPhase(passTicks, elementIndex, airspace);
		double sin = Math.sin(phase);
		double cos = Math.cos(phase);
		return sin * cos < 0.0 && Math.abs(sin) < 0.48 && Math.abs(cos) > 0.86;
	}

	static Vec3 casStrikePoint(Vec3 target, Vec3 attackAxis, int elementIndex,
		long passTicks, AirspaceSlot airspace) {
		Vec3 forward = rotateHorizontal(horizontalUnit(attackAxis), airspace.axisAngle());
		double phase = casPhase(passTicks, elementIndex, airspace);
		double longitudinal = Math.sin(phase) * CAS_LONGITUDINAL_RADIUS;
		double direction = Math.signum(Math.cos(phase));
		if (direction == 0.0) direction = 1.0;
		double strikeLine = Mth.clamp(longitudinal * 0.28 + direction * 2.2, -7.5, 7.5);
		return target.add(forward.scale(strikeLine));
	}

	static Vec3 ballisticAim(Vec3 targetCenter, Vec3 targetVelocity, int seed,
		double range, double accuracy) {
		double safeAccuracy = Mth.clamp(accuracy, 0.0, 1.0);
		double leadTicks = Mth.clamp(range / 4.8, 0.15, 0.72);
		double error = (1.0 - safeAccuracy) * 2.4;
		double x = signedNoise(seed * 31 + 7) * error;
		double y = signedNoise(seed * 17 + 13) * error * 0.65;
		double z = signedNoise(seed * 47 + 3) * error;
		return targetCenter.add(targetVelocity.scale(leadTicks)).add(x, y, z);
	}

	static FiringSolution firingSolution(double range, double idealRange,
		double slotError, double idealTolerance, double maximumRange) {
		if (!Double.isFinite(range) || !Double.isFinite(slotError) || range > maximumRange) {
			return new FiringSolution(false, 0.0);
		}
		double safeTolerance = Math.max(0.1, idealTolerance);
		if (slotError <= safeTolerance) return new FiringSolution(true, 1.0);
		double rangePenalty = Math.max(0.0, range - idealRange)
			/ Math.max(1.0, maximumRange - idealRange);
		double slotPenalty = Math.min(1.0, (slotError - safeTolerance)
			/ Math.max(1.0, maximumRange * 0.65));
		double accuracy = Mth.clamp(1.0 - rangePenalty * 0.42 - slotPenalty * 0.48,
			0.18, 0.99);
		return new FiringSolution(true, accuracy);
	}

	static boolean accuracyHit(int seed, double accuracy) {
		if (accuracy >= 0.999999) return true;
		return (signedNoise(seed * 73 + 29) + 1.0) * 0.5 <= Mth.clamp(accuracy, 0.0, 1.0);
	}

	static boolean forwardFiringSolution(Vec3 muzzle, Vec3 velocity, Vec3 aim) {
		Vec3 flight = velocity;
		Vec3 shot = aim.subtract(muzzle);
		if (flight.lengthSqr() < 0.01 || shot.lengthSqr() < 0.01) return false;
		return flight.normalize().dot(shot.normalize()) >= 0.72;
	}

	static boolean laserIngressRequired(Vec3 aircraft, Vec3 targetCenter, AirspaceSlot airspace) {
		double horizontalDistance = aircraft.subtract(targetCenter).multiply(1, 0, 1).length();
		double minimumOrbitHeight = targetCenter.y + 8.0 + airspace.heightOffset();
		return horizontalDistance > LASER_INGRESS_DISTANCE || aircraft.y < minimumOrbitHeight - 1.0;
	}

	static Vec3 laserIngressWaypoint(Vec3 aircraft, Vec3 targetCenter, AirspaceSlot airspace) {
		double cruiseY = targetCenter.y + LASER_INGRESS_HEIGHT + airspace.heightOffset();
		if (aircraft.y < cruiseY - 2.0) return new Vec3(aircraft.x, cruiseY, aircraft.z);
		Vec3 outward = horizontalUnit(aircraft.subtract(targetCenter));
		double radius = LASER_INGRESS_RADIUS + airspace.radiusOffset();
		Vec3 staging = targetCenter.add(outward.scale(radius));
		return new Vec3(staging.x, cruiseY, staging.z);
	}

	public static TracerSegment tracerSegment(Vec3 muzzle, Vec3 aim, int age) {
		Vec3 delta = aim.subtract(muzzle);
		double distance = delta.length();
		if (distance < 0.001) return new TracerSegment(muzzle, muzzle);
		Vec3 direction = delta.scale(1.0 / distance);
		double head = Math.min(distance, (Math.max(0, age) + 0.65) * 4.2);
		double tail = Math.max(0.0, head - 2.0);
		return new TracerSegment(muzzle.add(direction.scale(tail)),
			muzzle.add(direction.scale(head)));
	}

	public record TracerSegment(Vec3 start, Vec3 end) {}
	public record FiringSolution(boolean permitted, double accuracy) {}

	static Vec3 laserOrbit(Vec3 targetCenter, int slot, int count, long tick, int charge) {
		return laserOrbit(targetCenter, slot, count, tick, charge, AirspaceSlot.single());
	}

	static Vec3 laserOrbit(Vec3 targetCenter, int slot, int count, long tick, int charge,
		AirspaceSlot airspace) {
		int safeCount = Math.max(1, count);
		double phase = tick * (0.32 / (10.0 + airspace.radiusOffset())) + Math.floorMod(slot, safeCount) * Math.PI * 2.0 / safeCount
			+ airspace.phaseOffset();
		double progress = Mth.clamp(charge / 1000.0, 0.0, 1.0);
		double eased = progress * progress * (3.0 - 2.0 * progress);
		double radius = Mth.lerp(eased, 8.0, 10.0) + airspace.radiusOffset();
		double height = Mth.lerp(eased, 6.0, 10.0) + airspace.heightOffset();
		return targetCenter.add(Math.cos(phase) * radius, height, Math.sin(phase) * radius);
	}

	static Vec3 laserOrbitVelocity(Vec3 targetCenter, int slot, int count, long tick, int charge) {
		return laserOrbitVelocity(targetCenter, Vec3.ZERO, slot, count, tick, charge);
	}

	static Vec3 laserOrbitVelocity(Vec3 targetCenter, Vec3 targetVelocity,
		int slot, int count, long tick, int charge) {
		return laserOrbitVelocity(targetCenter, targetVelocity, slot, count, tick, charge,
			AirspaceSlot.single());
	}

	static Vec3 laserOrbitVelocity(Vec3 targetCenter, Vec3 targetVelocity,
		int slot, int count, long tick, int charge, AirspaceSlot airspace) {
		return laserOrbit(targetCenter, slot, count, tick + 1, charge, airspace)
			.subtract(laserOrbit(targetCenter, slot, count, tick, charge, airspace))
			.add(targetVelocity);
	}

	static int laserChargePerTick(int slot, int batteryPercent) {
		return Math.max(8, 15 + batteryPercent / 20 - Math.floorMod(slot, 4));
	}

	static int laserSustainedFireTicks() {
		return (int)Math.ceil((double)LASER_SWITCH_HEAT / LASER_FIRE_HEAT_PER_TICK);
	}

	static boolean laserReleaseReady(int memberCount, int readyCount,
		boolean formationStarted, long waitTicks) {
		if (memberCount <= 0 || readyCount <= 0) return false;
		if (formationStarted || readyCount >= memberCount) return true;
		if (waitTicks >= LASER_RELEASE_TIMEOUT_TICKS) return true;
		int quorum = Math.max(1, (int)Math.ceil(memberCount * 0.75));
		return waitTicks >= LASER_SYNC_WINDOW_TICKS && readyCount >= quorum;
	}

	static boolean laserFallbackReleaseReady(long waitTicks, double range,
		double slotError, boolean lineClear, boolean formationSpacingReady) {
		if (waitTicks < LASER_RELEASE_TIMEOUT_TICKS || !lineClear) return false;
		// Absolute phase may lag a moving orbit while every member remains evenly spaced.
		// Release that coherent element, but never promote a clustered element into fire.
		return (slotError <= 1.65 || formationSpacingReady)
			&& firingSolution(range, 15.0, slotError, 1.65, 48.0).permitted();
	}

	static boolean sameLaserFormation(String ownManeuverKey, String candidateManeuverKey) {
		return ownManeuverKey != null && !ownManeuverKey.isBlank()
			&& ownManeuverKey.equals(candidateManeuverKey);
	}

	static boolean laserFormationSpacingReady(List<Vec3> positions, Vec3 center,
		double maximumGapError) {
		return laserFormationGapError(positions, center) <= Math.max(0.0, maximumGapError);
	}

	static double laserFormationGapError(List<Vec3> positions, Vec3 center) {
		if (positions == null || center == null || positions.isEmpty()) return Double.POSITIVE_INFINITY;
		if (positions.size() == 1) return 0.0;
		double[] angles = new double[positions.size()];
		for (int index = 0; index < positions.size(); index++) {
			Vec3 position = positions.get(index);
			if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.z))
				return Double.POSITIVE_INFINITY;
			angles[index] = Math.atan2(position.z - center.z, position.x - center.x);
		}
		Arrays.sort(angles);
		double ideal = Math.PI * 2.0 / angles.length;
		double error = 0.0;
		for (int index = 0; index < angles.length; index++) {
			double next = index + 1 < angles.length ? angles[index + 1] : angles[0] + Math.PI * 2.0;
			error = Math.max(error, Math.abs(next - angles[index] - ideal));
		}
		return error;
	}

	static boolean autocannonImpactAcceptable(Vec3 intendedAim, Vec3 impact,
		Vec3 targetCenter) {
		if (intendedAim == null || impact == null || targetCenter == null) return false;
		double impactDeviation = impact.multiply(1, 0, 1)
			.distanceTo(intendedAim.multiply(1, 0, 1));
		double targetOffset = impact.multiply(1, 0, 1)
			.distanceTo(targetCenter.multiply(1, 0, 1));
		return impactDeviation <= 4.0 && targetOffset <= 10.0;
	}

	static int smoothLaserOrbitCharge(int current, int desired, long elapsedTicks) {
		int elapsed = (int)Mth.clamp(elapsedTicks, 1L, 1000L);
		int delta = Mth.clamp(desired - current, -4 * elapsed, 24 * elapsed);
		return Mth.clamp(current + delta, 0, 1000);
	}

	static Vec3 missileApproach(Vec3 target, Vec3 from, int slot, int count) {
		return missileApproach(target, from, slot, count, AirspaceSlot.single());
	}

	static Vec3 missileApproach(Vec3 target, Vec3 from, int slot, int count,
		AirspaceSlot airspace) {
		Vec3 outward = rotateHorizontal(horizontalUnit(from.subtract(target)), airspace.axisAngle());
		Vec3 right = new Vec3(-outward.z, 0, outward.x);
		double spacing = (slot - (Math.max(1, count) - 1) / 2.0) * AirframeEnvelope.SLOT_DISTANCE;
		return target.add(outward.scale(32.0)).add(right.scale(spacing))
			.add(0, 16.0 + airspace.heightOffset(), 0);
	}

	static Vec3 entryApproach(Vec3 target, Vec3 from, AirspaceSlot airspace) {
		Vec3 outward = rotateHorizontal(horizontalUnit(from.subtract(target)), airspace.axisAngle());
		return target.add(outward.scale(14.0)).add(0, 9.0 + airspace.heightOffset(), 0);
	}

	static Vec3 egress(Vec3 target, Vec3 from, AirspaceSlot airspace) {
		Vec3 outward = rotateHorizontal(horizontalUnit(from.subtract(target)), airspace.axisAngle());
		return target.add(outward.scale(38.0)).add(0, 18.0 + airspace.heightOffset(), 0);
	}

	static AirspaceSlot airspaceSlot(List<String> maneuverKeys, String ownKey) {
		List<String> ordered = maneuverKeys.stream().filter(key -> key != null && !key.isBlank())
			.distinct().sorted().toList();
		int index = ordered.indexOf(ownKey);
		return index < 0 ? AirspaceSlot.single() : new AirspaceSlot(index, ordered.size());
	}

	private static Vec3 horizontalUnit(Vec3 value) {
		Vec3 horizontal = value.multiply(1, 0, 1);
		return horizontal.lengthSqr() < 0.001 ? new Vec3(0, 0, 1) : horizontal.normalize();
	}

	private static Vec3 rotateHorizontal(Vec3 value, double angle) {
		double sin = Math.sin(angle);
		double cos = Math.cos(angle);
		return new Vec3(value.x * cos - value.z * sin, 0, value.x * sin + value.z * cos);
	}

	private static double casPhase(long passTicks, int elementIndex) {
		return casPhase(passTicks, elementIndex, AirspaceSlot.single());
	}

	private static double casPhase(long passTicks, int elementIndex, AirspaceSlot airspace) {
		double ticks = Math.max(0L, passTicks);
		double travel = ticks < CAS_ACCELERATION_TICKS
			? CAS_PATH_SPEED * ticks * ticks / (2.0 * CAS_ACCELERATION_TICKS)
			: CAS_PATH_SPEED * (ticks - CAS_ACCELERATION_TICKS * 0.5);
		travel -= Math.max(0, elementIndex) * CAS_TRAIL_DISTANCE;
		travel += airspace.index() * CAS_ARC_TOTAL * 0.09;
		double wrapped = travel % CAS_ARC_TOTAL;
		if (wrapped < 0) wrapped += CAS_ARC_TOTAL;
		int low = 0;
		int high = CAS_ARC_SAMPLES;
		while (low + 1 < high) {
			int mid = (low + high) >>> 1;
			if (CAS_ARC_LENGTHS[mid] <= wrapped) low = mid;
			else high = mid;
		}
		double segment = CAS_ARC_LENGTHS[high] - CAS_ARC_LENGTHS[low];
		double fraction = segment <= 0.000001 ? 0.0
			: (wrapped - CAS_ARC_LENGTHS[low]) / segment;
		return -Math.PI * 0.5 + (low + fraction) * Math.PI * 2.0 / CAS_ARC_SAMPLES;
	}

	private static double[] buildCasArcLengths() {
		double[] lengths = new double[CAS_ARC_SAMPLES + 1];
		Vec3 previous = casLocalPoint(-Math.PI * 0.5);
		for (int sample = 1; sample <= CAS_ARC_SAMPLES; sample++) {
			double phase = -Math.PI * 0.5 + sample * Math.PI * 2.0 / CAS_ARC_SAMPLES;
			Vec3 point = casLocalPoint(phase);
			lengths[sample] = lengths[sample - 1] + point.distanceTo(previous);
			previous = point;
		}
		return lengths;
	}

	private static Vec3 casLocalPoint(double phase) {
		double longitudinal = Math.sin(phase) * CAS_LONGITUDINAL_RADIUS;
		double lateral = Math.sin(phase) * Math.cos(phase) * CAS_LATERAL_RADIUS;
		double normalizedDistance = Math.abs(longitudinal) / CAS_LONGITUDINAL_RADIUS;
		double height = CAS_ATTACK_HEIGHT + Math.pow(normalizedDistance, 1.55) * 18.0;
		return new Vec3(longitudinal, height, lateral);
	}

	private static double signedNoise(int seed) {
		int value = seed * 1103515245 + 12345;
		return ((value >>> 8) & 0xffff) / 32767.5 - 1.0;
	}

	public record AirspaceSlot(int index, int count) {
		private static final int LANE_BANDS = 4;
		private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

		public AirspaceSlot {
			count = Math.max(1, count);
			index = Mth.clamp(index, 0, count - 1);
		}

		static AirspaceSlot single() { return new AirspaceSlot(0, 1); }
		double heightOffset() { return Math.floorMod(index, LANE_BANDS) * AirframeEnvelope.LAYER_HEIGHT; }
		double radiusOffset() {
			int heightBand = Math.floorMod(index, LANE_BANDS);
			int radiusBand = Math.floorMod(heightBand + index / LANE_BANDS, LANE_BANDS);
			return radiusBand * 4.0;
		}
		double phaseOffset() { return index * GOLDEN_ANGLE; }
		double axisAngle() { return index * GOLDEN_ANGLE; }
	}
}
