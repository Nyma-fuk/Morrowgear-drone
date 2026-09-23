package jp.morrowgear.drone.carrier;

import net.minecraft.world.phys.Vec3;

/** Arc-fusion baseline output and capacitor-only high-output operation. */
public final class CarrierPowerPolicy {
    public static final int FLIGHT_GENERATION_PER_TICK = 12;
    public static final int WORK_GENERATION_PER_TICK = 10;
    public static final int MINING_STARTUP_ENERGY = 200;
    public static final int SUSTAINED_MINING_BLOCKS_PER_TICK = CarrierPolicy.BREAK_PER_TICK / 4;
    public static final int MINING_BURST_ENERGY_PER_BLOCK = 10;
    public static final int BOOST_ENABLE_RESERVE = 500;
    public static final int FLIGHT_SERVICE_RESERVE = 2_000;
    public static final int WORK_SERVICE_RESERVE = 3_000;
    public static final double LOW_POWER_SPEED = .12;
    private static final double BOOST_COST_PER_ACCELERATION = 600;

    private CarrierPowerPolicy() {}

    public static boolean regenerate(CarrierShip ship) {
        int flight = Math.min(CarrierPolicy.MAX_ENERGY, ship.energy + FLIGHT_GENERATION_PER_TICK);
        int work = Math.min(CarrierPolicy.MAX_ENERGY, ship.weaponEnergy + WORK_GENERATION_PER_TICK);
        if (flight == ship.energy && work == ship.weaponEnergy) return false;
        ship.energy = flight;
        ship.weaponEnergy = work;
        ship.dirty.run();
        return true;
    }

    public static double flightSpeed(int storedEnergy) {
        return storedEnergy >= BOOST_ENABLE_RESERVE ? CarrierNavigation.SPEED : LOW_POWER_SPEED;
    }

    /** Steady cruise is reactor-supported; only acceleration above low-output speed uses storage. */
    public static int boostCost(Vec3 previousVelocity, Vec3 nextVelocity) {
        double acceleration = Math.max(0, nextVelocity.length() - previousVelocity.length());
        if (nextVelocity.length() <= LOW_POWER_SPEED || acceleration <= 1.0e-9) return 0;
        return Math.max(1, (int) Math.ceil(acceleration * BOOST_COST_PER_ACCELERATION));
    }

    public static boolean workAvailable(CarrierShip ship, int cost) {
        return cost >= 0 && ship.weaponEnergy >= cost;
    }

    public static boolean consumeWork(CarrierShip ship, int cost) {
        if (!workAvailable(ship, cost)) return false;
        ship.weaponEnergy -= cost;
        ship.dirty.run();
        return true;
    }

    /** Reconstructs work spent during one ordinary server tick after arc-fusion generation. */
    public static int observedWorkConsumption(int previousStored, int currentStored) {
        int available = Math.min(CarrierPolicy.MAX_ENERGY, previousStored + WORK_GENERATION_PER_TICK);
        return available - currentStored;
    }

    /** Child-aircraft service may use surplus storage, never the carrier's maneuver or weapon reserve. */
    public static int serviceOffer(int stored, int demand, boolean weapon) {
        int reserve = weapon ? WORK_SERVICE_RESERVE : FLIGHT_SERVICE_RESERVE;
        return Math.min(Math.max(0, demand), Math.min(40, Math.max(0, stored - reserve)));
    }

    public static boolean sustainedMiningSlot(int brokenThisTick) {
        return brokenThisTick >= 0 && brokenThisTick < SUSTAINED_MINING_BLOCKS_PER_TICK;
    }

    /** Solid-block upper budget; air and sealed bedrock columns complete sooner. */
    public static int miningTickBudget(CarrierPolicy.Operation operation) {
        int preparation = CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS;
        return preparation + (operation.volume() + SUSTAINED_MINING_BLOCKS_PER_TICK - 1)
            / SUSTAINED_MINING_BLOCKS_PER_TICK;
    }
}
