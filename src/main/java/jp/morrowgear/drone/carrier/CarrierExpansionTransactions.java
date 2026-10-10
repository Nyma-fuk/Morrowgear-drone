package jp.morrowgear.drone.carrier;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Atomic world transitions for the one physical representation of a detachable expansion. */
public final class CarrierExpansionTransactions {
    public record Decision(boolean success, CarrierExpansionLedger.Status status, String detail) {}

    private CarrierExpansionTransactions() {}

    public static Decision placed(ServerLevel level, CarrierExpansionItemData.Identity identity, BlockPos pos) {
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        var current=ledger.get(identity.id());
        String dimension=dimension(level),witness=blockWitness(level,pos);
        long tick=level.getGameTime();
        if(current==null)return decision(ledger.claimPlaced(identity,dimension,pos.asLong(),witness,tick));
        if(current.location()!=CarrierExpansionPolicy.Location.ITEM)
            return decision(ledger.claimPlaced(identity,dimension,pos.asLong(),witness,tick));
        return commit(ledger,current,current.place(dimension,pos.asLong()),witness,tick);
    }

    public static Decision install(ServerLevel level,CarrierExpansionItemData.Identity identity,BlockPos source,
                                   UUID carrier,int hardpoint){
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        var current=ledger.get(identity.id());
        if(current==null){
            Decision observed=placed(level,identity,source);
            if(!observed.success())return observed;
            current=ledger.get(identity.id());
        }
        if(current.location()!=CarrierExpansionPolicy.Location.PLACED
            ||!dimension(level).equals(current.dimension())||current.position()!=source.asLong())
            return conflict(ledger.claimInstalled(identity,carrier,hardpoint,carrierWitness(carrier,hardpoint),
                level.getGameTime()));
        return commit(ledger,current,current.install(carrier,hardpoint),carrierWitness(carrier,hardpoint),
            level.getGameTime());
    }

    public static Decision unload(ServerLevel level,CarrierExpansionItemData.Identity identity,UUID carrier,
                                  int hardpoint,BlockPos destination){
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        if(!(level.getBlockEntity(destination) instanceof CarrierExpansionContainer))
            ledger.releaseMissingPlaced(dimension(level),destination.asLong(),
                "verified-empty-before-unload/"+carrier+"/"+hardpoint,level.getGameTime());
        var current=ledger.get(identity.id());
        if(current==null){
            var observed=ledger.claimInstalled(identity,carrier,hardpoint,carrierWitness(carrier,hardpoint),
                level.getGameTime());
            if(!observed.success())return decision(observed);
            current=ledger.get(identity.id());
        }
        if(current.location()!=CarrierExpansionPolicy.Location.CARRIER||!carrier.equals(current.carrier())
            ||current.hardpoint()!=hardpoint)
            return conflict(ledger.claimPlaced(identity,dimension(level),destination.asLong(),
                blockWitness(level,destination),level.getGameTime()));
        return commit(ledger,current,current.place(dimension(level),destination.asLong()),
            blockWitness(level,destination),level.getGameTime());
    }

    public static Decision recoverItem(ServerLevel level,CarrierExpansionItemData.Identity identity,BlockPos source,
                                       UUID player){
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        var current=ledger.get(identity.id());
        if(current==null){
            Decision observed=placed(level,identity,source);
            if(!observed.success())return observed;
            current=ledger.get(identity.id());
        }
        if(current.location()!=CarrierExpansionPolicy.Location.PLACED
            ||!dimension(level).equals(current.dimension())||current.position()!=source.asLong())
            return new Decision(false,CarrierExpansionLedger.Status.STALE,"SOURCE_LOCATION_CHANGED");
        return commit(ledger,current,current.moveToItem(),"item/"+player+"/"+identity.id(),level.getGameTime());
    }

    public static Decision beginLift(ServerLevel level,CarrierExpansionItemData.Identity identity,BlockPos source,
                                     UUID mission){
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        var current=ledger.get(identity.id());
        if(current==null){
            Decision observed=placed(level,identity,source);
            if(!observed.success())return observed;
            current=ledger.get(identity.id());
        }
        if(current.location()!=CarrierExpansionPolicy.Location.PLACED||current.position()!=source.asLong()
            ||!dimension(level).equals(current.dimension()))
            return new Decision(false,CarrierExpansionLedger.Status.STALE,"SOURCE_LOCATION_CHANGED");
        return commit(ledger,current,current.lift(mission,dimension(level),source.asLong()),
            "lift/"+mission,level.getGameTime());
    }

    public static Decision finishLift(ServerLevel level,CarrierExpansionItemData.Identity identity,UUID mission,
                                      BlockPos destination){
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        var current=ledger.get(identity.id());
        if(current==null)return new Decision(false,CarrierExpansionLedger.Status.NOT_FOUND,"UNKNOWN_IDENTITY");
        if(current.location()!=CarrierExpansionPolicy.Location.LIFTED||!mission.equals(current.liftMission()))
            return new Decision(false,CarrierExpansionLedger.Status.STALE,"LIFT_MISSION_CHANGED");
        return commit(ledger,current,current.place(dimension(level),destination.asLong()),
            blockWitness(level,destination),level.getGameTime());
    }

    public static Decision rollbackToPlaced(ServerLevel level,CarrierExpansionItemData.Identity identity,
                                            BlockPos position){
        CarrierExpansionLedger ledger=CarrierExpansionLedger.get(level.getServer());
        var token=ledger.begin(identity.id());
        if(token.isEmpty())return new Decision(false,CarrierExpansionLedger.Status.QUARANTINED,"ROLLBACK_BLOCKED");
        return decision(ledger.commit(token.get(),token.get().snapshot().place(dimension(level),position.asLong()),
            blockWitness(level,position),level.getGameTime()));
    }

    private static Decision conflict(CarrierExpansionLedger.Result result){
        return new Decision(false,result.status(),result.detail());
    }
    private static Decision commit(CarrierExpansionLedger ledger,CarrierExpansionPolicy.Module current,
                                   CarrierExpansionPolicy.Module replacement,String witness,long tick){
        var token=ledger.begin(current.id());
        if(token.isEmpty())return new Decision(false,CarrierExpansionLedger.Status.QUARANTINED,"IDENTITY_QUARANTINED");
        return decision(ledger.commit(token.get(),replacement,witness,tick));
    }
    private static Decision decision(CarrierExpansionLedger.Result result){
        return new Decision(result.success(),result.status(),result.detail());
    }
    private static String dimension(ServerLevel level){return level.dimension().identifier().toString();}
    private static String blockWitness(ServerLevel level,BlockPos pos){return "block/"+dimension(level)+"/"+pos.asLong();}
    private static String carrierWitness(UUID carrier,int hardpoint){return "carrier/"+carrier+"/"+hardpoint;}
}
