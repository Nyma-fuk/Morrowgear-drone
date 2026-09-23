package jp.morrowgear.drone.client;

import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.client.CarrierEffectGeometry.*;

public final class CarrierEffectChecks {
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static List<Point> vertices(Quad q) { return List.of(q.a(), q.b(), q.c(), q.d()); }
    static Point minus(Point a, Point b) { return a.add(b.scale(-1)); }
    static Point cross(Point a, Point b) { return new Point(a.y()*b.z()-a.z()*b.y(), a.z()*b.x()-a.x()*b.z(), a.x()*b.y()-a.y()*b.x()); }
    static double dot(Point a, Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    static final Point START = new Point(8, 100, 8), END = new Point(.5, -63.5, 15.5);
    static final Column COLUMN = new Column(0, 16, 0, 16);
    public static void main(String[] args) {
        switch (args[0]) {
            case "work_column" -> {
                var broad = CarrierEffectGeometry.workColumn(START,-63,COLUMN,0);
                check(broad.size()==9,"four expansion faces, four vertical walls, footprint");
                for (Quad q : broad) for (Point p : vertices(q))
                    check(p.finite() && COLUMN.contains(p) && p.y()>=-63 && p.y()<=100,"column inside approved volume");
                Quad floor=broad.getLast();
                check(Math.abs(floor.b().x()-floor.a().x())==16 && Math.abs(floor.d().z()-floor.a().z())==16,"entire 16x16 footprint");
                check(CarrierEffectGeometry.workColumn(START,101,COLUMN,0).isEmpty(),"no upward mining");
                check(CarrierEffectGeometry.workColumn(START,0,COLUMN,8).isEmpty(),"no inverted footprint");
                check(CarrierEffectGeometry.workColumn(START,Double.NaN,COLUMN,0).isEmpty(),"finite geometry");
                check(CarrierEffectGeometry.workColumn(START,99,COLUMN,0).size()==5,"short column does not invert shoulder");
                var area = CarrierEffectGeometry.areaBeam(START,new Point(8,0,8),8);
                check(area.size()==12,"bounded area beam geometry");
                for(Quad q : area) {
                    check(Math.abs(Math.hypot(q.a().x()-8,q.a().z()-8)-1.15)<1e-8,"lens matches physical aperture");
                    check(Math.abs(Math.hypot(q.c().x()-8,q.c().z()-8)-8)<1e-8,"impact matches server damage radius");
                }
            }
            case "aperture" -> {
                var beam = CarrierEffectGeometry.beam(START, END, 30, null);
                check(beam.size() == 12, "12 broad beam faces");
                for (Quad q : beam) for (Point p : vertices(q)) {
                    double t = (START.y()-p.y())/(START.y()-END.y());
                    double x = START.x()*(1-t)+END.x()*t, z = START.z()*(1-t)+END.z()*t;
                    check(Math.hypot(p.x()-x,p.z()-z) <= 1.15+1e-9, "inside aperture radius");
                    check(p.y() <= START.y() && p.y() >= END.y(), "below emitter, no overshoot");
                }
            }
            case "chunk_clip" -> {
                for (double sx : new double[]{0,.01,8,15.99,16}) for (double sz : new double[]{0,.01,8,15.99,16})
                    for (double ex : new double[]{.01,8,15.99}) for (double ez : new double[]{.01,8,15.99}) {
                        var beam = CarrierEffectGeometry.beam(new Point(sx,100,sz), new Point(ex,-60,ez), 1.15, COLUMN);
                        check(!beam.isEmpty(), "valid corner beam retained");
                        check(beam.size() <= 72, "bounded clipped polygons");
                        for (Quad q : beam) for (Point p : vertices(q)) {
                            check(p.finite() && COLUMN.contains(p), "every actual emitted vertex stays inside 16x16");
                            check(p.y() >= -60 && p.y() <= 100, "clipped height");
                        }
                    }
            }
            case "negative_chunk" -> {
                Column negative = new Column(-32,-16,-16,0);
                for (Quad q : CarrierEffectGeometry.beam(new Point(-31.99,500,-.01), new Point(-16.01,-200,-15.99),1.15,negative))
                    for (Point p : vertices(q)) check(negative.contains(p), "negative world chunk clipping");
            }
            case "invalid_rays" -> {
                for (Point end : List.of(START, new Point(8,101,8), new Point(Double.NaN,1,0), new Point(99,0,0)))
                    check(CarrierEffectGeometry.beam(START,end,1.15,COLUMN).isEmpty(), "invalid ray emits nothing");
                check(CarrierEffectGeometry.beam(START,END,Double.NaN,COLUMN).isEmpty(), "invalid radius");
                check(!CarrierEffectGeometry.beam(START,new Point(80,10,8),1.15,null).isEmpty(), "combat uses individual downward rays, not mining column");
            }
            case "winding" -> {
                Point start = new Point(0,20,0), end = new Point(0,0,0);
                for (Quad q : CarrierEffectGeometry.beam(start,end,1,null)) {
                    Point n = cross(minus(q.b(),q.a()),minus(q.c(),q.a()));
                    Point midpoint = q.a().add(q.b()).scale(.5);
                    check(dot(n,new Point(midpoint.x(),0,midpoint.z())) > 0,"outward tube winding");
                }
                for (Quad q : CarrierEffectGeometry.disc(start,1,.5,null))
                    check(cross(minus(q.b(),q.a()),minus(q.c(),q.a())).y()<0,"lens underside faces down");
            }
            case "budget" -> {
                int beam = CarrierEffectGeometry.beam(START, END, 1.15, null).size();
                int disc = CarrierEffectGeometry.disc(START,1,.5,null).size();
                check(16*(beam*3+disc)+disc*3==804,"16-ray pass budget, no distance-driven subdivisions");
            }
            case "phase_progress" -> {
                check(CarrierEffectGeometry.progress(30,60,0)==.5,"server half charge");
                check(CarrierEffectGeometry.progress(30,60,200)==2.0/3,"extrapolation capped to heartbeat");
                check(CarrierEffectGeometry.progress(80,60,0)==1,"no phase overflow");
                check(CarrierEffectGeometry.progress(5,0,0)==0,"untimed mining");
            }
            case "baseline" -> {
                var timeline = new CarrierEffectTimeline(); UUID id = UUID.randomUUID();
                var first = timeline.observe(id,80000,900,9000,2,END);
                check(first.fresh() && first.interpolation()==0,"late joining uses local receive age");
                check(!Double.isFinite(first.captureAge()) && first.position()==null,"first historical counts never emit collection");
                var next = timeline.observe(id,80001,901,9001,3,END);
                check(next.captureAge()==0 && next.position().equals(END),"confirmed delta starts capture at actual block");
            }
            case "continuous_capture" -> {
                var timeline = new CarrierEffectTimeline(); UUID id = UUID.randomUUID();
                timeline.observe(id,0,0,0,0,START);
                timeline.observe(id,1,1,1,1,END);
                var next = timeline.observe(id,2,32,32,2,START);
                check(next.captureAge()==1 && next.position().equals(END),"busy mining does not restart or teleport in-flight capture");
                var later = timeline.observe(id,20,64,64,20,START);
                check(later.captureAge()==0 && later.position().equals(START),"next bounded sweep uses latest confirmed block");
            }
            case "reset_and_empty" -> {
                var timeline = new CarrierEffectTimeline(); UUID id = UUID.randomUUID();
                timeline.observe(id,100,50,50,0,START);
                var empty = timeline.observe(id,101,51,50,1,END);
                check(empty.position()==null,"zero-drop block does not pretend to collect items");
                timeline.observe(id,102,52,52,2,END);
                var reset = timeline.observe(id,1,0,0,3,START);
                check(reset.position()==null,"reload resets collection");
            }
            case "stale_and_budget" -> {
                var timeline = new CarrierEffectTimeline(); UUID id = UUID.randomUUID();
                timeline.observe(id,100,50,50,0,START);
                var stale = timeline.observe(id,100,50,50,21,START);
                check(!stale.fresh() && stale.interpolation()==10,"stalled sync cannot sustain light indefinitely");
                for(int i=0;i<300;i++) timeline.observe(new UUID(0,i),1,0,0,0,START);
                check(timeline.size()==128,"bounded identity cache");
            }
            case "map_clip" -> {
                check(CarrierMapLine.clip(-999,-999,-1,-1,0,0,319,179)==null,"offscreen line costs nothing");
                double[] line = CarrierMapLine.clip(-999,90,999,90,0,0,319,179);
                check(line!=null && Math.abs(line[0])<1e-9 && Math.abs(line[2]-319)<1e-9,"huge search radius bounded to screen");
                check(CarrierMapLine.clip(Double.NaN,0,1,1,0,0,319,179)==null,"invalid line");
                for(int x=-500;x<600;x+=33) for(int y=-500;y<600;y+=33) {
                    double[] l = CarrierMapLine.clip(x,y,160,90,6,54,313,139);
                    if(l!=null) for(int i=0;i<4;i+=2) check(l[i]>=6-1e-9 && l[i]<=313+1e-9 && l[i+1]>=54-1e-9 && l[i+1]<=139+1e-9,"map scissor bound");
                }
            }
            default -> throw new AssertionError(args[0]);
        }
    }
}
