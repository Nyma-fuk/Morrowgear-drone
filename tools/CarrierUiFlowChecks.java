import java.util.UUID;
import jp.morrowgear.drone.CarrierUiPolicy;
import jp.morrowgear.drone.CarrierUiPolicy.*;
import jp.morrowgear.drone.CarrierUiInputPolicy;

public final class CarrierUiFlowChecks {
    static final UUID SHIP = new UUID(1,1), OLD = new UUID(2,2), NEW = new UUID(3,3), LATER = new UUID(4,4);
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static Snapshot snapshot(UUID generation, int preview, int tick) {
        return new Snapshot(SHIP,true,false,0,false,generation,"test:world",-1,2,-64,94,2,preview,7,tick);
    }
    static Snapshot change(Snapshot s, int menu, UUID ship, int mode, boolean owner, boolean destroyed, boolean moving, int cx, int cz, int previewMode) {
        return new Snapshot(ship,owner,destroyed,mode,moving,s.generation(),s.dimension(),cx,cz,s.minY(),s.maxY(),previewMode,s.previewTicks(),menu,s.serverTick());
    }
    static CarrierUiPolicy opened() {
        CarrierUiPolicy p = new CarrierUiPolicy(7,SHIP);
        p.accept(snapshot(OLD,0,100),1000); p.select(-1,2,"test:world");
        return p;
    }
    static CarrierUiPolicy waiting() {
        var p = opened();
        check(p.queuePreview(2,false,1010),"one preview intent accepted");
        p.accept(snapshot(OLD,0,110),1500);
        var sent = p.pollCommand(1500);
        check(sent != null && sent.kind()==CommandKind.PREVIEW && sent.mode()==2,"preview dispatched after server tick progress");
        return p;
    }
    static CarrierUiPolicy confirmed() {
        var p = waiting(); p.accept(snapshot(NEW,200,110),1510);
        check(p.phase()==Phase.CONFIRM && !p.consent(),"only matching acknowledgement shows unconsented confirmation");
        return p;
    }
    static final Destination TARGET = new Destination("test:world",-24,120,72);
    static Snapshot navigation(Snapshot s, Destination target, boolean paused, int reason, boolean moving) {
        return new Snapshot(s.ship(),s.owner(),s.destroyed(),s.mode(),moving,s.generation(),s.dimension(),s.chunkX(),s.chunkZ(),
            s.minY(),s.maxY(),s.previewMode(),s.previewTicks(),s.menu(),s.serverTick(),new Navigation(target,paused,reason));
    }
    static CarrierUiPolicy moveWaiting() {
        var p = opened(); check(p.queueMove(TARGET,1010),"move intent accepted");
        p.accept(snapshot(OLD,0,110),1500);
        var command = p.pollCommand(1500);
        check(command != null && command.kind()==CommandKind.MOVE && command.generation()==null && command.destination().equals(TARGET),
            "move captures exact destination and has no preview generation");
        return p;
    }
    public static void main(String[] args) {
        switch(args[0]) {
            case "move_queue" -> {
                var p = opened(); p.queueMove(TARGET,1010);
                check(p.phase()==Phase.QUEUED_MOVE && p.pollCommand(1200)==null,"fast move click remains queued");
                check(!p.queueMove(new Destination("test:world",8,88,8),1201),"second click cannot overwrite queued target");
                p.accept(snapshot(OLD,0,105),2000); check(p.pollCommand(2000)==null,"move waits six sampled server ticks");
                p.accept(snapshot(OLD,0,106),2100); var command = p.pollCommand(2100);
                check(command.destination().equals(TARGET) && p.pollCommand(2101)==null,"exact XYZ sends once");
                p.accept(navigation(snapshot(OLD,0,107),TARGET,false,2,true),2150);
                check(p.moveResult()==MoveResult.ACCEPTED && !p.busy(),"matching authoritative navigation acknowledges move");
            }
            case "move_scope" -> {
                var p = moveWaiting();
                var s = navigation(snapshot(OLD,0,111),TARGET,false,2,true);
                p.accept(new Snapshot(new UUID(9,9),true,false,0,true,OLD,"test:world",-1,2,-64,94,2,0,7,111,s.navigation()),1510);
                p.accept(new Snapshot(SHIP,true,false,0,true,OLD,"test:world",-1,2,-64,94,2,0,8,111,s.navigation()),1520);
                p.accept(navigation(snapshot(OLD,0,109),TARGET,false,2,true),1530);
                for (var wrong : new Destination[]{new Destination("test:other",-24,120,72),new Destination("test:world",-24,121,72),new Destination("test:world",-23,120,72)})
                    p.accept(navigation(snapshot(OLD,0,111),wrong,false,2,true),1540);
                check(p.phase()==Phase.WAITING_MOVE && p.busy(),"old sample/wrong menu/ship/dimension/XYZ cannot acknowledge move");
                p.accept(s,1550); check(p.moveResult()==MoveResult.ACCEPTED,"correct scoped destination accepted");
            }
            case "move_saved" -> {
                var p = opened(); p.accept(navigation(snapshot(OLD,0,110),TARGET,true,3,false),1500);
                check(p.queueMove(TARGET,1501),"paused saved destination can be resumed");
                check(p.pollCommand(1501)!=null,"cooled down resume sends");
                p.accept(navigation(snapshot(OLD,0,110),TARGET,true,3,false),1510);
                check(p.phase()==Phase.WAITING_MOVE,"unchanged saved destination is not resume success");
                p.accept(navigation(snapshot(OLD,0,111),TARGET,false,2,true),1550);
                check(p.moveResult()==MoveResult.ACCEPTED,"unpaused matching destination acknowledges resume");
            }
            case "move_stopped" -> {
                var p = moveWaiting();
                p.accept(navigation(snapshot(OLD,0,111),TARGET,true,10,false),1550);
                check(p.moveResult()==MoveResult.STOPPED && p.moveStopReason()==10 && !p.busy(),"authoritative paused/no-power reason is not move success");
                var q = moveWaiting(); q.accept(navigation(snapshot(OLD,0,111),null,false,9,false),1550);
                check(q.moveResult()==MoveResult.STOPPED && q.moveStopReason()==9,"changed rejection reason without destination is reported");
            }
            case "move_timeout" -> {
                var p = moveWaiting(); p.accept(snapshot(OLD,0,130),2500); p.tick(4001);
                check(p.moveResult()==MoveResult.UNCONFIRMED && p.reopenRequired(),"unchanged heartbeat is not success, timeout requires isolated reopening");
                p.accept(navigation(snapshot(OLD,0,170),TARGET,false,2,true),4050);
                check(p.moveResult()!=MoveResult.ACCEPTED && p.reopenRequired(),"late move response does not relabel timed-out request as successful");
                var q = opened(); q.queueMove(TARGET,1010); q.tick(2600);
                check(q.moveResult()==MoveResult.UNCONFIRMED && !q.reopenRequired() && !q.busy(),"unsent stale intent reports not sent and can be explicitly retried");
            }
            case "move_cancel" -> {
                for (boolean otherCommand : new boolean[]{false,true}) {
                    var p = opened(); p.queueMove(TARGET,1010);
                    if (otherCommand) p.cancelMove(); else p.cancel();
                    p.accept(snapshot(OLD,0,110),1500);
                    check(p.pollCommand(1500)==null && !p.busy(),"tab/STOP/other command cancels unsent move");
                }
                var p = moveWaiting(); p.cancelMove();
                check(!p.queueMove(TARGET,1510),"cancelled in-flight move drains before another request");
                p.accept(navigation(snapshot(OLD,0,111),TARGET,true,3,false),1550);
                check(!p.busy() && p.moveResult()!=MoveResult.ACCEPTED,"STOP response drains pending move without claiming success");
            }
            case "move_exclusive" -> {
                var p = moveWaiting();
                check(!p.queuePreview(2,false,1510) && !p.queueMove(TARGET,1510),"pending move excludes attack and duplicate move");
                var q = waiting(); check(!q.queueMove(TARGET,1510),"pending preview excludes movement");
                var r = opened(); r.queueMove(TARGET,1010);
                r.accept(change(snapshot(OLD,0,110),7,SHIP,0,false,false,false,-1,2,2),1500);
                check(r.pollCommand(1500)==null && !r.busy(),"loss of authority cancels queued move");
            }
            case "unsolicited_preview" -> {
                var p = opened(); p.accept(snapshot(NEW,200,110),1500); p.consent(true);
                check(p.phase()==Phase.SELECT && !p.canStart(1500),"server-opened PREVIEW alone cannot auto-confirm");
            }
            case "queued_preview" -> {
                var p = opened(); p.queuePreview(2,false,1010);
                check(p.phase()==Phase.QUEUED_PREVIEW && p.pollCommand(1100)==null,"cooldown click stays queued");
                p.accept(snapshot(OLD,190,105),1300);
                check(p.phase()==Phase.QUEUED_PREVIEW && p.pollCommand(1300)==null,"old preview heartbeat is not an ack and five ticks are insufficient");
                p.accept(snapshot(OLD,180,110),1500);
                check(p.pollCommand(1500)!=null && p.pollCommand(1501)==null,"one intent sends once");
                check(!p.queuePreview(2,false,1502),"duplicate preview cannot overwrite in-flight request");
            }
            case "fast_start" -> {
                var p = confirmed(); p.consent(true);
                check(p.queueStart(1520),"quick consent/start click must be retained");
                check(p.phase()==Phase.QUEUED_START && p.pollCommand(1530)==null,"no premature ACTIVATE inside server cooldown");
                check(!p.queueStart(1540),"double start cannot create another request");
                p.accept(snapshot(NEW,190,120),2010);
                var start = p.pollCommand(2010);
                check(start!=null && start.kind()==CommandKind.START && start.generation().equals(NEW),"queued start sends approved generation without second click");
                check(p.pollCommand(2020)==null && p.phase()==Phase.SENT,"at most one activation");
                p.accept(change(snapshot(NEW,0,121),7,SHIP,2,true,false,false,-1,2,2),2060);
                check(p.phase()==Phase.SELECT && !p.busy(),"only matching running mode acknowledges activation");
            }
            case "slow_server" -> {
                var p = opened(); p.queuePreview(2,false,1010);
                p.accept(snapshot(OLD,0,100),2000);
                check(p.pollCommand(2000)==null,"wall clock alone cannot bypass a paused server cooldown");
                p.accept(snapshot(OLD,0,105),2200); check(p.pollCommand(2200)==null,"five sampled ticks still guarded");
                p.accept(snapshot(OLD,0,106),2300); check(p.pollCommand(2300)!=null,"send once server clock safely progresses");
            }
            case "cancel_queued" -> {
                var p = opened(); p.queuePreview(2,false,1010); p.cancel();
                p.accept(snapshot(OLD,0,110),1500);
                check(p.pollCommand(1500)==null && !p.busy(),"back/tab/modal cancels unsent work");
            }
            case "cancel_late_ack" -> {
                var p = waiting(); p.cancel();
                check(!p.queuePreview(2,false,1510),"new preview cannot steal cancelled in-flight reply");
                p.accept(snapshot(NEW,200,110),1520);
                check(p.phase()==Phase.SELECT && !p.busy() && !p.canStart(1520),"late cancelled reply discarded");
                check(p.queuePreview(2,false,1530),"operator may explicitly retry after draining reply");
                p.accept(snapshot(NEW,190,120),2010); check(p.pollCommand(2010)!=null,"new preview dispatched");
                p.accept(snapshot(NEW,180,130),2510); check(p.phase()==Phase.WAITING,"same generation cannot acknowledge retry");
                p.accept(snapshot(LATER,200,131),2560); check(p.phase()==Phase.CONFIRM,"new retry generation accepted");
            }
            case "unknown_timeout" -> {
                var p = waiting(); p.tick(4001);
                check(p.reopenRequired() && !p.queuePreview(2,false,4001),"unknown request must not auto-retry against late acknowledgement");
                p.accept(snapshot(NEW,200,170),4050);
                check(p.phase()!=Phase.CONFIRM && !p.canStart(4050),"late timed-out ack never revives consent");
                var fresh = new CarrierUiPolicy(8,SHIP);
                fresh.accept(snapshot(NEW,200,170),4050);
                check(!fresh.fresh(4050),"old menu reply ignored after reopening");
                fresh.accept(change(snapshot(NEW,200,170),8,SHIP,0,true,false,false,-1,2,2),4060);
                check(fresh.fresh(4060) && fresh.phase()==Phase.SELECT,"new menu establishes fresh isolated baseline");
            }
            case "scope_and_order" -> {
                var p = waiting(); var s = snapshot(NEW,200,111);
                p.accept(change(s,6,SHIP,0,true,false,false,-1,2,2),1510);
                p.accept(change(s,7,new UUID(9,9),0,true,false,false,-1,2,2),1520);
                p.accept(snapshot(NEW,200,109),1530);
                check(p.phase()==Phase.WAITING,"wrong menu, ship and older sample cannot confirm");
                p.accept(s,1540); check(p.phase()==Phase.CONFIRM,"live scoped ack accepted");
            }
            case "ack_authority" -> {
                for (int condition=0;condition<4;condition++) {
                    var p = waiting();
                    p.accept(change(snapshot(NEW,200,111),7,SHIP,condition==3?2:0,condition!=0,condition==1,condition==2,-1,2,2),1510);
                    check(p.phase()!=Phase.CONFIRM && !p.canStart(1510),"invalid initial acknowledgement authority "+condition);
                }
            }
            case "ack_target" -> {
                var p = waiting(); var s = snapshot(NEW,200,111);
                p.accept(change(s,7,SHIP,0,true,false,false,-2,2,2),1510);
                p.accept(change(s,7,SHIP,0,true,false,false,-1,2,1),1520);
                check(p.phase()==Phase.WAITING,"wrong target or mode does not confirm");
                p.accept(s,1530); check(p.phase()==Phase.CONFIRM,"correct target acknowledged");
            }
            case "queued_start_revoked" -> {
                var p = confirmed(); p.consent(true); p.queueStart(1520);
                p.accept(snapshot(LATER,200,120),2010);
                check(p.expired() && p.pollCommand(2010)==null,"changed generation revokes queued start");
                var q = confirmed(); q.consent(true); q.queueStart(1520);
                q.accept(snapshot(NEW,1,111),1525); q.tick(1575);
                check(q.expired() && q.pollCommand(2010)==null,"expired range never dispatches late start");
            }
            case "start_ack_identity" -> {
                var p = confirmed(); p.consent(true); p.queueStart(1520);
                p.accept(snapshot(NEW,190,120),2010); p.pollCommand(2010);
                p.accept(snapshot(NEW,180,130),2500);
                check(p.phase()==Phase.SENT && p.busy(),"unrelated idle heartbeat is not activation success");
                p.accept(change(snapshot(LATER,0,131),7,SHIP,2,true,false,false,-1,2,2),2550);
                check(p.expired(),"another generation running is not claimed as successful start");
            }
            case "saved_generation" -> {
                var p = opened(); p.queuePreview(1,true,1010);
                p.accept(snapshot(OLD,0,110),1500); var command = p.pollCommand(1500);
                check(command.kind()==CommandKind.RESUME,"saved mining remains a distinct request");
                p.accept(change(snapshot(OLD,200,111),7,SHIP,0,true,false,false,-1,2,1),1510);
                check(p.phase()==Phase.CONFIRM && !p.canStart(1510),"renewed server preview of saved generation requires consent");
                var q = opened(); q.accept(snapshot(OLD,200,110),1500); q.requestSaved(2,1510);
                q.accept(snapshot(OLD,200,110),1520);
                check(q.phase()==Phase.WAITING,"identical existing preview is not fresh saved-preview acknowledgement");
            }
            case "selection_cancels" -> {
                var p = opened(); p.queuePreview(2,false,1010); p.select(0,0,"test:world");
                p.accept(snapshot(OLD,0,110),1500);
                check(p.pollCommand(1500)==null,"target change cancels queued intent");
            }
            case "destroyed_cargo" -> {
                var p = waiting();
                p.accept(change(snapshot(OLD,0,0),7,SHIP,0,true,true,false,-1,2,2),1600);
                check(p.cargoAccessible(1600),"destroyed exterior resets sample clock without locking owned recovery cargo");
                check(!p.busy() && !p.reopenRequired(),"obsolete live operation cannot block recovery pages");
                check(!p.queuePreview(2,false,1600) && !p.canStart(1600),"recovery access must not authorize operations on destroyed ship");
                p.accept(change(snapshot(OLD,0,0),7,SHIP,0,true,true,false,-1,2,2),2200);
                check(p.cargoAccessible(2200),"destroyed cargo heartbeat remains usable across pages");
                check(!p.cargoAccessible(3701),"stale recovery state cannot authorize page commands");
            }
            case "cargo_scope" -> {
                var p = new CarrierUiPolicy(7,SHIP);
                var destroyed = change(snapshot(OLD,0,0),8,SHIP,0,true,true,false,-1,2,2);
                p.accept(destroyed,1000);
                check(!p.cargoAccessible(1000),"different menu cannot authorize cargo recovery");
                p.accept(change(destroyed,7,SHIP,0,false,true,false,-1,2,2),1010);
                check(!p.cargoAccessible(1010),"guest cannot page destroyed owner cargo");
                var q = waiting(); q.tick(4001);
                check(!q.cargoAccessible(4001),"unresolved live command cannot bypass reopening through cargo controls");
            }
            case "opening_enter" -> {
                var input = new CarrierUiInputPolicy();
                check(!input.press(257,true),"inherited initial focus must not execute opening Return");
                input.choose(); check(!input.press(257,true),"held Return cannot fire after navigation");
                input.release(257); check(input.press(257,true),"new deliberate Enter after selection works");
                check(!input.press(257,true),"key repeat blocked");
            }
            case "rebuilt_keys" -> {
                var input = new CarrierUiInputPolicy(); input.choose(); check(input.press(32,true),"space toggles selected consent once");
                input.rebuilt(); input.choose(); check(!input.press(32,true),"rebuild cannot turn held consent key into start");
                input.release(32); check(input.press(32,true),"new deliberate key after release works");
                input.release(32); input.rebuilt(); check(!input.press(32,true),"new surface needs an explicit control selection");
            }
            case "native_input" -> {
                check(!CarrierUiInputPolicy.controlClick(true,true),"control double activation suppressed");
                check(CarrierUiInputPolicy.controlClick(false,true),"native inventory double-click preserved");
                var input = new CarrierUiInputPolicy(); check(input.press(49,false),"hotbar keys unchanged");
                input.choose(); check(!input.press(335,false),"Enter without focused control cannot create one");
            }
            case "projection_restore" -> {
                Rect map = CarrierUiPolicy.mapRect(480,270,false), confirm = CarrierUiPolicy.mapRect(480,270,true);
                var before = CarrierUiPolicy.projection(map,123,456,2.5,false,-1,2,96);
                var approved = CarrierUiPolicy.projection(confirm,123,456,2.5,true,-1,2,96);
                check(approved.centerX()==-8 && approved.centerZ()==40 && approved.zoom()*192<=78,"confirmation fits approved circle");
                var after = CarrierUiPolicy.projection(map,123,456,2.5,false,-1,2,96);
                check(before.equals(after),"confirmation projection does not mutate selection zoom/center");
            }
            case "odd_projection" -> {
                Rect r = new Rect(6,54,467,175);
                for(double zoom : new double[]{.25,1,2.5,8}) for(int cx=-6;cx<6;cx++) for(int cz=-6;cz<6;cz++) {
                    var p = CarrierUiPolicy.projection(r,-8,40,zoom,false,cx,cz,0);
                    check(p.chunkX(r,p.x(r,cx*16+8))==cx && p.chunkZ(r,p.z(r,cz*16+8))==cz,"rendered chunk center matches click at odd viewport size");
                }
            }
            default -> throw new AssertionError(args[0]);
        }
    }
}
