package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierHeavyMissilePolicyTest {
    @Test void launchSequenceHasTimeForHatchColdEjectAndPoweredClimb(){
        assertTrue(CarrierHeavyMissilePolicy.HATCH_TICKS>CarrierHeavyMissilePolicy.EJECT_TICKS);
        assertTrue(CarrierHeavyMissilePolicy.CLIMB_TICKS>CarrierHeavyMissilePolicy.IGNITION_TICKS);
        assertTrue(CarrierHeavyMissilePolicy.CLIMB_TICKS>=112,"powered climb must reach about twice the former apex");
        assertTrue(CarrierHeavyMissilePolicy.TERMINAL_SPEED>3);
        assertTrue(CarrierHeavyMissilePolicy.FUSE_TICKS>0);
    }

    @Test void poweredClimbAcceleratesUpwardWhileBendingOnlyShallowlyTowardTarget(){
        Vec3 origin=new Vec3(0,80,0), target=new Vec3(120,64,0);
        Vec3 first=CarrierHeavyMissilePolicy.climbVelocity(origin,target,
            CarrierHeavyMissilePolicy.IGNITION_TICKS);
        Vec3 last=CarrierHeavyMissilePolicy.climbVelocity(origin,target,
            CarrierHeavyMissilePolicy.CLIMB_TICKS);
        assertTrue(last.length()>first.length());
        assertTrue(last.y>first.y);
        assertTrue(last.y>Math.abs(last.x),"ascent must remain dominant until pitch-over");
    }

    @Test void pitchOverAndTerminalGuidanceRespectTheirFinLimitedTurnRates(){
        Vec3 current=new Vec3(0,1,0), desired=new Vec3(100,-100,0);
        Vec3 turnover=CarrierHeavyMissilePolicy.guidedVelocity(current,desired,
            CarrierHeavyMissilePolicy.CLIMB_TICKS+1);
        Vec3 terminal=CarrierHeavyMissilePolicy.guidedVelocity(current,desired,
            CarrierHeavyMissilePolicy.CLIMB_TICKS+CarrierHeavyMissilePolicy.TURNOVER_TICKS+1);
        assertTrue(angle(current,turnover)<=CarrierHeavyMissilePolicy.TURNOVER_RATE_RADIANS+1e-9);
        assertTrue(angle(current,terminal)<=CarrierHeavyMissilePolicy.TERMINAL_RATE_RADIANS+1e-9);
        assertTrue(terminal.length()>turnover.length());
    }

    @Test void terminalThrustVectoringCapturesCloseLocksWithoutChangingLongRangeArc(){
        assertEquals(CarrierHeavyMissilePolicy.TERMINAL_RATE_RADIANS,
            CarrierHeavyMissilePolicy.terminalTurnRate(CarrierHeavyMissilePolicy.TERMINAL_CAPTURE_RADIUS,false),1e-9);
        assertEquals(CarrierHeavyMissilePolicy.TERMINAL_CAPTURE_RATE_RADIANS,
            CarrierHeavyMissilePolicy.terminalTurnRate(0,false),1e-9);
        assertEquals(CarrierHeavyMissilePolicy.TURNOVER_RATE_RADIANS,
            CarrierHeavyMissilePolicy.terminalTurnRate(0,true),1e-9);
        for(double horizontal:new double[]{0,6,20,50,90,150})
            assertTrue(simulatedMissDistance(horizontal)<.4,
                "heavy missile failed to converge on a lock at horizontal range "+horizontal);
    }

    private static double simulatedMissDistance(double horizontal){
        Vec3 position=new Vec3(0,100,0), target=new Vec3(horizontal,68,0), velocity=new Vec3(0,.52,0);
        double closest=Double.POSITIVE_INFINITY;
        for(int tick=1;tick<=CarrierHeavyMissilePolicy.MAX_FLIGHT_TICKS;tick++){
            if(tick<=CarrierHeavyMissilePolicy.EJECT_TICKS)velocity=new Vec3(0,.52,0);
            else if(tick<=CarrierHeavyMissilePolicy.IGNITION_TICKS)velocity=new Vec3(0,1.05,0);
            else if(tick<=CarrierHeavyMissilePolicy.CLIMB_TICKS)
                velocity=CarrierHeavyMissilePolicy.climbVelocity(position,target,tick);
            else velocity=CarrierHeavyMissilePolicy.guidedVelocity(velocity,target.subtract(position),tick);
            position=position.add(velocity);
            closest=Math.min(closest,position.distanceTo(target));
        }
        return closest;
    }

    private static double angle(Vec3 a,Vec3 b){
        double cosine=a.normalize().dot(b.normalize());
        return Math.acos(Math.max(-1,Math.min(1,cosine)));
    }

    @Test void salvoOpensOnceRipplesEveryFourTicksAndClosesAfterTheLastRound(){
        assertEquals(24,CarrierHeavyMissilePolicy.launchTick(0));
        assertEquals(28,CarrierHeavyMissilePolicy.launchTick(1));
        assertEquals(52,CarrierHeavyMissilePolicy.launchTick(7));
        assertEquals(CarrierHeavyMissilePolicy.LaunchPhase.HATCH_OPEN,
            CarrierHeavyMissilePolicy.launchPhase(23,8));
        assertEquals(CarrierHeavyMissilePolicy.LaunchPhase.RIPPLE_FIRE,
            CarrierHeavyMissilePolicy.launchPhase(24,8));
        assertEquals(CarrierHeavyMissilePolicy.LaunchPhase.HATCH_CLOSE,
            CarrierHeavyMissilePolicy.launchPhase(CarrierHeavyMissilePolicy.closeStartTick(8),8));
        assertEquals(CarrierHeavyMissilePolicy.LaunchPhase.COMPLETE,
            CarrierHeavyMissilePolicy.launchPhase(CarrierHeavyMissilePolicy.sequenceDuration(8),8));
        assertEquals(0,CarrierHeavyMissilePolicy.hatchOpen(0,8),1e-9);
        assertEquals(1,CarrierHeavyMissilePolicy.hatchOpen(24,8),1e-9);
        assertEquals(0,CarrierHeavyMissilePolicy.hatchOpen(CarrierHeavyMissilePolicy.sequenceDuration(8),8),1e-9);
    }

    @Test void emptyAndBoundarySalvosStayBounded(){
        assertEquals(0,CarrierHeavyMissilePolicy.sequenceDuration(0));
        assertEquals(CarrierHeavyMissilePolicy.LaunchPhase.IDLE,CarrierHeavyMissilePolicy.launchPhase(0,0));
        assertThrows(IllegalArgumentException.class,()->CarrierHeavyMissilePolicy.launchTick(-1));
        for(int size=1;size<=8;size++){
            assertTrue(CarrierHeavyMissilePolicy.sequenceDuration(size)>CarrierHeavyMissilePolicy.launchTick(size-1));
            assertTrue(CarrierHeavyMissilePolicy.sequenceDuration(size)<100);
        }
        int full=jp.morrowgear.drone.carrier.CarrierMissileModulePolicy.MAX_SALVO;
        assertTrue(CarrierHeavyMissilePolicy.sequenceDuration(full)
            >CarrierHeavyMissilePolicy.launchTick(full-1));
        assertTrue(CarrierHeavyMissilePolicy.sequenceDuration(full)<500);
    }

    @Test void blastIsTripleRadiusAndWardenClassTargetsRequireTwoDirectHits(){
        assertEquals(21.0f,CarrierHeavyMissilePolicy.EXPLOSION_POWER);
        assertTrue(CarrierHeavyMissilePolicy.DIRECT_DAMAGE<500);
        assertTrue(CarrierHeavyMissilePolicy.DIRECT_DAMAGE*2>=500);
        assertTrue(CarrierHeavyMissilePolicy.DIRECT_DAMAGE
            +CarrierHeavyMissilePolicy.MAX_BLAST_ENTITY_DAMAGE<500);
        assertEquals(CarrierHeavyMissilePolicy.MAX_BLAST_ENTITY_DAMAGE,
            CarrierHeavyMissilePolicy.explosionEntityDamage(1000),1e-6);
    }

    @Test void lifetimeScalesToTheEntireDimensionHeight(){
        assertTrue(CarrierHeavyMissilePolicy.maxFlightTicks(4096)>4096);
    }

    @Test void everyPhysicalCellStaysInsideItsOwnForeAftMagazineAndMirrorsAcrossTheHull(){
        for(int left:new int[]{0,2})for(int cell=0;cell<20;cell++){
            var a=CarrierHeavyMissilePolicy.launchTube(left,cell);
            var b=CarrierHeavyMissilePolicy.launchTube(left+4,cell);
            assertEquals(-a.x(),b.x(),1e-9);
            assertEquals(a.z(),b.z(),1e-9);
            var mount=jp.morrowgear.drone.carrier.CarrierCargoVisualPolicy.hardpoint(left);
            assertTrue(Math.abs(a.x()-mount.x())<CarrierHeavyMissilePolicy.HATCH_HALF_WIDTH);
            assertTrue(Math.abs(a.z()-mount.z())<CarrierHeavyMissilePolicy.HATCH_HALF_LENGTH);
        }
        assertThrows(IllegalArgumentException.class,()->CarrierHeavyMissilePolicy.launchTube(1,0));
        assertThrows(IllegalArgumentException.class,()->CarrierHeavyMissilePolicy.launchTube(0,20));
        assertEquals(72.0,CarrierHeavyMissilePolicy.HATCH_OPEN_ANGLE_DEGREES,1e-9);
    }
}
