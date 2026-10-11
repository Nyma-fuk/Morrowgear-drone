package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CargoLiftPolicyTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void transientAircraftLightDoesNotReserveTheLandingAnchor() {
		assertTrue(CargoLiftLoadEntity.landingAnchorClear(Blocks.AIR.defaultBlockState()));
		assertTrue(CargoLiftLoadEntity.landingAnchorClear(Blocks.LIGHT.defaultBlockState()));
		assertFalse(CargoLiftLoadEntity.landingAnchorClear(Blocks.STONE.defaultBlockState()));
	}
    private static List<CargoLiftPolicy.Aircraft> aircraft(double battery, boolean connected) {
        return Arrays.stream(CargoLiftPolicy.Corner.values()).map(corner ->
            new CargoLiftPolicy.Aircraft(UUID.randomUUID(), corner, battery, true, true, connected)).toList();
    }

    @Test void loadCannotLiftBeforeAllFourWinchesConnect() {
        assertEquals(CargoLiftPolicy.Phase.CONNECTING,
            CargoLiftPolicy.assess(CargoLiftPolicy.Phase.CONNECTING, aircraft(.8, false), true, true).next());
        assertEquals(CargoLiftPolicy.Phase.TENSIONING,
            CargoLiftPolicy.assess(CargoLiftPolicy.Phase.CONNECTING, aircraft(.8, true), true, true).next());
    }

    @Test void airborneFailureTriggersEmergencyDescentWithoutDroppingInventory() {
        var list = new java.util.ArrayList<>(aircraft(.8, true));
        var failed = list.get(2);
        list.set(2, new CargoLiftPolicy.Aircraft(failed.id(), failed.corner(), .8, false, true, true));
        var result = CargoLiftPolicy.assess(CargoLiftPolicy.Phase.TRANSIT, list, true, true);
        assertEquals(CargoLiftPolicy.Phase.EMERGENCY_DESCENT, result.next());
        assertEquals("AIRCRAFT_UNAVAILABLE", result.reason());
    }

    @Test void altitudeIncludesWholeLoadAndCableEnvelope() {
        assertTrue(CargoLiftPolicy.droneAltitude(100, 1.2) >= 111.4);
    }

    @Test void runtimePhaseCannotAdvanceBeforePhysicalCondition() {
        assertEquals(CargoLiftPolicy.Phase.RENDEZVOUS,
            CargoLiftPolicy.assess(CargoLiftPolicy.Phase.RENDEZVOUS,
                aircraft(.8, true), true, true, false).next());
        assertEquals("RENDEZVOUS_PENDING", CargoLiftPolicy.assess(
            CargoLiftPolicy.Phase.RENDEZVOUS, aircraft(.8, true), true, true, false).reason());
    }

    @Test void lowReserveBeforeLiftHoldsButLowReserveInFlightDescends() {
        assertEquals(CargoLiftPolicy.Phase.HOLD, CargoLiftPolicy.assess(
            CargoLiftPolicy.Phase.CONNECTING, aircraft(.27, true), true, true, true).next());
        assertEquals(CargoLiftPolicy.Phase.EMERGENCY_DESCENT, CargoLiftPolicy.assess(
            CargoLiftPolicy.Phase.TRANSIT, aircraft(.27, true), true, true, false).next());
    }

    @Test void everyAircraftReceivesOneUniqueContainerCorner() {
        var offsets = Arrays.stream(CargoLiftPolicy.Corner.values())
            .map(CargoLiftPolicy::cornerOffset).toList();
        assertEquals(4, offsets.stream().distinct().count());
        assertTrue(offsets.stream().allMatch(offset -> Math.abs(offset.x()) > CargoLiftPolicy.LOAD_HALF_LENGTH));
        assertTrue(offsets.stream().allMatch(offset -> Math.abs(offset.z()) > CargoLiftPolicy.LOAD_HALF_WIDTH));
        double minimum = Double.POSITIVE_INFINITY;
        for (int a = 0; a < offsets.size(); a++) for (int b = a + 1; b < offsets.size(); b++) {
            double dx = offsets.get(a).x() - offsets.get(b).x();
            double dz = offsets.get(a).z() - offsets.get(b).z();
            minimum = Math.min(minimum, Math.sqrt(dx * dx + dz * dz));
        }
        assertEquals(CargoLiftPolicy.MIN_AIRCRAFT_SEPARATION, minimum, 1e-9);
        assertTrue(minimum >= 6.5, "3x3 salvage hulls require a visible safety gap");
    }

    @Test void cableEyesStayOnContainerWhileAircraftUseWideFormation() {
        for (CargoLiftPolicy.Corner corner : CargoLiftPolicy.Corner.values()) {
            var eye = CargoLiftPolicy.attachmentOffset(corner);
            var aircraft = CargoLiftPolicy.formationOffset(corner);
            assertTrue(Math.abs(eye.x()) < CargoLiftPolicy.LOAD_HALF_LENGTH);
            assertTrue(Math.abs(eye.z()) < CargoLiftPolicy.LOAD_HALF_WIDTH);
            assertTrue(Math.abs(aircraft.x()) > Math.abs(eye.x()));
            assertTrue(Math.abs(aircraft.z()) > Math.abs(eye.z()));
        }
    }

    @Test void occupiedDestinationHoldsAnAirborneLoad() {
        assertEquals(CargoLiftPolicy.Phase.HOLD, CargoLiftPolicy.assess(
            CargoLiftPolicy.Phase.TRANSIT, aircraft(.8, true), false, true, false).next());
        assertEquals("LANDING_FOOTPRINT_OCCUPIED", CargoLiftPolicy.assess(
            CargoLiftPolicy.Phase.TRANSIT, aircraft(.8, true), false, true, false).reason());
    }

    @Test void destinationOccupancyIsEvaluatedOnlyInsideTheHoldingArea() {
        assertFalse(CargoLiftPolicy.landingClearRequired(CargoLiftPolicy.Phase.TRANSIT, 16.0));
        assertTrue(CargoLiftPolicy.landingClearRequired(CargoLiftPolicy.Phase.TRANSIT, 15.99));
        assertTrue(CargoLiftPolicy.landingClearRequired(CargoLiftPolicy.Phase.DESCENT, 100.0));
        assertFalse(CargoLiftPolicy.landingClearRequired(CargoLiftPolicy.Phase.RELEASE, 0.0));
        assertFalse(CargoLiftPolicy.landingClearRequired(CargoLiftPolicy.Phase.COMPLETE, 0.0));
    }

    @Test void liftAircraftUseCruiseSpeedUntilTheActualFinalMetres() {
        assertEquals(.92,CargoLiftPolicy.aircraftSpeed(CargoLiftPolicy.Phase.RENDEZVOUS,20),1e-9);
        assertTrue(CargoLiftPolicy.aircraftSpeed(CargoLiftPolicy.Phase.RENDEZVOUS,4)
            > CargoLiftPolicy.aircraftSpeed(CargoLiftPolicy.Phase.RENDEZVOUS,1));
        assertEquals(CargoLiftPolicy.TRANSIT_AIRCRAFT_SPEED,
            CargoLiftPolicy.aircraftSpeed(CargoLiftPolicy.Phase.TRANSIT,.5),1e-9);
        assertTrue(CargoLiftPolicy.MAX_TRANSIT_SPEED>=.8,"a four-aircraft lift must not crawl in open transit");
    }
}
