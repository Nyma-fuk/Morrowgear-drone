package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.CarrierUiPolicy;
import jp.morrowgear.drone.CarrierUiInputPolicy;
import jp.morrowgear.drone.CarrierMapUiPolicy;
import jp.morrowgear.drone.CarrierUiPolicy.Phase;
import jp.morrowgear.drone.CarrierUiPolicy.Rect;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.carrier.CarrierAnchor;
import jp.morrowgear.drone.carrier.CarrierActionEstimatePolicy;
import jp.morrowgear.drone.carrier.CarrierCombatPolicy;
import jp.morrowgear.drone.carrier.CarrierCommandPayload;
import jp.morrowgear.drone.carrier.CarrierCommandPayload.Action;
import jp.morrowgear.drone.carrier.CarrierMenu;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierPowerPolicy;
import jp.morrowgear.drone.carrier.CarrierSupplies;
import jp.morrowgear.drone.carrier.CarrierViewPayload;
import jp.morrowgear.drone.carrier.client.CarrierClientApi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Owner-scoped carrier console. Menu registration is supplied by the parent client entrypoint. */
public final class CarrierScreen extends DockCarrierHmiScreen<CarrierMenu> {
    private enum Tab {
        OVERVIEW("概要", "gauge"), MINING("採掘", "pickaxe"), ATTACK("攻撃", "target"), MOVE("移動", "navigation"),
        CARGO("貨物", "package"), SUPPLY("補給", "battery-charging"), CABIN("船内", "home"), DIAGNOSTICS("診断", "sliders-horizontal");
        final String label, icon;
        Tab(String label, String icon) { this.label = label; this.icon = icon; }
    }
    private final CarrierUiPolicy confirmation;
    private final CarrierUiInputPolicy input = new CarrierUiInputPolicy();
    private final CarrierViewPayload openingView;
    private final CarrierMapView terrain = new CarrierMapView();
    private CarrierViewPayload lastView;
    private Tab tab = Tab.OVERVIEW;
    private int page, listPage, altitude;
    private boolean choosingTab, diagnostics, playerPage, moveControls, supplyCandidatesVisible;
    private double centerX, centerZ, zoom = 2.5;
    private long nextCommandAt;
    private String notice = "", widgetKey = "";
    private Button primary, stop, resumeButton;
    private Button cargoPrevious, cargoNext;
    private Checkbox consent;
    private Tab operationAfterMove;
    private long cargoPendingUntil;
    private static CargoReopen cargoReopen;
    private record CargoReopen(UUID ship, int oldMenu, boolean playerPage, long until) {}

