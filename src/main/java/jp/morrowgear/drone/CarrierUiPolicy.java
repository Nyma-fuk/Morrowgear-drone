package jp.morrowgear.drone;

import java.util.Objects;
import java.util.UUID;

/** Client confirmation state without Minecraft dependencies. No command is sent by this policy. */
public final class CarrierUiPolicy {
    public enum Phase { SELECT, QUEUED_PREVIEW, WAITING, CONFIRM, EXPIRED_CONFIRM, QUEUED_START, SENT, QUEUED_MOVE, WAITING_MOVE }
    public enum MoveResult { NONE, PENDING, ACCEPTED, STOPPED, UNCONFIRMED, CANCELLED }
    public record Destination(String dimension, int x, int y, int z) {}
    public record Navigation(Destination destination, boolean paused, int stopReason, int commandRevision) {
        public static final Navigation EMPTY = new Navigation(null, false, 0, 0);
        public Navigation(Destination destination, boolean paused, int stopReason) { this(destination, paused, stopReason, 0); }
    }
    public record Snapshot(UUID ship, boolean owner, boolean destroyed, int mode, boolean moving,
                           UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY,
                           int previewMode, int previewTicks, int menu, int serverTick, Navigation navigation) {
        public Snapshot(UUID ship, boolean owner, boolean destroyed, int mode, boolean moving,
                        UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY,
                        int previewMode, int previewTicks, int menu, int serverTick) {
            this(ship, owner, destroyed, mode, moving, generation, dimension, chunkX, chunkZ, minY, maxY,
                previewMode, previewTicks, menu, serverTick, Navigation.EMPTY);
        }
        public Snapshot(UUID ship, boolean owner, boolean destroyed, int mode, boolean moving,
                        UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY,
                        int previewMode, int previewTicks) {
            this(ship, owner, destroyed, mode, moving, generation, dimension, chunkX, chunkZ, minY, maxY, previewMode, previewTicks, 0, 0);
        }
    }
    public enum CommandKind { PREVIEW, RESUME, START, MOVE }
    public record Command(CommandKind kind, int mode, UUID generation, String dimension, int chunkX, int chunkZ, Destination destination) {
        public Command(CommandKind kind, int mode, UUID generation, String dimension, int chunkX, int chunkZ) {
            this(kind, mode, generation, dimension, chunkX, chunkZ, null);
        }
    }
    private record Pending(Command command, Snapshot before, long sentAt) {}
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) { return px >= x && py >= y && px < right() && py < bottom(); }
    }
    public static final long FRESH_MILLIS = 1500;
    private Snapshot latest, approved;
    private long receivedAt, expiresAt, requestAt, lastCommandAt;
    private int chunkX, chunkZ, lastCommandTick;
    private Integer menu;
    private UUID ship;
    private String dimension;
    private boolean selected, consent, expired;
    private boolean reopenRequired;
    private Command queued;
    private Pending pending;
    private Snapshot starting;
    private MoveResult moveResult = MoveResult.NONE;
    private int moveStopReason;
    private Phase phase = Phase.SELECT;

    public CarrierUiPolicy() {}
    public CarrierUiPolicy(int menu, UUID ship) { this.menu = menu; this.ship = ship; }

    public void select(int x, int z, String dimension) {
        cancel();
        this.chunkX = x; this.chunkZ = z; this.dimension = dimension; selected = true;
    }
    // Retain an already-sent request until its reply is consumed; cancellation cannot make a late reply a new approval.
    public void cancel() {
        if (moveResult == MoveResult.PENDING) moveResult = MoveResult.CANCELLED;
        else if (moveResult != MoveResult.UNCONFIRMED && (pending == null || pending.command.kind != CommandKind.MOVE)) moveResult = MoveResult.NONE;
        phase = Phase.SELECT; approved = null; consent = false; expired = false; queued = null;
    }
    public void cancelMove() {
        if (queued != null && queued.kind == CommandKind.MOVE || pending != null && pending.command.kind == CommandKind.MOVE) cancel();
    }
    public void clear() { cancel(); selected = false; }
    public boolean request(int mode, long now) { return request(mode, false, now); }
    public boolean requestSaved(int mode, long now) { return request(mode, true, now); }
    private boolean request(int mode, boolean saved, long now) {
        if (pending != null || starting != null || reopenRequired || !selected || !idleOwner(latest) || !fresh(now)) return false;
        Command command = new Command(saved ? CommandKind.RESUME : CommandKind.PREVIEW, mode,
            latest.generation(), dimension, chunkX, chunkZ);
        consent = false; expired = false; approved = null; queued = null;
        pending = new Pending(command, latest, now);
        requestAt = now; phase = Phase.WAITING; noteCommand(now);
        return true;
    }
    public boolean queuePreview(int mode, boolean saved, long now) {
        if (phase != Phase.SELECT && phase != Phase.EXPIRED_CONFIRM || busy() || reopenRequired
            || !selected || !idleOwner(latest) || !fresh(now) || mode < 1 || mode > 2) return false;
        queued = new Command(saved ? CommandKind.RESUME : CommandKind.PREVIEW, mode, latest.generation(), dimension, chunkX, chunkZ);
        approved = null; consent = false; phase = Phase.QUEUED_PREVIEW; requestAt = now; expired = false; moveResult = MoveResult.NONE;
        return true;
    }
    public boolean queueStart(long now) {
        if (!canStart(now)) return false;
        queued = new Command(CommandKind.START, approved.previewMode(), approved.generation(), dimension, chunkX, chunkZ);
        phase = Phase.QUEUED_START; requestAt = now;
        return true;
    }
    public boolean queueMove(Destination destination, long now) {
        if (phase != Phase.SELECT || busy() || reopenRequired || !idleOwner(latest) || !fresh(now)
            || destination == null || !destination.dimension.equals(latest.dimension())) return false;
        queued = new Command(CommandKind.MOVE, 0, null, destination.dimension,
            Math.floorDiv(destination.x, 16), Math.floorDiv(destination.z, 16), destination);
        phase = Phase.QUEUED_MOVE; requestAt = now; expired = false; moveResult = MoveResult.PENDING;
        return true;
    }
    /** Returns at most one intent. Transport must use this screen's live menu, never a stored menu from another opening. */
    public Command pollCommand(long now) {
        tick(now);
        if (queued == null || !commandReady(now)) return null;
        Command command = queued;
        if (command.kind == CommandKind.MOVE) {
            if (!idleOwner(latest) || !command.dimension.equals(latest.dimension())) { invalidate(); return null; }
            queued = null; pending = new Pending(command, latest, now);
            phase = Phase.WAITING_MOVE; noteCommand(now);
            return command;
        }
        if (!selected || command.chunkX != chunkX || command.chunkZ != chunkZ || !command.dimension.equals(dimension)
            || (command.kind == CommandKind.START && !sameSelection(latest))
            || (command.kind == CommandKind.RESUME && !command.generation.equals(latest.generation()))
            || !idleOwner(latest) || !fresh(now)) {
            invalidate(); return null;
        }
        if (command.kind == CommandKind.START) {
            if (!validApproval(now)) { invalidate(); return null; }
            queued = null; sent(now);
        } else if (!request(command.mode, command.kind == CommandKind.RESUME, now)) return null;
        return command;
    }
    public boolean commandReady(long now) {
        return latest != null && fresh(now) && now - lastCommandAt >= 300
            && (long) latest.serverTick() - lastCommandTick >= 6;
    }
    public void noteCommand(long now) {
        lastCommandAt = now;
        if (latest != null) lastCommandTick = latest.serverTick();
    }
    public void accept(Snapshot next, long now) {
        // Extended screen-opening data is created before Minecraft assigns the live
        // container id. Adopt that one bootstrap snapshot for this screen only;
        // every subsequent update still has to carry the exact live menu id.
        if (next.menu() == -1 && latest == null && menu != null && next.ship().equals(ship)) {
            next = withMenu(next, menu);
        }
        if (menu == null) { menu = next.menu(); ship = next.ship(); }
        if (next.menu() != menu || !next.ship().equals(ship)) return;
        // Destroyed ships no longer have an exterior entity and therefore report sample tick zero.
        if (latest != null && next.serverTick() < latest.serverTick() && !next.destroyed()) return;
        if (latest != null && !latest.dimension().equals(next.dimension())) clear();
        boolean first = latest == null;
        latest = next; receivedAt = now;
        if (first) noteCommand(now);
        if (pending != null && pending.command.kind == CommandKind.MOVE) acceptMove(next, now);
        if (pending != null && pending.command.kind != CommandKind.MOVE && acknowledges(pending, next)) {
            boolean wanted = phase == Phase.WAITING && selected && sameSelection(next) && !reopenRequired;
            pending = null; noteCommand(now);
            if (wanted && idleOwner(next)) {
                approved = next; expiresAt = now + next.previewTicks() * 50L; phase = Phase.CONFIRM;
            } else if (wanted) invalidate();
        } else if (pending != null && pending.command.kind != CommandKind.MOVE
            && next.navigation().commandRevision() != pending.before.navigation().commandRevision()) {
            pending = null;
            invalidate();
        }
        if (phase == Phase.CONFIRM || phase == Phase.QUEUED_START) {
            if (!idleOwner(next)) invalidate();
            else if (!samePreview(approved, next) || next.previewTicks() <= 0) expireApproval();
            else expiresAt = Math.min(expiresAt, now + next.previewTicks() * 50L);
        }
        if (starting != null && next.mode() == starting.previewMode() && sameRange(starting, next)) {
            starting = null; cancel(); noteCommand(now);
        } else if (starting != null
            && next.navigation().commandRevision() != starting.navigation().commandRevision()) {
            starting = null; invalidate();
        } else if (starting != null && next.mode() != 0) { starting = null; invalidate(); }
        if (!next.owner() || next.destroyed()) {
            pending = null; starting = null; reopenRequired = false;
            invalidate();
        }
    }
    private static Snapshot withMenu(Snapshot source, int menu) {
        return new Snapshot(source.ship(), source.owner(), source.destroyed(), source.mode(), source.moving(),
            source.generation(), source.dimension(), source.chunkX(), source.chunkZ(), source.minY(), source.maxY(),
            source.previewMode(), source.previewTicks(), menu, source.serverTick(), source.navigation());
    }
    private void acceptMove(Snapshot next, long now) {
        Navigation nav = next.navigation(), before = pending.before.navigation();
        boolean target = Objects.equals(pending.command.destination, nav.destination());
        boolean accepted = target && next.moving() && !nav.paused() && next.owner() && !next.destroyed() && next.mode() == 0;
        boolean stopped = !next.moving() && (nav.commandRevision() != before.commandRevision()
            || nav.stopReason() != before.stopReason()
            || target && nav.paused() && (!before.paused() || !Objects.equals(before.destination(), nav.destination())));
        if (!accepted && !stopped) return;
        boolean wanted = phase == Phase.WAITING_MOVE && !reopenRequired;
        pending = null; cancel(); noteCommand(now);
        if (wanted) { moveResult = accepted ? MoveResult.ACCEPTED : MoveResult.STOPPED; moveStopReason = nav.stopReason(); }
    }
    public void tick(long now) {
        if ((phase == Phase.CONFIRM || phase == Phase.QUEUED_START) && (!fresh(now) || now >= expiresAt)) expireApproval();
        if (queued != null && (!fresh(now) || now - requestAt > 2500)) {
            boolean move = queued.kind == CommandKind.MOVE;
            invalidate(); if (move) moveResult = MoveResult.UNCONFIRMED;
        }
        if (pending != null && now - pending.sentAt > 2500 || starting != null && now - requestAt > 2500) {
            // Without a request ID an unknown late reply cannot safely be reused. A fresh menu isolates it.
            boolean move = pending != null && pending.command.kind == CommandKind.MOVE;
            reopenRequired = true; invalidate(); if (move) moveResult = MoveResult.UNCONFIRMED;
        }
    }
    private void expireApproval() {
        phase = Phase.EXPIRED_CONFIRM; consent = false; expired = true; queued = null;
    }
    private void invalidate() { cancel(); expired = true; }
    public void consent(boolean value) { consent = phase == Phase.CONFIRM && value; }
    public boolean canStart(long now) {
        return phase == Phase.CONFIRM && validApproval(now);
    }
    private boolean validApproval(long now) {
        return !reopenRequired && consent && fresh(now) && now < expiresAt && idleOwner(latest) && samePreview(approved, latest);
    }
    public void sent(long now) { starting = approved; phase = Phase.SENT; consent = false; requestAt = now; noteCommand(now); }
    public boolean fresh(long now) { return latest != null && now >= receivedAt && now - receivedAt <= FRESH_MILLIS; }
    public boolean cargoAccessible(long now) { return fresh(now) && latest.owner() && !reopenRequired; }
    public boolean busy() { return queued != null || pending != null || starting != null; }
    public boolean reopenRequired() { return reopenRequired; }
    public MoveResult moveResult() { return moveResult; }
    public int moveStopReason() { return moveStopReason; }
    private static boolean idleOwner(Snapshot s) { return s != null && s.owner() && !s.destroyed() && !s.moving() && s.mode() == 0; }
    private static boolean acknowledges(Pending p, Snapshot next) {
        Command c = p.command;
        boolean generation = c.kind == CommandKind.RESUME
            ? Objects.equals(c.generation, next.generation()) && next.previewTicks() > p.before.previewTicks()
            : !Objects.equals(p.before.generation(), next.generation());
        return generation && next.previewTicks() > 0 && next.previewMode() == c.mode
            && next.chunkX() == c.chunkX && next.chunkZ() == c.chunkZ && next.dimension().equals(c.dimension);
    }
    private boolean sameSelection(Snapshot s) { return selected && s.chunkX() == chunkX && s.chunkZ() == chunkZ && s.dimension().equals(dimension); }
    private static boolean samePreview(Snapshot a, Snapshot b) {
        return sameRange(a, b) && a.previewMode() == b.previewMode();
    }
    private static boolean sameRange(Snapshot a, Snapshot b) {
        return a != null && b != null && a.menu() == b.menu() && a.ship().equals(b.ship()) && a.generation().equals(b.generation())
            && a.dimension().equals(b.dimension()) && a.chunkX() == b.chunkX() && a.chunkZ() == b.chunkZ()
            && a.minY() == b.minY() && a.maxY() == b.maxY();
    }
    public Phase phase() { return phase; }
    public boolean selected() { return selected; }
    public boolean expired() { return expired; }
    public boolean consent() { return consent; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public Snapshot approved() { return approved; }
    public static int chunkAt(double pixel, int origin, int size, double center, double zoom) {
        return Math.floorDiv((int) Math.floor(center + (pixel - origin - size / 2.0) / zoom), 16);
    }
    public static boolean inventoryVisible(boolean cargo, boolean inventoryPage, int height) {
        return cargo && inventoryPage && height >= 166;
    }
    public static boolean slotVisible(int index, int height, boolean playerPage) {
        return index >= 0 && index < 95 && (height >= 230 || (playerPage ? index >= 54 : index < 59));
    }
    public static int inventoryTop(int height, boolean playerPage) {
        return height < 230 && playerPage ? -104 : height < 314 ? 0 : 54;
    }
    public static Rect mapRect(int width, int height, boolean confirmation) {
        if (width < 600 || height < 300) return confirmation
            ? new Rect(6, 54, 90, Math.max(40, height - 92))
            : new Rect(6, 54, width - 12, Math.max(40, height - 94));
        int side = Math.min(320, Math.max(220, width / 3));
        return new Rect(8, 70, width - side - 28, height - 110);
    }
    public record MapProjection(double centerX, double centerZ, double zoom) {
        public int x(Rect r, double worldX) { return (int)Math.round(r.x + r.width / 2.0 + (worldX - centerX) * zoom); }
        public int z(Rect r, double worldZ) { return (int)Math.round(r.y + r.height / 2.0 + (worldZ - centerZ) * zoom); }
        public int chunkX(Rect r, double pixel) { return chunkAt(pixel, r.x, r.width, centerX, zoom); }
        public int chunkZ(Rect r, double pixel) { return chunkAt(pixel, r.y, r.height, centerZ, zoom); }
    }
    public static MapProjection projection(Rect map, double centerX, double centerZ, double zoom,
                                           boolean confirming, int chunkX, int chunkZ, int combatRadius) {
        if (!confirming) return new MapProjection(centerX, centerZ, zoom);
        double fitted = combatRadius <= 0 ? zoom : Math.max(.05,
            Math.min(zoom, (Math.min(map.width, map.height) - 12.0) / (combatRadius * 2)));
        return new MapProjection(chunkX * 16.0 + 8, chunkZ * 16.0 + 8, fitted);
    }
}
