package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CarrierContainerTransferPolicyTest {
    @Test void unloadSupportPlaneFollowsTheHighestSurvivingCraterRim() {
        assertEquals(64, CarrierContainerTransferPolicy.supportPlane(-64, 320,
            38, 39, 40, 41, 64, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51));
        assertEquals(-31, CarrierContainerTransferPolicy.supportPlane(-64, 320, -40, -31, -47));
        assertEquals(CarrierContainerTransferPolicy.NO_SUPPORT,
            CarrierContainerTransferPolicy.supportPlane(-64, 66, 64));
        assertEquals(CarrierContainerTransferPolicy.NO_SUPPORT,
            CarrierContainerTransferPolicy.supportPlane(-64, 320));
    }
    private static CarrierContainerTransferPolicy.Conditions ready(boolean grounded) {
        return new CarrierContainerTransferPolicy.Conditions(true, true, true, true, true, true, true, true, grounded);
    }
    @Test void loadingCommitsBeforeVisibleHoistSoInterruptionCannotDuplicateTheBlock() {
        var tension = CarrierContainerTransferPolicy.next(CarrierContainerTransferPolicy.Direction.LOAD,
            CarrierContainerTransferPolicy.Phase.TENSION, ready(false));
        assertEquals(CarrierContainerTransferPolicy.Phase.HOIST, tension.next());
        assertTrue(tension.commitLocation());
        assertFalse(CarrierContainerTransferPolicy.next(CarrierContainerTransferPolicy.Direction.LOAD,
            CarrierContainerTransferPolicy.Phase.ATTACH, ready(false)).commitLocation());
    }
    @Test void unloadingBecomesAccessibleAtGroundReleaseBeforeWinchAndHatchFinish() {
        var contact = CarrierContainerTransferPolicy.next(CarrierContainerTransferPolicy.Direction.UNLOAD,
            CarrierContainerTransferPolicy.Phase.GROUND_CONTACT, ready(true));
        assertEquals(CarrierContainerTransferPolicy.Phase.RELEASE, contact.next());
        assertTrue(contact.commitLocation());
    }
    @Test void unloadingTransfersLoadToFourPointSlingBeforeASeparatePurgeAndDescent() {
        var hatch = CarrierContainerTransferPolicy.next(CarrierContainerTransferPolicy.Direction.UNLOAD,
            CarrierContainerTransferPolicy.Phase.HATCH_OPEN, ready(false));
        assertEquals(CarrierContainerTransferPolicy.Phase.TENSION, hatch.next());
        var tension = CarrierContainerTransferPolicy.next(CarrierContainerTransferPolicy.Direction.UNLOAD,
            CarrierContainerTransferPolicy.Phase.TENSION, ready(false));
        assertEquals(CarrierContainerTransferPolicy.Phase.PURGE, tension.next());
        assertEquals(CarrierContainerTransferPolicy.Phase.DEPLOY,
            CarrierContainerTransferPolicy.next(CarrierContainerTransferPolicy.Direction.UNLOAD,
                CarrierContainerTransferPolicy.Phase.PURGE, ready(false)).next());
    }
    @Test void craneEasingHasStationaryEndsAndNeverLeavesItsTravel() {
        assertEquals(0,CarrierContainerTransferPolicy.eased(0),1e-12);
        assertEquals(1,CarrierContainerTransferPolicy.eased(1),1e-12);
        assertEquals(.5,CarrierContainerTransferPolicy.eased(.5),1e-12);
        assertEquals(0,CarrierContainerTransferPolicy.eased(-1),1e-12);
        assertEquals(1,CarrierContainerTransferPolicy.eased(2),1e-12);
    }
    @Test void openOrLeasedContainerCannotMove() {
        var blocked = new CarrierContainerTransferPolicy.Conditions(true, true, true, true, true, false, false, true, true);
        assertEquals(CarrierContainerTransferPolicy.Phase.HOLD,
            CarrierContainerTransferPolicy.next(CarrierContainerTransferPolicy.Direction.LOAD,
                CarrierContainerTransferPolicy.Phase.RESERVED, blocked).next());
    }

    @Test void activeCraneTransactionPersistsAndHasARecoveryPath() throws Exception {
        String entity=Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java"));
        for(String key:new String[]{"CarrierTransferActive","CarrierTransferDirection","CarrierTransferPhase",
            "CarrierTransferPosition","CarrierTransferHardpoint","CarrierTransferType","CarrierTransferTicks",
            "CarrierTransferRetries","CarrierTransferApproachY","CarrierTransferDetail"})
            assertTrue(entity.contains(key),key);
        assertTrue(entity.contains("recoverContainerTransfer"));
        assertTrue(entity.contains("releaseContainerReservation"));
        assertTrue(entity.contains("HOLD_RETRY_TICKS"));
        assertTrue(entity.contains("APPROACH_STALL_TICKS"));
        assertTrue(entity.contains("CarrierContainerFootprint.resolveAnchor(level(),pos)"));
        String snapshot=Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierExteriorSnapshot.java"));
        assertTrue(snapshot.contains("Ledger-backed, loaded-only candidates"));
        assertTrue(snapshot.contains(".limit(MAX_CONTAINERS)"));
        assertTrue(snapshot.contains("level.hasChunkAt"));
        String runtime=Files.readString(Path.of("src/main/java/jp/morrowgear/drone/CarrierRuntimeVerification.java"));
        assertTrue(runtime.contains("CarrierContainerFootprint.removeContainer(level,cursor.immutable())"));
        assertTrue(runtime.contains("state.is(CarrierModule.CONTAINER_PART_BLOCK)"));
        assertTrue(runtime.contains("CarrierContainerFootprint.resolveAnchor(level,cursor)==null"));
        assertEquals(2,count(runtime,"CarrierContainerFootprint.removeContainer(level,containerUnload)"));
    }

    @Test void recoveryWorkIsRateLimitedAndBounded() {
        assertTrue(CarrierContainerTransferPolicy.HOLD_RETRY_TICKS>=20);
        assertTrue(CarrierContainerTransferPolicy.MAX_RECOVERY_ATTEMPTS>0);
        assertTrue(CarrierContainerTransferPolicy.MAX_RECOVERY_ATTEMPTS<=5);
        assertTrue(CarrierContainerTransferPolicy.APPROACH_STALL_TICKS>=100);
    }

    @Test void carrierKeepsAHighSafeAltitudeAndTheWinchHasNoGameplayLengthLimit() {
        assertEquals(180,CarrierContainerTransferPolicy.initialApproachAltitude(180,64,-64,320),1e-9);
        double floor=CarrierContainerTransferPolicy.initialApproachAltitude(40,64,-64,320);
        assertTrue(floor>64+CarrierContainerTransferPolicy.CONTAINER_HEIGHT);
        assertEquals(192,CarrierContainerTransferPolicy.nextApproachAltitude(180,320),1e-9);
        assertTrue(CarrierContainerTransferPolicy.winchLength(300,-64)>350);
        assertTrue(Double.isNaN(CarrierContainerTransferPolicy.initialApproachAltitude(300,319,-64,320)));
    }

    private static int count(String value,String fragment){
        int count=0,offset=0;
        while((offset=value.indexOf(fragment,offset))>=0){count++;offset+=fragment.length();}
        return count;
    }
}