    public CarrierScreen(CarrierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 230);
        confirmation = new CarrierUiPolicy(menu.containerId, menu.shipId);
        openingView = menu.view();
        if (cargoReopen != null) {
            if (cargoReopen.until() >= now() && cargoReopen.ship().equals(menu.shipId) && cargoReopen.oldMenu() != menu.containerId) {
                tab = Tab.CARGO; playerPage = cargoReopen.playerPage();
            }
            cargoReopen = null;
        }
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private CarrierViewPayload view() { return menu.view(); }
    private boolean small() { return width < 600 || height < 300; }
    private boolean mapTab() { return tab == Tab.MINING || tab == Tab.ATTACK || tab == Tab.MOVE; }
    private Tab navigationTab() { return diagnostics ? Tab.DIAGNOSTICS : tab == Tab.CARGO && page > 0 ? Tab.SUPPLY : tab; }
    private boolean compactCargo() { return inventoryVisible() && height < 314; }
    @Override protected boolean inventoryVisible() { return CarrierUiPolicy.inventoryVisible(tab == Tab.CARGO, page == 0 && !choosingTab && !diagnostics, height); }
    @Override protected boolean inventoryEditable() { return inventoryVisible() && view().owner() && !cargoPending(); }
    @Override protected boolean slotVisible(Slot slot) { return inventoryVisible() && CarrierUiPolicy.slotVisible(slot.index, height, playerPage); }
    private boolean linked() { return ClientPlayNetworking.canSend(CarrierCommandPayload.TYPE); }
    private boolean currentMenu() { return minecraft.player != null && minecraft.player.containerMenu == menu; }
    private boolean cargoReady() { return currentMenu() && linked() && confirmation.cargoAccessible(now()); }
    private boolean ready() { return cargoReady() && !view().destroyed(); }
    private boolean working() { return view().mode() != 0 || view().moving(); }
    private boolean cargoPending() { return now() < cargoPendingUntil; }
    private boolean canResumeMove() {
        return tab == Tab.MOVE && !confirmation.selected() && view().navigation().navigationPaused()
            && view().navigation().destination().isPresent();
    }
    private int requestedMode() { return tab == Tab.ATTACK ? CarrierPolicy.Mode.COMBAT.ordinal() : CarrierPolicy.Mode.MINING.ordinal(); }
    private boolean atSelection() {
        CarrierAnchor at = view().navigation().exterior();
        return confirmation.selected() && Math.floorDiv(at.x(), 16) == confirmation.chunkX() && Math.floorDiv(at.z(), 16) == confirmation.chunkZ();
    }
    @Override protected void init() {
        super.init();
        if (lastView == null) {
            CarrierAnchor at = view().navigation().exterior();
            centerX = at.x(); centerZ = at.z(); altitude = at.y();
        }
        receive(); build();
    }
    private void receive() {
        CarrierViewPayload v = view();
        if (v == lastView) return;
        if (!v.ship().equals(menu.shipId) || v.menu() != menu.containerId && v != openingView) return;
        lastView = v;
        if (notice.equals("結果を確認中") || notice.equals("移動を確認中") || notice.equals("停止を確認中")) notice = "";
        confirmation.accept(new CarrierUiPolicy.Snapshot(v.ship(), v.owner(), v.destroyed(), v.mode(), v.moving(),
            v.generation(), v.dimension(), v.chunkX(), v.chunkZ(), v.minY(), v.maxY(), v.previewMode(), v.previewTicks(),
            menu.containerId, v.navigation().effects().sampleTick(), new CarrierUiPolicy.Navigation(
                v.navigation().destination().map(CarrierScreen::destination).orElse(null),
                v.navigation().navigationPaused(), v.stop(), v.navigation().commandRevision())), now());
    }
    private static CarrierUiPolicy.Destination destination(CarrierAnchor anchor) {
        return new CarrierUiPolicy.Destination(anchor.dimension(), anchor.x(), anchor.y(), anchor.z());
    }
    @Override protected void containerTick() {
        receive(); confirmation.tick(now());
        continueOperationAfterMove();
        dispatchCommand();
        if (cargoPendingUntil != 0 && !cargoPending()) {
            cargoPendingUntil = 0; cargoReopen = null;
            if (notice.equals("貨物ページを確認中")) notice = "切替を確認できません。もう一度選んでください";
        }
        if (!widgetState().equals(widgetKey)) build();
        refreshControls();
    }
    private String widgetState() {
        return tab + ":" + page + ":" + listPage + ":" + choosingTab + ":" + diagnostics + ":" + confirmation.phase()
            + ":" + confirmation.selected() + ":" + confirmation.expired() + ":" + atSelection() + ":" + working() + ":" + view().owner()
            + ":" + view().destroyed() + ":" + view().navigation().navigationPaused() + ":" + view().navigation().destination().isPresent()
            + ":" + view().navigation().effects().phase()
            + ":" + confirmation.busy() + ":" + confirmation.reopenRequired()
            + ":" + confirmation.moveResult()
            + ":" + confirmation.fresh(now())
            + ":" + (tab == Tab.CARGO && page == 2 ? view().navigation().bays().hashCode() : tab == Tab.CABIN ? view().guests().hashCode() : 0);
    }
    private void changeTab(Tab next) {
        if (cargoPending()) return;
        if (!menu.getCarried().isEmpty()) { notice = "持っているアイテムを枠へ戻してください"; return; }
        cancelSlotGesture(); confirmation.cancel(); operationAfterMove = null; tab = next == Tab.SUPPLY ? Tab.CARGO : next;
        page = next == Tab.SUPPLY ? 1 : 0; listPage = 0;
        choosingTab = false; diagnostics = next == Tab.DIAGNOSTICS; moveControls = false; notice = ""; build();
    }
    private void changePage(int next) {
        if (cargoPending()) return;
        if (!menu.getCarried().isEmpty()) { notice = "持っているアイテムを枠へ戻してください"; return; }
        cancelSlotGesture(); page = next; listPage = 0; build();
    }
    private void back() {
        if (diagnostics) { changeTab(Tab.OVERVIEW); }
        else if (choosingTab || moveControls) { choosingTab = false; moveControls = false; build(); }
        else if (confirmation.phase() != Phase.SELECT) { confirmation.cancel(); build(); }
        else if (tab != Tab.OVERVIEW) changeTab(Tab.OVERVIEW);
        else onClose();
    }
    private void build() {
        input.rebuilt();
        resetControls(); primary = null; consent = null; cargoPrevious = null; cargoNext = null; resumeButton = null;
        leftPos = width < 600 ? 0 : 12;
        topPos = CarrierUiPolicy.inventoryTop(height, inventoryVisible() && playerPage);
        int nx = compactCargo() ? 182 : 6, nw = compactCargo() ? width - 188 : 58;
        button("戻る", "chevron-left", nx, 4, nw, this::back);
        if (compactCargo()) {
            button("補給・小型機", "battery-charging", 182, 30, width - 188, () -> changePage(1));
            button("他の操作", "list-checks", 182, 56, width - 188, this::toggleTabs);
            if (height < 230) button(playerPage ? "貨物を見る" : "持ち物を見る", "package", 182, 120, width - 188, () -> {
                if (!menu.getCarried().isEmpty() || cargoPending()) { notice = "持っているアイテムを枠へ戻してください"; return; }
                cancelSlotGesture(); playerPage = !playerPage; build();
            });
        } else if (small()) {
            button(navigationTab().label + " / 一覧", navigationTab().icon, width - 105, 4, 99, this::toggleTabs);
        } else {
            int w = (width - 16) / Tab.values().length;
            for (int i = 0; i < Tab.values().length; i++) {
                Tab t = Tab.values()[i]; button(t.label, null, 8 + i * w, 30, w - 4, () -> changeTab(t));
            }
            button("閉じる", "x", width - 28, 4, 20, this::onClose);
        }
        stop = button("停止", "square", compactCargo() ? 182 : 6, height - 24, compactCargo() ? width - 188 : 70, this::stop);
        if (choosingTab) {
            for (int i = 0; i < Tab.values().length; i++) {
                int w = (width - 24) / 2;
                Tab t = Tab.values()[i]; button(t.label, t.icon, 8 + i % 2 * (w + 8), 32 + i / 2 * 25, w, () -> changeTab(t));
            }
        } else if (diagnostics) {
            listPage = Math.clamp(listPage, 0, (diagnosticLines().size() - 1) / diagnosticRows());
            pagination(8, height - 54, diagnosticRows(), diagnosticLines().size());
        } else {
            if (tab == Tab.CARGO) buildCargo();
            else if (tab == Tab.CABIN) buildCabin();
            else if (mapTab()) buildMapControls();
            else buildOverview();
        }
        refreshControls();
        widgetKey = widgetState();
    }
    private int sideX() { return small() ? confirming() ? 104 : 6 : CarrierUiPolicy.mapRect(width, height, false).right() + 12; }
    private int sideWidth() { return width - sideX() - 8; }
    private void buildMapControls() {
        int x = small() ? 82 : sideX(), w = width - x - 6;
        if (confirming()) {
            int cy = small() ? height - 60 : 171;
            boolean expired = confirmation.phase() == Phase.EXPIRED_CONFIRM;
            if (!expired) consent = control(Checkbox.builder(Component.literal(tab == Tab.ATTACK ? "攻撃範囲を確認しました" : "破壊する範囲に同意します"), font)
                    .pos(sideX(), cy).maxWidth(sideWidth()).selected(confirmation.consent())
                    .onValueChange((box, value) -> { confirmation.consent(value); refreshControls(); }).build());
            primary = button(expired ? tab == Tab.ATTACK ? "攻撃範囲を再確認" : "採掘範囲を再確認"
                : confirmation.phase() == Phase.QUEUED_START ? "開始を待っています"
                : tab == Tab.ATTACK ? "この範囲の敵を攻撃" : "この範囲の採掘を開始", "play",
                x, height - 24, w, expired ? this::revalidate : this::activate);
        } else {
            String label = confirmation.busy() ? requestNotice() : working() ? status()
                : canResumeMove() ? "保存した目的地への移動を再開" : !confirmation.selected() ? "地図で区画を選ぶ"
                : tab == Tab.MOVE ? "母艦をここへ移動"
                : !atSelection() ? (tab == Tab.ATTACK ? "攻撃位置へ移動して範囲確認" : "採掘位置へ移動して範囲確認")
                : tab == Tab.ATTACK ? "攻撃範囲を確認" : "採掘範囲を確認";
            primary = button(label, working() ? "square" : tab == Tab.MOVE || !atSelection() ? "navigation" : "chevron-right",
                x, height - 24, w, this::primaryAction);
            if (!small() && !working() && view().total() > 0 && tab == Tab.MINING)
                resumeButton = button("前の採掘を続ける", "rotate-ccw", sideX(), height - 54, sideWidth(), this::resume);
        }
        if (moveControls && small()) {
            button("高度 -8", "arrow-down", 8, 78, 94, () -> altitude -= 8);
            button("高度 +8", "arrow-up", 108, 78, 94, () -> altitude += 8);
            button("地図に戻る", "map", 8, 108, 194, () -> { moveControls = false; build(); });
        } else if (!small()) {
            Rect map = CarrierUiPolicy.mapRect(width, height, false);
            button("母艦の位置", "locate-fixed", map.right() - 216, 53, 100, this::recenter);
            button("拡大", "plus", map.right() - 112, 53, 52, () -> zoom = Math.min(8, zoom * 1.25));
            button("縮小", "minus", map.right() - 56, 53, 52, () -> zoom = Math.max(.25, zoom / 1.25));
        } else if (!confirming()) {
            button("母艦の位置", "locate-fixed", 6, 28, 94, this::recenter);
            button("拡大", "plus", 105, 28, 58, () -> zoom = Math.min(8, zoom * 1.25));
            button("縮小", "minus", 168, 28, 58, () -> zoom = Math.max(.25, zoom / 1.25));
            if (tab == Tab.MOVE) button("高度", "gauge", 231, 28, width - 237, () -> { moveControls = true; build(); });
            if (tab == Tab.MINING) resumeButton = button("続きの範囲", "rotate-ccw", 231, 28, width - 237, this::resume);
        }
        if (tab == Tab.MOVE && !small()) {
            button("高度 -8", "arrow-down", sideX(), 145, 94, () -> altitude -= 8);
            button("高度 +8", "arrow-up", sideX() + 100, 145, 94, () -> altitude += 8);
        }
    }
    private boolean confirming() { return confirmation.phase() == Phase.CONFIRM
        || confirmation.phase() == Phase.EXPIRED_CONFIRM || confirmation.phase() == Phase.QUEUED_START; }
    private void toggleTabs() {
        if (cargoPending()) return;
        if (!menu.getCarried().isEmpty()) { notice = "持っているアイテムを枠へ戻してください"; return; }
        confirmation.cancel(); choosingTab = !choosingTab; build();
    }
    private void recenter() { centerX = view().navigation().exterior().x(); centerZ = view().navigation().exterior().z(); }
    private void refreshControls() {
        if (stop != null) stop.active = linked() && view().owner() && (working() || view().navigation().destination().isPresent() || view().bayUsed() > 0
            || view().previewTicks() > 0 || confirmation.busy());
        if (primary != null) primary.active = !choosingTab && !diagnostics && !moveControls && (confirmation.phase() == Phase.EXPIRED_CONFIRM
            ? ready() && !confirmation.busy() : confirming() ? ready() && confirmation.canStart(now())
            : ready() && !confirmation.busy() && !working() && (confirmation.selected() || canResumeMove()) && confirmation.phase() == Phase.SELECT);
        if (primary != null) primary.setTooltip(Tooltip.create(Component.literal(primary.active ? primary.getMessage().getString()
            : !linked() || !confirmation.fresh(now()) ? "通信できません。再受信を待ってください"
            : !view().owner() ? "所有者のみ操作できます" : view().destroyed() ? "母艦が壊れています"
            : confirmation.busy() ? requestNotice() : working() ? "実行中の作業を停止してください"
            : confirming() ? "対象範囲を確認して同意してください" : "地図で対象区画を選択してください")));
        if (consent != null) consent.active = ready() && confirmation.phase() == Phase.CONFIRM;
        if (resumeButton != null) resumeButton.active = ready() && !working() && !confirmation.busy()
            && confirmation.phase() == Phase.SELECT && view().total() > 0;
        boolean canPage = cargoReady() && !confirmation.busy() && now() >= nextCommandAt && !cargoPending() && menu.getCarried().isEmpty();
        if (cargoPrevious != null) cargoPrevious.active = canPage && menu.cargoPage() > 0;
        if (cargoNext != null) cargoNext.active = canPage && menu.cargoPage() + 1 < view().navigation().storage().pages();
    }
    private void stop() {
        if (send(Action.STOP, CarrierCommandPayload.NONE, 0, 0, 0, CarrierCommandPayload.NONE)) {
            confirmation.cancel(); operationAfterMove = null; notice = "停止を確認中";
        }
        build();
    }
    private void primaryAction() {
        if (!ready() || confirmation.busy() || working() || confirmation.phase() != Phase.SELECT) return;
        if (canResumeMove()) {
            CarrierAnchor destination = view().navigation().destination().orElseThrow();
            confirmation.queueMove(destination(destination), now()); notice = ""; build();
            return;
        }
        if (!confirmation.selected()) return;
        int x = confirmation.chunkX() * 16 + 8, z = confirmation.chunkZ() * 16 + 8;
        if (tab == Tab.MOVE || !atSelection()) {
            operationAfterMove = tab == Tab.MOVE ? null : tab;
            confirmation.queueMove(new CarrierUiPolicy.Destination(view().navigation().exterior().dimension(), x, altitude, z),
                tab == Tab.ATTACK ? CarrierPolicy.Mode.COMBAT.ordinal()
                    : tab == Tab.MINING ? CarrierPolicy.Mode.MINING.ordinal() : CarrierPolicy.Mode.IDLE.ordinal(), now());
            notice = "";
        } else if (confirmation.queuePreview(requestedMode(), false, now())) notice = "";
        build();
    }

    private void continueOperationAfterMove() {
        if (operationAfterMove == null || working() || !atSelection() || confirmation.busy()
            || confirmation.phase() != Phase.SELECT || !ready()) return;
        Tab operation = operationAfterMove;
        operationAfterMove = null;
        int mode = operation == Tab.ATTACK ? CarrierPolicy.Mode.COMBAT.ordinal() : CarrierPolicy.Mode.MINING.ordinal();
        if (confirmation.queuePreview(mode, false, now())) {
            notice = operation == Tab.ATTACK ? "到着 / 攻撃範囲を確認中" : "到着 / 採掘範囲を確認中";
            build();
        }
    }
    private void resume() {
        if (!ready() || working() || confirmation.busy() || confirmation.phase() != Phase.SELECT) return;
        confirmation.select(view().chunkX(), view().chunkZ(), view().dimension());
        confirmation.queuePreview(1, true, now()); notice = "";
        build();
    }
    private void activate() {
        if (!ready() || !confirmation.canStart(now())) return;
        confirmation.queueStart(now()); notice = "";
        build();
    }
    private void revalidate() {
        if (!ready() || confirmation.phase() != Phase.EXPIRED_CONFIRM) return;
        if (confirmation.queuePreview(requestedMode(), false, now())) notice = "";
        build();
    }
    private void dispatchCommand() {
        if (!ready() || choosingTab || diagnostics || moveControls) return;
        var command = confirmation.pollCommand(now());
        if (command == null) return;
        Action action = switch (command.kind()) {
            case START -> Action.ACTIVATE;
            case RESUME -> Action.RESUME_PREVIEW;
            case MOVE -> command.mode() == CarrierPolicy.Mode.MINING.ordinal() ? Action.MOVE_MINING
                : command.mode() == CarrierPolicy.Mode.COMBAT.ordinal() ? Action.MOVE_COMBAT : Action.MOVE;
            case PREVIEW -> command.mode() == CarrierPolicy.Mode.COMBAT.ordinal() ? Action.PREVIEW_COMBAT : Action.PREVIEW_MINING;
        };
        UUID generation = command.kind() == CarrierUiPolicy.CommandKind.START ? command.generation() : CarrierCommandPayload.NONE;
        var destination = command.destination();
        CarrierClientApi.send(new CarrierCommandPayload(menu.containerId, menu.shipId, action, generation,
            destination == null ? command.chunkX() * 16 + 8 : destination.x(), destination == null ? 0 : destination.y(),
            destination == null ? command.chunkZ() * 16 + 8 : destination.z(), CarrierCommandPayload.NONE));
        nextCommandAt = now() + 300;
    }
    private String requestNotice() {
        if (confirmation.moveResult() == CarrierUiPolicy.MoveResult.UNCONFIRMED)
            return confirmation.reopenRequired() ? "移動の応答がありません。画面を開き直してください" : "移動を送信できませんでした。もう一度選んでください";
        if (confirmation.reopenRequired()) return "応答を確認できません。画面を開き直してください";
        if (confirmation.moveResult() == CarrierUiPolicy.MoveResult.STOPPED) return "移動は停止中 / " + status();
        return switch (confirmation.phase()) {
            case QUEUED_MOVE -> "移動の送信を待っています";
            case WAITING_MOVE -> "移動の応答を確認しています";
            case QUEUED_PREVIEW, WAITING -> "範囲を確認しています";
            case QUEUED_START -> "開始を待っています";
            case SENT -> "開始を確認しています";
            default -> confirmation.busy() ? "取り消した操作の応答待ち" : "";
        };
    }
    private boolean send(Action action, UUID generation, int x, int y, int z, UUID target) {
        boolean guestAction = action == Action.BOARD || action == Action.EXIT;
        if (!currentMenu() || !linked() || (!guestAction && !view().owner())) { notice = "操作できません"; return false; }
        confirmation.cancelMove();
        if (action != Action.STOP && action != Action.EXIT && (now() < nextCommandAt || confirmation.busy())) {
            notice = "前の操作を確認中です"; return false;
        }
        CarrierClientApi.send(new CarrierCommandPayload(menu.containerId, menu.shipId, action, generation, x, y, z, target));
        if (action != Action.STOP && action != Action.EXIT) { nextCommandAt = now() + 300; confirmation.noteCommand(now()); }
        notice = "結果を確認中";
        return true;
    }
    private void simple(Action action, UUID target) {
        if (action != Action.EXIT && !confirmation.fresh(now())) { notice = "通信できません"; return; }
        if (action == Action.BOARD) {
            confirmation.cancel();
            CarrierAnchor exterior = view().navigation().exterior();
            boolean sameDimension = minecraft.player != null
                && minecraft.player.level().dimension().identifier().toString().equals(exterior.dimension());
            if (!sameDimension || !CarrierPolicy.withinBoardingEnvelope(
                minecraft.player.getX(), minecraft.player.getZ(), exterior.x(), exterior.z())) {
                notice = "搭乗地点から離れすぎています";
                build();
                return;
            }
        }
        send(action, CarrierCommandPayload.NONE, 0, 0, 0, target);
    }
    private void buildCargo() {
        if (page == 0) {
            int x = compactCargo() ? 182 : 200, y = compactCargo() ? 82 : 86;
            int w = Math.min(80, (width - x - 10) / 2);
            cargoPrevious = button("前へ", "chevron-left", x, y, w, () -> cargoPage(-1));
            cargoNext = button("次へ", "chevron-right", x + w + 4, y, w, () -> cargoPage(1));
        }
        if (compactCargo()) return;
        int x = cargoContentX(), y = small() ? 30 : 55;
        int tabWidth = DockCarrierLayout.supplyTabWidth(width, x);
        button("貨物", "package", x, y, tabWidth, () -> changePage(0));
        button("母艦補給", "battery-charging", x + tabWidth + 4, y, tabWidth, () -> changePage(1));
        button("小型機補給", "dock", x + 2 * (tabWidth + 4), y, tabWidth, () -> changePage(2));
        if (page == 1) {
            button("機動蓄電セルを投入", "battery-charging", powerX(0) + powerWidth() + 6, powerY(0) + 6, 22,
                () -> simple(Action.REFUEL, CarrierCommandPayload.NONE)).active = view().owner();
            button("兵装電力セルを投入", "zap", powerX(1) + powerWidth() + 6, powerY(1) + 6, 22,
                () -> simple(Action.CHARGE_WEAPON, CarrierCommandPayload.NONE)).active = view().owner();
        } else if (page == 2) {
            List<DroneEntity> candidates = ownedDrones();
            List<UUID> ids = new ArrayList<>(view().drones());
            for (var candidate : view().candidates()) if (!ids.contains(candidate.drone())) ids.add(candidate.drone());
            for (DroneEntity d : candidates) if (!ids.contains(d.getUUID())) ids.add(d.getUUID());
            supplyCandidatesVisible = !ids.isEmpty();
            int count = Math.max(1, (height - y - 102) / 24);
            listPage = Math.min(listPage, Math.max(0, (ids.size() - 1) / count));
            for (int i = listPage * count; i < Math.min(ids.size(), (listPage + 1) * count); i++) {
                UUID id = ids.get(i); boolean reserved = view().drones().contains(id);
                DroneEntity drone = candidates.stream().filter(d -> d.getUUID().equals(id)).findFirst().orElse(null);
                String label = drone == null ? view().candidates().stream().filter(d -> d.drone().equals(id))
                    .map(CarrierViewPayload.DroneCandidate::label).findFirst().orElse("補給対象の機体 " + (i + 1)) : drone.unitId();
                var bay = view().navigation().bays().stream().filter(b -> b.drone().equals(id)).findFirst();
                if (bay.isPresent()) label += " / " + (bay.get().slot() + 1) + "番 " + bayPhase(bay.get());
                Button b = button(label + (reserved ? " / 取り消す" : " / 補給する"), reserved ? "x" : "plus",
                    x, y + 40 + (i % count) * 24, Math.min(320, width - x - 6), () -> simple(reserved ? Action.RELEASE_BAY : Action.RESERVE_BAY, id));
                b.active = view().owner() && (reserved || view().bayUsed() < CarrierPolicy.BAY_SLOTS);
            }
            pagination(x, height - 62, count, ids.size());
        }
    }
    private int cargoContentX() { return page == 0 && !small() ? 200 : 6; }
    private int powerX(int index) { return 6 + (!small() ? index * (width / 2) : 0); }
    private int powerY(int index) { return (small() ? 60 : 85) + (small() ? index * 42 : 0); }
    private int powerWidth() { return (small() ? width : width / 2) - 48; }
    private void cargoPage(int delta) {
        int target = menu.cargoPage() + delta;
        if (!cargoReady() || cargoPending() || !menu.getCarried().isEmpty() || target < 0 || target >= view().navigation().storage().pages()) return;
        cancelSlotGesture();
        if (send(Action.CARGO_PAGE, CarrierCommandPayload.NONE, target, menu.getStateId(), menu.cargoPage(), CarrierCommandPayload.NONE)) {
            cargoPendingUntil = now() + 3000;
            cargoReopen = new CargoReopen(menu.shipId, menu.containerId, playerPage, cargoPendingUntil);
            notice = "貨物ページを確認中";
        }
        refreshControls();
    }
    private List<DroneEntity> ownedDrones() {
        List<DroneEntity> result = new ArrayList<>();
        if (minecraft.level != null && minecraft.player != null)
            for (Entity e : minecraft.level.entitiesForRendering())
                if (e instanceof DroneEntity d && d.isAlive() && d.isOwnedBy(minecraft.player.getUUID())) result.add(d);
        result.sort(Comparator.comparing(DroneEntity::unitId));
        return result;
    }
    private void pagination(int x, int y, int count, int total) {
        button("前へ", "chevron-left", x, y, 66, () -> { listPage = Math.max(0, listPage - 1); build(); }).active = listPage > 0;
        button("次へ", "chevron-right", x + 72, y, 66, () -> { listPage++; build(); }).active = (listPage + 1) * count < total;
        button("更新", "refresh-cw", x + 144, y, 66, this::build);
    }
    private void buildCabin() {
        int x = 8, y = 56;
        button("船内へ入る", "log-in", x, y, 115, () -> simple(Action.BOARD, CarrierCommandPayload.NONE)).active = !view().destroyed();
        button("外へ出る", "log-out", x + 121, y, 104, () -> simple(Action.EXIT, CarrierCommandPayload.NONE));
        if (view().destroyed()) button("残った荷物を取りに行く", "archive", x, y + 25, 220,
            () -> simple(Action.RECOVER_CABIN, CarrierCommandPayload.NONE)).active = view().owner();
        else if (view().owner() && minecraft.getConnection() != null) {
            List<UUID> ids = new ArrayList<>(view().guests());
            for (UUID id : minecraft.getConnection().getOnlinePlayerIds())
                if (!id.equals(minecraft.player.getUUID()) && !ids.contains(id)) ids.add(id);
            int count = Math.max(1, (height - 148) / 24);
            listPage = Math.min(listPage, Math.max(0, (ids.size() - 1) / count));
            for (int i = listPage * count; i < Math.min(ids.size(), (listPage + 1) * count); i++) {
                UUID id = ids.get(i); boolean guest = view().guests().contains(id);
                var info = minecraft.getConnection().getPlayerInfo(id);
                String name = info == null ? "招待したプレイヤー " + (i + 1) : info.getProfile().name();
                button(name + (guest ? " / 招待を取り消す" : " / 招待する"), guest ? "x" : "plus", x,
                    y + 30 + i % count * 24, Math.min(330, width - 16), () -> simple(guest ? Action.REVOKE_GUEST : Action.ALLOW_GUEST, id))
                    .active = guest || view().guests().size() < 32;
            }
            pagination(x, height - 62, count, ids.size());
        }
    }
    @Override protected void drawScreen(GuiGraphicsExtractor g, int mx, int my) {
        if (!compactCargo()) {
            g.fill(0, 0, width, 26, PANEL);
            text(g, "母艦 / " + navigationTab().label, 72, 10, Math.max(20, width - 182), TEXT);
            g.fill(0, height - 28, width, height, PANEL);
        }
        if (!small() && !compactCargo()) {
            int w = (width - 16) / Tab.values().length;
            int x = 8 + navigationTab().ordinal() * w;
            g.fill(x, 50, x + w - 4, 52, CYAN);
            text(g, status(), 230, 10, width - 270, MUTED);
        }
        if (choosingTab) return;
        if (diagnostics) { drawDiagnostics(g, mx, my); return; }
        if (tab == Tab.CARGO) drawCargo(g, mx, my);
        else if (mapTab()) drawMap(g, mx, my);
        else if (tab != Tab.CABIN) drawOverview(g);
        if (tab == Tab.CABIN && !view().owner()) text(g, "招待されたプレイヤー: 乗り降りできます", 8, 90, width - 16, MUTED);
        if (tab == Tab.CABIN && view().owner() && !view().destroyed()
            && view().guests().isEmpty() && minecraft.player != null && minecraft.getConnection() != null
            && minecraft.getConnection().getOnlinePlayerIds().stream().noneMatch(id -> !id.equals(minecraft.player.getUUID()))) {
            text(g, "招待できるプレイヤーはいません", 8, 90, width - 16, MUTED);
        }
        if (tab == Tab.CABIN || tab == Tab.CARGO || tab == Tab.OVERVIEW) {
            int x = compactCargo() ? 182 : 84;
            text(g, notice.isEmpty() ? status() : notice, x, height - (compactCargo() ? 37 : 39), width - x - 8, AMBER);
        }
    }
    private void buildOverview() {
        int x = Math.max(180, width * 2 / 3), w = width - x - 8;
        Tab[] shortcuts = {Tab.MOVE, Tab.MINING, Tab.CARGO, Tab.SUPPLY};
        for (int i = 0; i < shortcuts.length; i++) {
            Tab next = shortcuts[i];
            button(next.label, next.icon, x, (small() ? 34 : 62) + i * 24, w, () -> changeTab(next));
        }
    }
    private void drawOverview(GuiGraphicsExtractor g) {
        int x = 8, y = small() ? 34 : 62, w = Math.max(164, width * 2 / 3 - 20);
        var v = view();
        var at = v.navigation().exterior();
        var storage = v.navigation().storage();
        List<String> summary = List.of(
            status(),
            actionAvailability(),
            activeEstimate(),
            ControllerDisplayText.dimensionName(at.dimension()) + " / " + at.x() + ", " + at.y() + ", " + at.z(),
            "機動蓄電 " + v.energy() + " / " + CarrierPolicy.MAX_ENERGY + " E",
            "兵装蓄電 " + v.weaponEnergy() + " / " + CarrierPolicy.MAX_ENERGY + " E",
            "貨物 " + storage.usedSlots() + "/" + storage.totalSlots() + "枠 / " + storage.items() + "個",
            "補給ベイ " + v.bayUsed() + "/" + CarrierPolicy.BAY_SLOTS + " / 招待 " + v.guests().size() + "人");
        int row = Math.min(23, Math.max(14, (height - y - 46) / summary.size()));
        for (int i = 0; i < summary.size(); i++) {
            text(g, summary.get(i), x, y + i * row, w, i == 0 ? CYAN : TEXT);
            g.horizontalLine(x, x + w, y + i * row + 12, LINE);
            if (i == 4 || i == 5) {
                int value = i == 4 ? v.energy() : v.weaponEnergy();
                int filled = (int) Math.round(w * Math.clamp(value / (double) CarrierPolicy.MAX_ENERGY, 0, 1));
                g.fill(x, y + i * row + 12, x + filled, y + i * row + 14, i == 4 ? CYAN : AMBER);
            }
        }
        int identityY = y + summary.size() * row + 6;
        int imageSize = Math.min(96, height - 48 - identityY);
        if (imageSize >= 48 && w >= imageSize + 140) {
            HmiArt.item(g, "carrier_unit", x, identityY, imageSize);
            text(g, "チャンクバスター", x + imageSize + 12, identityY + 12,
                w - imageSize - 12, TEXT);
            text(g, "ID " + v.ship().toString().substring(0, 8), x + imageSize + 12,
                identityY + 29, w - imageSize - 12, MUTED);
        }
    }
    private void drawCargo(GuiGraphicsExtractor g, int mx, int my) {
        if (!compactCargo()) {
            int tx = cargoContentX(), tw = DockCarrierLayout.supplyTabWidth(width, tx);
            int ty = small() ? 50 : 75;
            g.fill(tx + page * (tw + 4), ty, tx + page * (tw + 4) + tw, ty + 2, CYAN);
        }
        if (inventoryVisible()) {
            slotBackgrounds(g);
            text(g, height < 230 && playerPage ? "補給品と持ち物" : "貨物", leftPos + 8, height < 230 ? 5 : topPos + 5, 160, TEXT);
            var storage = view().navigation().storage();
            text(g, "貨物 " + (menu.cargoPage() + 1) + " / " + storage.pages(), compactCargo() ? 182 : 200, 108,
                width - (compactCargo() ? 188 : 208), TEXT);
            if (height >= 230) {
                text(g, "使用 " + storage.usedSlots() + "/" + storage.totalSlots() + "枠", compactCargo() ? 182 : 200,
                    compactCargo() ? 174 : 124, width - (compactCargo() ? 188 : 208), MUTED);
                text(g, "収納 " + storage.items() + "個", compactCargo() ? 182 : 200,
                    compactCargo() ? 187 : 137, width - (compactCargo() ? 188 : 208), CYAN);
            }
            if (compactCargo() && height >= 230) {
                text(g, "補給品", 182, 127, width - 188, CYAN);
                text(g, "持ち物", 182, 151, width - 188, MUTED);
            } else if (!compactCargo()) {
                text(g, "補給品", leftPos + 180, topPos + 128, width - leftPos - 188, CYAN);
                text(g, "持ち物", leftPos + 180, topPos + 152, width - leftPos - 188, MUTED);
            }
            String[] names = {"推進パワーセル", "兵装電力セル", "機関砲弾", "ミサイル", "修理材"};
            String[] icons = {"battery-charging", "zap", "target", "crosshair", "wrench"};
            for (int i = 54; i < Math.min(59, menu.slots.size()); i++) {
                var slot = menu.slots.get(i);
                if (!slotVisible(slot)) continue;
                if (!slot.hasItem()) HmiArt.icon(g, icons[i - 54], leftPos + slot.x + 2, topPos + slot.y + 2, 12);
                if (mx >= leftPos + slot.x && mx < leftPos + slot.x + 16 && my >= topPos + slot.y && my < topPos + slot.y + 16)
                    g.setTooltipForNextFrame(Component.literal(names[i - 54]), mx, my);
            }
        }
        if (page == 1) {
            drawPower(g, "機動蓄電", view().energy(), powerX(0), powerY(0), powerWidth(), CYAN);
            drawPower(g, "兵装蓄電", view().weaponEnergy(), powerX(1), powerY(1), powerWidth(), AMBER);
            int detailY = powerY(1) + 38;
            if (detailY + 22 < height - 44) {
                text(g, "推進パワーセル +" + CarrierSupplies.FLIGHT_CELL_ENERGY
                    + "E / 兵装電力セル +" + CarrierSupplies.WEAPON_CELL_ENERGY + "E", 6, detailY, width - 12, TEXT);
                text(g, "補給元: 専用枠 → 貨物 → 持ち物", 6, detailY + 12, width - 12, MUTED);
            }
        } else if (page == 2) {
            text(g, "補給位置 " + view().bayUsed() + "/" + CarrierPolicy.BAY_SLOTS
                + "  機関砲 " + view().navigation().gunCredit() + "発"
                + "  ミサイル " + view().navigation().missileCredit() + "発", 6, small() ? 54 : 79,
                width - 12, MUTED);
            if (!supplyCandidatesVisible)
                text(g, "補給候補なし（半径64ブロック）", 6, small() ? 76 : 101, width - 12, MUTED);
        }
    }
    private void drawPower(GuiGraphicsExtractor g, String label, int amount, int x, int y, int w, int color) {
        text(g, label + (confirmation.fresh(now()) ? "" : " / 最終受信"), x, y, w, TEXT);
        text(g, amount + " / " + CarrierPolicy.MAX_ENERGY + " E", x, y + 12, w, color);
        g.fill(x, y + 25, x + w, y + 28, LINE);
        int fill = jp.morrowgear.drone.DockStockViewPolicy.gaugePixels(amount, CarrierPolicy.MAX_ENERGY, w);
        g.fill(x, y + 25, x + fill, y + 28, color);
    }
    private void drawMap(GuiGraphicsExtractor g, int mx, int my) {
        if (moveControls) {
            text(g, "移動先の高さ Y " + altitude, 8, 57, width - 16, TEXT);
            text(g, notice, 8, height - 37, width - 16, AMBER);
            return;
        }
        boolean confirming = confirming();
        Rect map = CarrierUiPolicy.mapRect(width, height, confirming);
        CarrierAnchor exterior = view().navigation().exterior();
        terrain.working(working());
        var projection = projection(map);
        boolean hasTerrain = terrain.draw(g, map, exterior.dimension(), projection.centerX(), projection.centerZ(), projection.zoom(), view().terrain());
        g.enableScissor(map.x(), map.y(), map.right(), map.bottom());
        int firstX = projection.chunkX(map, map.x()), lastX = projection.chunkX(map, map.right());
        int firstZ = projection.chunkZ(map, map.y()), lastZ = projection.chunkZ(map, map.bottom());
        for (int c = firstX; c <= lastX; c++) g.verticalLine(px(map, c * 16), map.y(), map.bottom(), LINE);
        for (int c = firstZ; c <= lastZ; c++) g.horizontalLine(map.x(), map.right(), pz(map, c * 16), LINE);
        if (tab == Tab.MINING) drawMiningScanGrid(g, map);
        if (view().mode() != 0) highlight(g, map, view().chunkX(), view().chunkZ(), AMBER);
        else if (confirmation.selected()) highlight(g, map, confirmation.chunkX(), confirmation.chunkZ(), AMBER);
        else if (tab == Tab.MINING && CarrierMapUiPolicy.persistentMiningFrame(
            view().mode(), view().previewMode(), view().previewTicks(), view().stop(), view().total()))
            highlight(g, map, view().chunkX(), view().chunkZ(), AMBER);
        if (!confirming && !working() && map.contains(mx, my)) highlight(g, map,
            CarrierUiPolicy.chunkAt(mx, map.x(), map.width(), centerX, zoom), CarrierUiPolicy.chunkAt(my, map.y(), map.height(), centerZ, zoom), CYAN);
        terrain.drawEffects(g, map, projection.centerX(), projection.centerZ(), projection.zoom(), exterior, view(), confirmation.fresh(now()));
        HmiArt.icon(g, "navigation", px(map, exterior.x()) - 6, pz(map, exterior.z()) - 6, 12);
        g.outline(px(map, exterior.x()) - 8, pz(map, exterior.z()) - 8, 16, 16, CYAN);
        view().navigation().destination().ifPresent(at -> HmiArt.icon(g, "map-pin", px(map, at.x()) - 6, pz(map, at.z()) - 6, 12));
        g.disableScissor();
        if (!confirming && (view().mode() != CarrierPolicy.Mode.IDLE.ordinal()
            || tab == Tab.MINING && miningStage() == CarrierMapUiPolicy.MiningStage.RELEASE)) drawPhase(g, map);
        if (!hasTerrain) text(g, "地形を表示できません", map.x() + 3, map.y() + 3, map.width() - 6, MUTED);
        if (small() && !confirming) {
            text(g, !requestNotice().isEmpty() ? requestNotice() : confirmation.expired() ? "範囲をもう一度確認してください" : !notice.isEmpty() ? notice
                : view().mode() == 1 && confirmation.fresh(now()) ? "採掘 " + (view().total() > 0 ? (long)view().cursor() * 100 / view().total() : 0)
                    + "% / 回収 " + view().navigation().effects().capturedItems() + "個"
                : working() || canResumeMove() ? status() : confirmation.selected() ? "選択中心から半径24 / 円形3チャンク域" : "地図で区画を選んでください", 6, height - 37, width - 12, AMBER);
            return;
        }
        int x = sideX(), y = small() ? 54 : 74, w = sideWidth();
        text(g, tab == Tab.ATTACK ? "敵への攻撃 / 地形は壊しません" : tab == Tab.MOVE ? "母艦の移動" : "採掘する範囲", x, y, w, TEXT);
        text(g, tab == Tab.ATTACK && view().navigation().effects().combatRadius() > 0
            ? "敵を探す範囲 半径 " + view().navigation().effects().combatRadius() + "ブロック"
            : confirmation.selected() ? "中心チャンク " + confirmation.chunkX() + ", " + confirmation.chunkZ() + " / 半径24の円形範囲" : "地図で区画を選択", x, y + 15, w, AMBER);
        if (confirming) {
            text(g, tab == Tab.ATTACK ? "射線の通る敵だけを攻撃" : "母艦の下から岩盤まで掘削", x, y + 30, w, TEXT);
            text(g, tab == Tab.ATTACK ? "標的周囲 " + (int)CarrierPolicy.COMBAT_IMPACT_RADIUS + "ブロックを照射" : "高さ Y " + view().minY() + " - " + view().maxY(), x, y + 43, w, MUTED);
        } else {
            text(g, status(), x, y + 31, w, CYAN);
            text(g, activeEstimate(), x, y + 47, w, AMBER);
            if (tab == Tab.MOVE) text(g, "移動先の高さ Y " + altitude, x, y + 62, w, MUTED);
            else if (view().total() > 0 && view().mode() == 1) text(g, "採掘 " + (long) view().cursor() * 100 / view().total() + "%", x, y + 62, w, GREEN);
            else if (view().mode() == 2) text(g, "攻撃作戦 残り " + formatTicks(view().navigation().combatTicks()), x, y + 62, w, AMBER);
            if (!small()) text(g, !requestNotice().isEmpty() ? requestNotice() : confirmation.expired() ? "範囲をもう一度確認してください" : notice, x, y + 78, w, AMBER);
            if (!small()) {
                var effect = view().navigation().effects(); var storage = view().navigation().storage();
                text(g, "採掘 " + effect.minedBlocks() + "ブロック / 回収 " + effect.capturedItems() + "個", x, y + 96, w, GREEN);
                text(g, "貨物 " + storage.usedSlots() + "/" + storage.totalSlots() + "枠", x, y + 111, w, MUTED);
                text(g, (confirmation.fresh(now()) ? "蓄電 / " : "最終受信 / ") + "機動 " + view().energy() + " / 兵装 " + view().weaponEnergy(), x, y + 126, w, MUTED);
            }
        }
    }
    private void drawMiningScanGrid(GuiGraphicsExtractor g, Rect map) {
        int centerX, centerZ;
        if (confirmation.selected()) {
            centerX = confirmation.chunkX(); centerZ = confirmation.chunkZ();
        } else if (CarrierMapUiPolicy.persistentMiningFrame(
            view().mode(), view().previewMode(), view().previewTicks(), view().stop(), view().total())) {
            centerX = view().chunkX(); centerZ = view().chunkZ();
        } else {
            return;
        }
        var effect = view().navigation().effects();
        var scanned = CarrierMapUiPolicy.scannedChunks(view().mode(), effect.phase(), effect.phaseTick(), centerX, centerZ);
        for (var chunk : CarrierMapUiPolicy.scanGrid(centerX, centerZ)) {
            scanCorners(g, map, chunk.x(), chunk.z(), scanned.contains(chunk) ? HmiArt.CYAN : HmiArt.LINE);
        }
    }
    private void scanCorners(GuiGraphicsExtractor g, Rect map, int chunkX, int chunkZ, int color) {
        int x = px(map, chunkX * 16), z = pz(map, chunkZ * 16);
        int size = Math.max(2, (int)Math.round(16 * projection(map).zoom()));
        int arm = Math.max(1, Math.min(4, size / 3));
        g.fill(x, z, x + arm, z + 1, color); g.fill(x, z, x + 1, z + arm, color);
        g.fill(x + size - arm, z + size - 1, x + size, z + size, color);
        g.fill(x + size - 1, z + size - arm, x + size, z + size, color);
    }
    private CarrierMapUiPolicy.MiningStage miningStage() {
        var effect = view().navigation().effects();
        return CarrierMapUiPolicy.miningStage(view().mode(), effect.phase(), effect.phaseTick(), effect.phaseDuration());
    }
    private void drawPhase(GuiGraphicsExtractor g, Rect map) {
        boolean mining = view().mode() == CarrierPolicy.Mode.MINING.ordinal()
            || miningStage() == CarrierMapUiPolicy.MiningStage.RELEASE;
        String[] names = mining ? new String[]{"走査", "充填", "下降", "解放"}
            : new String[]{"走査", "充填", "照射", "冷却"};
        int active = mining ? miningStage().ordinal() - 1 : switch (view().navigation().effects().phase()) {
            case SCAN -> 0; case CHARGE -> 1; case FIRE -> 2; case COOLDOWN -> 3; default -> -1;
        };
        int y = map.bottom() - 14, part = map.width() / names.length;
        for (int i = 0; i < names.length; i++) {
            int x = map.x() + i * part;
            g.fill(x, y, x + part, y + 14, PANEL);
            text(g, names[i], x + 4, y + 2, part - 8, i == active && confirmation.fresh(now()) ? AMBER : MUTED);
        }
        var effect = view().navigation().effects();
        if (active >= 0 && active < names.length && confirmation.fresh(now())) {
            int x = map.x() + active * part;
            double ratio = mining ? CarrierMapUiPolicy.progress(miningStage(), effect.phaseTick(), effect.phaseDuration())
                : CarrierEffectGeometry.progress(effect.phaseTick(), effect.phaseDuration(), 0);
            int progress = (int)((part - 4) * ratio);
            g.fill(x + 2, y + 12, x + 2 + progress, y + 14, AMBER);
        }
    }
    private CarrierUiPolicy.MapProjection projection(Rect map) {
        return CarrierUiPolicy.projection(map, centerX, centerZ, zoom, confirming(), confirmation.chunkX(), confirmation.chunkZ(),
            tab == Tab.ATTACK ? view().navigation().effects().combatRadius() : 0);
    }
    private int px(Rect r, double x) { return projection(r).x(r, x); }
    private int pz(Rect r, double z) { return projection(r).z(r, z); }
    private void highlight(GuiGraphicsExtractor g, Rect r, int cx, int cz, int color) {
        int x = px(r, cx * 16), z = pz(r, cz * 16), size = (int) Math.round(16 * projection(r).zoom());
        g.outline(x, z, size, size, color); g.outline(x + 1, z + 1, size - 2, size - 2, color);
    }
    @Override protected boolean surfaceClicked(MouseButtonEvent event) {
        if (diagnostics || choosingTab || moveControls || !mapTab() || event.button() != 0 || working() || confirmation.busy() || confirmation.phase() != Phase.SELECT) return false;
        Rect r = CarrierUiPolicy.mapRect(width, height, false);
        if (!r.contains(event.x(), event.y())) return false;
        confirmation.select(CarrierUiPolicy.chunkAt(event.x(), r.x(), r.width(), centerX, zoom),
            CarrierUiPolicy.chunkAt(event.y(), r.y(), r.height(), centerZ, zoom), view().navigation().exterior().dimension());
        notice = ""; build(); return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (mapTab() && !choosingTab && !diagnostics && !moveControls && !confirming()
            && CarrierUiPolicy.mapRect(width, height, false).contains(x, y)) {
            zoom = Math.max(.25, Math.min(8, zoom * (dy > 0 ? 1.25 : .8))); return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 1 && mapTab() && !choosingTab && !diagnostics && !moveControls
            && !confirmation.busy() && confirmation.phase() == Phase.SELECT && CarrierUiPolicy.mapRect(width, height, false).contains(event.x(), event.y())) {
            centerX -= dx / zoom; centerZ -= dy / zoom; return true;
        }
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256 && (confirmation.phase() != Phase.SELECT || choosingTab || diagnostics || moveControls)) { back(); return true; }
        if (event.key() == 258 || event.key() >= 262 && event.key() <= 265) input.choose();
        if (!input.press(event.key(), getFocused() != null)) return true;
        return super.keyPressed(event);
    }
    @Override public boolean keyReleased(KeyEvent event) {
        input.release(event.key()); return super.keyReleased(event);
    }
    @Override protected void setInitialFocus() { clearFocus(); }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean onControl = controls.stream().anyMatch(w -> w.visible && w.isMouseOver(event.x(), event.y()));
        if (!CarrierUiInputPolicy.controlClick(onControl, doubleClick)) return true;
        boolean handled = super.mouseClicked(event, doubleClick);
        if (onControl && getFocused() != null) input.choose();
        return handled;
    }
    private String status() {
        if (!linked() || !confirmation.fresh(now())) return "通信できません";
        if (!view().owner()) return "招待されたプレイヤー";
        if (view().moving()) return "移動中";
        if (view().mode() == 1) return switch (miningStage()) {
            case SCAN -> "採掘範囲を走査中";
            case CHARGE -> "掘削光を充填中";
            case DESCENT -> "掘削光を下降中";
            case RELEASE -> "採掘中";
            default -> "採掘中";
        };
        if (view().mode() == 2) return switch (view().navigation().effects().phase()) {
            case CHARGE -> "攻撃の準備中 / 充填";
            case FIRE -> "敵へ照射中";
            case COOLDOWN -> "攻撃後の冷却中";
            default -> "攻撃状態を確認中";
        };
        if (view().navigation().navigationPaused() && view().stop() == CarrierPolicy.Stop.EMERGENCY.ordinal())
            return "移動は停止中 / 目的地を保存済み";
        int code = view().stop();
        if (code < 0 || code >= CarrierPolicy.Stop.values().length) return "状態を確認できません";
        if (CarrierPolicy.Stop.values()[code].name().equals("LIQUID")) return "液体を検知したため停止";
        return switch (CarrierPolicy.Stop.values()[code]) {
            case FULL -> "貨物がいっぱいです";
            case PROTECTED -> "保護されたブロックがあります";
            case FRIENDLY_IN_AREA -> "旧安全待機を解除中 / 採掘は継続します";
            case UNLOADED -> "対象チャンクを読み込み中 / 完了後に自動再開";
            case OBSTRUCTED -> "進路または照射が遮られています";
            case NO_POWER -> view().mode() == CarrierPolicy.Mode.IDLE.ordinal()
                ? "炉出力による低速航行" : "炉出力で兵装蓄電を回復中";
            case OWNER_ABSENT -> "所有者が操作範囲外です";
            case LEASE_LIMIT -> "同時に動かせる母艦の上限です";
            case NO_SAFE_EXIT -> "安全な下船先がありません";
            case TARGET_LIMIT -> "敵が多すぎるため停止";
            case INVALID_TARGET -> "移動先または作業範囲を指定できません";
            case BUSY -> "別の作業が進行中です";
            case CONTROL_LOST -> "母艦を操作できません";
            case DESTROYED -> "母艦が壊れています";
            case EXPIRED -> "範囲をもう一度確認してください";
            // Mining and combat share completion; saved mining progress may still remain.
            case COMPLETE -> "直前の作業が終わりました";
            case RELOAD, EMERGENCY -> "停止中";
            case PREVIEW -> "範囲を確認中";
            default -> "待機中";
        };
    }

    private String actionAvailability() {
        if (!linked() || !confirmation.fresh(now())) return "命令受付: 通信復旧待ち";
        if (!view().owner()) return "命令受付: 所有者のみ";
        if (view().destroyed()) return "命令受付: 修復不能";
        if (confirmation.busy()) return "命令受付: 応答待ち";
        if (view().stop() == CarrierPolicy.Stop.UNLOADED.ordinal())
            return "現任務: 対象チャンク読込待ち / 自動再開";
        if (view().moving()) return "次の命令: 到着後 " + movementEstimate();
        if (view().mode() != CarrierPolicy.Mode.IDLE.ordinal()) return "次の命令: 現任務完了後 " + operationEstimate();
        return "命令受付: 移動・採掘・攻撃とも今すぐ";
    }

    private String activeEstimate() {
        if (view().stop() == CarrierPolicy.Stop.UNLOADED.ordinal())
            return "残り時間は読込中のため一時停止";
        if (view().moving()) return "到着見込み " + movementEstimate();
        var effect = view().navigation().effects();
        if (view().mode() == CarrierPolicy.Mode.MINING.ordinal()) {
            int total = CarrierActionEstimatePolicy.miningRemainingTicks(effect.phase(), effect.phaseTick(),
                effect.phaseDuration(), view().cursor(), view().total());
            if (view().stop() == CarrierPolicy.Stop.NO_POWER.ordinal()) {
                int ready = CarrierActionEstimatePolicy.energyReadyTicks(view().weaponEnergy(),
                    CarrierPowerPolicy.MINING_STARTUP_ENERGY, CarrierPowerPolicy.WORK_GENERATION_PER_TICK);
                return "兵装電力の回復まで " + formatTicks(ready) + " / 完了目安 " + formatTicks(total);
            }
            String next = switch (miningStage()) {
                case SCAN -> "走査完了"; case CHARGE -> "充填完了"; case DESCENT -> "地表到達";
                case RELEASE -> "掘削完了"; default -> "次段階";
            };
            int phase = CarrierActionEstimatePolicy.phaseRemainingTicks(effect.phaseTick(), effect.phaseDuration());
            return next + "まで " + formatTicks(phase) + " / 完了目安 " + formatTicks(total);
        }
        if (view().mode() == CarrierPolicy.Mode.COMBAT.ordinal()) {
            int phase = CarrierActionEstimatePolicy.phaseRemainingTicks(effect.phaseTick(), effect.phaseDuration());
            String next = switch (effect.phase()) {
                case SCAN -> "標的確定"; case CHARGE -> "照射開始"; case FIRE -> "照射終了";
                case COOLDOWN -> "再実行可能"; default -> "次段階";
            };
            if (view().stop() == CarrierPolicy.Stop.NO_POWER.ordinal()) {
                int required = effect.phase() == CarrierPolicy.WorkPhase.FIRE
                    ? CarrierCombatPolicy.HIT_ENERGY : CarrierCombatPolicy.CHARGE_ENERGY;
                int ready = CarrierActionEstimatePolicy.energyReadyTicks(view().weaponEnergy(), required,
                    CarrierPowerPolicy.WORK_GENERATION_PER_TICK);
                return "兵装電力の回復まで " + formatTicks(ready) + " / 作戦残り "
                    + formatTicks(view().navigation().combatTicks());
            }
            return next + "まで " + formatTicks(phase) + " / 作戦残り "
                + formatTicks(view().navigation().combatTicks());
        }
        return "移動・採掘・攻撃: 実行可能";
    }

    private String operationEstimate() {
        var effect = view().navigation().effects();
        if (view().mode() == CarrierPolicy.Mode.MINING.ordinal()) return formatTicks(
            CarrierActionEstimatePolicy.miningRemainingTicks(effect.phase(), effect.phaseTick(),
                effect.phaseDuration(), view().cursor(), view().total()));
        if (view().mode() == CarrierPolicy.Mode.COMBAT.ordinal()) return formatTicks(view().navigation().combatTicks());
        return "確認中";
    }

    private String movementEstimate() {
        return view().navigation().destination().map(destination -> formatTicks(
            CarrierActionEstimatePolicy.movementRemainingTicks(view().navigation().exterior(), destination)))
            .orElse("確認中");
    }

    private static String formatTicks(int ticks) {
        if (ticks < 0) return "算出不能";
        if (ticks == 0) return "今すぐ";
        if (ticks < 20) return String.format(java.util.Locale.ROOT, "約%.1f秒", ticks / 20.0);
        return "約" + ((ticks + 19) / 20) + "秒";
    }
    private int diagnosticRows() { return Math.max(1, (height - 118) / 12); }
    private List<String> diagnosticLines() {
        CarrierViewPayload v = view();
        return List.of("船 " + v.ship(), "Menu " + menu.containerId + " / View " + v.menu(),
            "次元 " + v.dimension(), "世代 " + v.generation(), "Mode " + v.mode() + " / Preview " + v.previewMode(),
            "範囲 " + v.chunkX() + ", " + v.chunkZ() + " / Y " + v.minY() + ".." + v.maxY(),
            "進捗 " + v.cursor() + "/" + v.total() + " / 期限 " + v.previewTicks(),
            "蓄電 機動 " + v.energy() + " / 兵装 " + v.weaponEnergy(), "補給対象 " + v.bayUsed() + " / 招待者 " + v.guests().size(),
            "所有者 " + v.owner() + " / 破壊 " + v.destroyed() + " / 停止理由 " + v.stop(),
            "外装 " + v.navigation().exterior(), "行先 " + v.navigation().destination(), "攻撃残り " + v.navigation().combatTicks());
    }
    private void drawDiagnostics(GuiGraphicsExtractor g, int mx, int my) {
        List<String> lines = diagnosticLines();
        int first = listPage * diagnosticRows();
        int y = 56;
        for (int i = first; i < Math.min(lines.size(), first + diagnosticRows()); i++) {
            text(g, lines.get(i), 8, y, width - 16, MUTED);
            if (mx >= 8 && mx < width - 8 && my >= y && my < y + 12)
                g.setTooltipForNextFrame(Component.literal(lines.get(i)), mx, my);
            y += 12;
        }
    }

    private static String bayPhase(CarrierViewPayload.Bay bay) {
        var phases = jp.morrowgear.drone.carrier.CarrierServiceBay.Phase.values();
        if (bay.phase() < 0 || bay.phase() >= phases.length) return bay.aligned() ? "補給中" : "状態確認中";
        return switch (phases[bay.phase()]) {
            case HOLDING -> "母艦旋回待ち";
            case APPROACH -> "待機点へ接近";
            case DOCKING -> "収容位置へ移動";
            case SERVICE -> "補給中";
        };
    }
    @Override public void removed() { confirmation.cancel(); terrain.close(); super.removed(); }
}
