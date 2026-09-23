import { createHudScene } from './hud-scene.js';
const P=window.MorrowHmi, catalog=window.MorrowCatalog||[];
let s=P.create(),hudScene=null,lastWingEdit=null,toastTimer=null,routeDraft=[],salvageTarget=null,pointerDrag=null,suppressClick=false,itemFilter='all';
const root=document.getElementById('app'),dialog=document.getElementById('dialog');
const rolePath=role=>`../../detail-scale-v22/icons/${role}-portrait-64.png`;
const itemPath=id=>`../items/${id}-128.png`;
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon=name=>`<i data-lucide="${name}" aria-hidden="true"></i>`;
const button=(label,action,ico,extra='')=>`<button data-action="${action}" ${extra}>${ico?icon(ico):''}${label}</button>`;
const tool=(label,action,ico,extra='')=>`<button class="icon" data-action="${action}" title="${label}" aria-label="${label}" ${extra}>${icon(ico)}</button>`;
const selected=()=>s.units.filter(u=>s.selected.has(u.id));
const wingName=id=>s.wings.find(w=>w.id===id)?.name||'UNASSIGNED';
const stateColor=u=>u.state==='POWER_LOST'?'amber':u.state==='COMBAT'?'red':u.state==='DOCKED'?'muted':'cyan';
const resource=(label,value,type='')=>`<div class="resource ${type}"><span>${label}</span><div class="bar"><i style="width:${Math.max(0,Math.min(100,value))}%"></i></div><span class="mono">${value}%</span></div>`;
function notify(text,error=false) {
  const node=document.getElementById('toast');node.textContent=text;node.className=error?'error':'';node.style.display='block';
  clearTimeout(toastTimer);toastTimer=setTimeout(()=>node.style.display='none',4500);
}
function chrome(content) {
  const nav=[['tactical','作戦地図','map'],['wings','Wing管理','layers'],['missions','任務','list-checks'],['supply','Dock・補給','battery-charging'],['salvage','回収','cable'],['visor','バイザー','scan-eye'],['items','機器・アイテム','package'],['settings','設定','sliders-horizontal']];
  return `<div class="shell"><header class="top"><div class="brand">MORROWGEAR <b>C2</b></div><div class="metrics">
    <div class="metric"><b>${s.units.length}</b><span>UNITS</span></div><div class="metric"><b>${s.wings.length}</b><span>WINGS</span></div>
    <div class="metric"><b class="amber">${s.units.filter(u=>u.state==='RETURN').length}</b><span>RETURN</span></div><div class="metric"><b class="red">${s.fronts.length}</b><span>FRONTS</span></div></div>
    <div class="top-end"><span class="preview">DESIGN PREVIEW</span><select aria-label="検証シナリオ" id="scenario">
    ${[['normal','24機 / 混成作戦'],['eight','8機'],['hundred','100機'],['large','256機'],['empty','0機'],['offline','通信断'],['denied','権限なし'],['critical','複数戦線 / 救難']].map(([v,l])=>`<option value="${v}" ${s.scenario===v?'selected':''}>${l}</option>`).join('')}</select></div></header>
    <nav class="tabs" aria-label="画面">${nav.map(([id,label,i])=>button(label,`view:${id}`,i,`class="${s.view===id?'active':''}" aria-current="${s.view===id?'page':'false'}"`)).join('')}</nav>
    <main>${content}</main><footer class="foot"><span>${s.connected?'<i class="status-dot"></i>':'<b class="amber">LINK LOST</b> '}${s.pending?'応答待ち':s.notice||'LOCAL SIMULATION / ゲーム未接続'}</span><span>${s.selected.size} SELECTED · OVERWORLD · REV ${s.revision}</span></footer></div>`;
}
function unitRow(u) {
  return `<div class="unit-row ${s.selected.has(u.id)?'selected':''}" data-unit="${u.id}" tabindex="0" role="option" aria-selected="${s.selected.has(u.id)}" aria-label="${u.id} ${P.labels[u.role]}">
    <input type="checkbox" aria-label="${u.id}を選択" ${s.selected.has(u.id)?'checked':''}><img src="${rolePath(u.role)}" alt="${u.role}">
    <div class="identity"><strong>${u.id}</strong><small>${wingName(u.wing)} / ${P.labels[u.role]}</small><span class="${stateColor(u)}" style="font-size:11px">${P.states[u.state]}</span></div><span class="percent mono ${u.flight<25?'amber':''}">${u.flight}%</span></div>`;
}
function roster() {
  const list=P.visible(s),pages=Math.max(1,Math.ceil(list.length/s.pageSize));s.page=Math.max(0,Math.min(pages-1,s.page));
  return `<aside class="roster"><div class="section-head"><h2>ユニット</h2><small>${list.length} / ${s.units.length}</small>${tool('地図に戻る','mobile:map','x','class="icon"')}</div>
    <div class="roster-filters"><input id="unit-search" aria-label="ユニット検索" placeholder="ID・Wing・役割" value="${esc(s.query)}"><div class="roster-selects">
    <select id="role-filter" aria-label="役割フィルター"><option value="all">すべての役割</option>${P.roles.map(r=>`<option value="${r}" ${r===s.role?'selected':''}>${P.labels[r]}</option>`).join('')}</select>
    <select id="unit-sort" aria-label="ユニット並び順">${[['id','ID順'],['role','役割順'],['flight','電力順']].map(([v,l])=>`<option value="${v}" ${s.sort===v?'selected':''}>${l}</option>`).join('')}</select></div></div>
    <div class="selection-bar">${button('表示分','select:page',null)}${button('絞込全機','select:filtered',null)}${button('全機','select:all',null)}${tool('選択解除','select:none','square')}</div>
    <div class="unit-list" role="listbox" aria-multiselectable="true">${list.slice(s.page*s.pageSize,(s.page+1)*s.pageSize).map(unitRow).join('')||`<div class="empty">${icon('search-x')}該当する機体なし</div>`}</div>
    <div class="pager">${tool('先頭ページ','page:first','chevrons-left',s.page===0?'disabled':'')}${tool('前ページ','page:prev','chevron-left',s.page===0?'disabled':'')}<span>${s.page+1} / ${pages}</span>${tool('次ページ','page:next','chevron-right',s.page>=pages-1?'disabled':'')}${tool('最終ページ','page:last','chevrons-right',s.page>=pages-1?'disabled':'')}</div></aside>`;
}
function command() {
  const us=selected(),u=us[0];
  const mixed=us.some(v=>v.wing!==u?.wing),summarize=fn=>{const values=[...new Set(us.map(fn))];return values.length>2?`${values.slice(0,2).join(' / ')} +${values.length-2}`:values.join(' / ')||'--';};
  return `<aside class="command"><div class="section-head"><h2>指揮</h2><small>${us.length} SELECTED</small>${tool('地図へ','mobile:map','x')}</div><div class="command-body">
    <div class="unit-heading">${u?`<img src="${rolePath(u.role)}" alt=""><div><strong>${us.length>1?`${mixed?'複数Wing':wingName(u.wing)} / ${us.length}機`:u.id}</strong><small>${us.length>1?'混成選択':P.labels[u.role]} · ${us.length>1?'資源は最小値':P.states[u.state]}</small></div>`:'<strong>選択なし</strong>'}</div>
    <div class="resources">${resource('飛行電力',us.length?Math.min(...us.map(u=>u.flight)):0)}${resource('兵装電力',us.length?Math.min(...us.map(u=>u.weapon)):0,'weapon')}${resource('機体状態',us.length?Math.min(...us.map(u=>u.hull)):0,'hull')}</div>
    <div><p class="section-label">FLIGHT / 即時指示</p><div class="command-grid">${[['follow','追従','navigation'],['hold','待機','pause'],['return','Dock帰還','corner-down-left'],['route','ルート','route']].map(([a,l,i])=>button(l,`command:${a}`,i,`title="${esc(P.eligible(s,a)||l)}" ${P.eligible(s,a)?'disabled':''}`)).join('')}</div></div>
    <div><p class="section-label">MISSION / 任務</p><div class="command-grid">${[['work','作業','pickaxe'],['cargo','輸送','package'],['guard','迎撃','crosshair'],['salvage','回収','cable']].map(([a,l,i])=>button(l,`command:${a}`,i,`title="${esc(P.eligible(s,a)||l)}" ${P.eligible(s,a)?'disabled':''}`)).join('')}</div></div>
    <dl class="kv"><dt>所属Wing</dt><dd>${summarize(v=>wingName(v.wing))}</dd><dt>現在任務</dt><dd>${summarize(v=>v.mission||'未割当')}</dd><dt>一時群</dt><dd>${summarize(v=>v.state==='COMBAT'?`ATTACK / ${v.target||'目標確認中'}`:'なし')}</dd><dt>復帰先</dt><dd>${summarize(v=>v.resume||v.mission||'未設定')}</dd><dt>ホームDock</dt><dd>${summarize(v=>v.dock||'未登録')}</dd><dt>データ</dt><dd class="${s.connected?'green':'amber'}">${!s.connected?'通信断 / 最終確認位置':us.some(v=>!v.fresh)?'最終受信の機体を含む':'最新 / 0.2秒前'}</dd></dl>
    ${button('ユニット詳細','unit-detail','scan',us.length!==1?'disabled':'')}${button('格納','command:store','archive',`class="danger" ${P.eligible(s,'store')?'disabled':''}`)}
    </div></aside>`;
}
function mapMarkup(route=false) {
  return `<section class="map" aria-label="戦術地図"><canvas id="map-canvas" aria-label="周辺地形とルート"></canvas><div class="map-overlay"><strong>OVERWORLD / NORTH SECTOR</strong><small>${route?'ROUTE DRAFT':`${s.units.length} UNITS / ${s.wings.length} WINGS`} · N ↑</small></div>
    <div class="map-tools">${tool('拡大','zoom:in','plus')}${tool('縮小','zoom:out','minus')}${tool('原点に戻す','zoom:reset','locate-fixed')}</div><div class="map-scale">${Math.round(64/s.zoom)} blocks</div>
    <div id="map-markers"></div><div class="mobile-controls">${button('一覧','mobile:roster','list')}${button('指揮','mobile:command','send')}</div>
    <div class="map-mode">${button('ルート編集','command:route','route')}${button('地点移動','map:move','map-pin',`aria-pressed="${s.routeEditing}"`)}</div></section>`;
}
function tactical() {
  return `<div class="tactical">${roster()}${mapMarkup()}${command()}<div class="mission-band"><section><h3>進行中の作戦</h3>${s.missions.slice(0,2).map(m=>`<div class="mission-line"><span class="cyan">${m.id}</span><div>${m.name}<div class="bar"><i style="width:${m.progress}%"></i></div></div><span>${m.progress}%</span></div>`).join('')}</section>
    <section><h3>戦線 / 一時攻撃群</h3>${s.fronts.slice(0,2).map(f=>`<div class="mission-line"><span class="red">${f.id}</span><div>${f.target} ${f.hostiles} / 交戦 ${f.engaged}機</div><span class="amber">${f.priority}</span></div>`).join('')}</section></div></div>`;
}
function slot(u) {
  return `<div class="wing-slot filled ${s.selected.has(u.id)?'selected':''}" data-drag-unit="${u.id}" data-unit="${u.id}" tabindex="0" role="button" aria-label="${u.id} ${wingName(u.wing)}"><img draggable="false" src="${rolePath(u.role)}" alt="${u.role}"><span>${u.id}</span><span class="state-mini ${stateColor(u)}">${P.states[u.state]}</span></div>`;
}
function wings() {
  return `<div class="page"><div class="page-toolbar"><h1>Wing管理</h1><p>${s.selected.size}機選択 / 上限8機</p>${button('選択機から作成','wing:new','plus')}${button('編集を戻す','wing:undo','undo-2',lastWingEdit?'':'disabled')}</div>
    <div class="split"><div class="stack"><div class="wing-grid">${s.wings.map(w=>{
      const us=s.units.filter(u=>u.wing===w.id);
      return `<section class="wing-card ${us.length===8?'full':''}" data-wing="${w.id}"><header><div><h2>${w.name}</h2><small>${w.mission||'任務未割当'} · ${us.find(u=>!['POWER_LOST','RETURN','DOCKED'].includes(u.state))?.id||'候補なし'} LEAD</small></div><strong>${us.length}/8</strong></header>
        <div class="wing-slots">${us.map(slot).join('')}${Array.from({length:8-us.length},(_,i)=>`<div class="wing-slot"><span class="muted">${String(us.length+i+1).padStart(2,'0')}</span>${icon('plus')}</div>`).join('')}</div>
        <footer>${button('選択','wing:select:'+w.id,'check-check')}${button('地図で指示','wing:map:'+w.id,'map')}</footer></section>`;
    }).join('')||'<div class="empty">Wing未作成</div>'}</div>
    <section class="unassigned" data-wing=""><h2>未所属</h2>${s.units.filter(u=>!u.wing).map(slot).join('')||'<small>0機</small>'}</section></div>
    <aside class="support-panel"><h2>編入先</h2><p class="muted">選択 ${s.selected.size}機</p><select id="join-target" aria-label="編入先Wing"><option value="">未所属</option>${s.wings.map(w=>`<option value="${w.id}">${w.name} / ${s.units.filter(u=>u.wing===w.id).length}機</option>`).join('')}</select>
    ${button('選択機を編入','wing:join','log-in','class="primary"')}<hr><dl class="kv"><dt>任務あり</dt><dd>指定Wingへ合流して参加</dd><dt>任務なし</dt><dd>現在の個別任務を維持</dd><dt>満員</dt><dd>編入を拒否・元の所属維持</dd><dt>離脱先</dt><dd>未所属 / 自動Wing対象</dd></dl><hr>${button('選択をすべて解除','select:none','square')}</aside></div></div>`;
}
function missions() {
  return `<div class="page"><div class="page-toolbar"><h1>任務管理</h1>${button('ルートを作成','command:route','route')}${button('作業を割当','command:work','pickaxe')}${button('輸送を割当','command:cargo','package')}</div>
    <div class="table-wrap"><table><thead><tr><th>任務</th><th>担当Wing / 役割</th><th>進捗</th><th>状態・再開条件</th><th></th></tr></thead><tbody>${s.missions.map(m=>`<tr><td><strong>${m.name}</strong><small>${m.id} / ${m.type}</small></td><td>${s.wings.filter(w=>w.mission===m.id).map(w=>w.name).join(', ')||'個別割当'}<small>${m.type==='EXCAVATE'?'SCOUT → ENGINEER → CARGO':m.type==='GUARD'?'SECURITY / データリンク':''}</small></td><td><div class="bar" style="width:110px"><i style="width:${m.progress}%"></i></div><small>${m.progress}%</small></td><td class="${m.status==='ACTIVE'?'green':'amber'}">${m.status}<small>${m.status==='PAUSED'?'利用可能な対象を再確認':'帰還・回収中の機体を除外'}</small></td><td>${tool('任務の詳細',`mission:detail:${m.id}`,'panel-right-open')}${tool(m.status==='ACTIVE'?'一時停止':'再開',`mission:toggle:${m.id}`,m.status==='ACTIVE'?'pause':'play')}</td></tr>`).join('')}</tbody></table></div>
    <div class="data-band"><div class="page-toolbar"><h2>個別ユニット / 現在・中断・復帰</h2><span class="muted">先頭16機 / 詳細は作戦地図</span></div><div class="table-wrap"><table><thead><tr><th>機体</th><th>所属</th><th>現在の動作</th><th>中断している任務</th><th>次の動作</th></tr></thead><tbody>${s.units.slice(0,16).map(u=>`<tr><td class="row-title"><img src="${rolePath(u.role)}" alt=""><strong>${u.id}</strong></td><td>${wingName(u.wing)}</td><td class="${stateColor(u)}">${P.states[u.state]}</td><td>${u.resume||u.mission||'なし'}</td><td>${u.state==='POWER_LOST'?'救難回収 → 整備':u.state==='DOCKED'?'補給可否と作戦需要を再判定':u.state==='COMBAT'?'目標消失 → 復帰先を再検証':'継続'}</td></tr>`).join('')}</tbody></table></div></div></div>`;
}
function supply() {
  return `<div class="page"><div class="page-toolbar"><h1>Dock・補給</h1><span class="muted">ホームDock / サービス給電を区別</span>${button('選択機の帰投先','dock:assign','map-pin')}</div>
    <div class="table-wrap"><table><thead><tr><th>ホームDock</th><th>着艦機</th><th>飛行電力源</th><th>兵装・弾薬資源</th><th>復帰判定</th><th></th></tr></thead><tbody>${s.docks.map(d=>`<tr><td><div class="row-title"><img src="${itemPath('dock_item')}" alt="Dock"><div><strong>${d.name}</strong><small>HOME / 1:1</small></div></div></td><td>${d.occupied||'空き'}</td><td class="${d.energy?'green':'amber'}">${d.energy}%</td><td>${d.ammo}%<small>${d.ammo?'利用可能':'補給資源なし'}</small></td><td>${d.energy?'任務・安全帰投量を照合':'不足理由を確認'}<small>${d.energy?'一律100%待ちはしない':'代替Dock / サービス給電'}</small></td><td>${button('サービス詳細',`dock:detail:${d.id}`,'battery-charging')}</td></tr>`).join('')}</tbody></table></div>
    <section class="data-band"><h2>浮遊サービスDock</h2>${s.stations.map(st=>`<div class="services"><img src="${itemPath('solar_service_station')}" alt="サービスDock"><div><h3>${st.name} <span class="cyan">${st.energy}%</span></h3><small>飛行電力のみ / 兵装・弾薬はホームDock</small><div class="berths">${st.slots.map((id,i)=>`<div class="berth ${id?'':'free'}">${icon(id?'zap':'circle')}<span>${i+1} / ${id||'空き'}</span></div>`).join('')}</div></div></div>`).join('')}</section>
    <section class="data-band"><h2>周辺コンテナ / 32 blocks</h2><div class="table-wrap"><table><tbody>${s.containers.map(c=>`<tr><td>${c.name}</td><td>${c.count} items</td><td class="${c.full?'amber':'green'}">${c.full?'満杯':'利用可能'}</td><td>${button('搬出元',`cargo:source:${c.id}`,'package-minus',s.source===c.id?'class="active"':'')}${button('搬入先',`cargo:dest:${c.id}`,'package-plus',s.destination===c.id?'class="active"':'')}</td></tr>`).join('')}</tbody></table></div></section></div>`;
}
function salvage() {
  const lost=s.units.filter(u=>u.state==='POWER_LOST'),rescuers=s.units.filter(u=>u.role==='salvage'&&u.state!=='POWER_LOST');
  return `<div class="page"><div class="page-toolbar"><h1>救難・回収</h1><span class="amber">${lost.length} BEACONS</span>${button('Salvage機を選択','salvage:select','cable')}</div>
    <div class="split"><div class="table-wrap"><table><thead><tr><th>機体ビーコン</th><th>距離 / データ</th><th>回収担当</th><th>搬送先</th><th></th></tr></thead><tbody>${lost.map((u,i)=>`<tr><td><div class="row-title"><img src="${rolePath(u.role)}" alt=""><div><strong>${u.id}</strong><small class="amber">POWER LOST / ${P.labels[u.role]}</small></div></div></td><td>${(i+1)*1280} blocks<small>${u.fresh?'現在位置':'最終受信 / 12秒前'}</small></td><td>${rescuers[0]?.id||'利用可能機なし'}</td><td>${rescuers[0]?.dock||'要指定'}</td><td>${button('回収計画',`salvage:plan:${u.id}`,'cable',rescuers.length?'':'disabled')}</td></tr>`).join('')||'<tr><td colspan="5" class="empty">救難ビーコンなし</td></tr>'}</tbody></table></div>
    <aside class="support-panel"><img src="${rolePath('salvage')}" alt="Salvage" style="width:100%;height:130px;object-fit:contain"><h2>回収経路</h2><dl class="kv"><dt>進入</dt><dd>対象上空へ移動</dd><dt>吊上げ</dt><dd>荷の最下面に地形余裕を確保</dd><dt>搬送先</dt><dd>出発時のサービスDock</dd><dt>到着後</dt><dd>修復・補給可否を照合</dd><dt>Dock消失</dt><dd>代替先を再探索</dd></dl><hr><h2>手動回収</h2><div class="row-title"><img src="${itemPath('recovery_tool')}" alt="回収ツール"><span>機体回収ツール</span></div><small>所有・距離・搭載品を確認してアイテム化</small></aside></div></div>`;
}
function visor() {
  const h=P.hud(s,innerWidth),u=h.focus;
  const work=s.missions.find(m=>m.type==='EXCAVATE'&&s.units.some(u=>u.mission===m.id)),workers=s.units.filter(u=>u.mission===work?.id);
  const event=s.events[0];
  return `<div class="hud-stage" id="hud-stage"><div class="hud-top"><div class="hud-chip">${h.unitCount} UNITS / ${h.wingCount} WINGS</div><div class="hud-chip">N · OVERWORLD</div></div>
    <div class="hud-ops">${h.fronts.map(f=>`<div class="hud-front"><strong class="amber">${f.id} / ${f.target}</strong><div>${f.hostiles} HOSTILES · ${f.engaged} ENGAGED</div></div>`).join('')}${h.extraFronts?`<div class="hud-chip">+${h.extraFronts} FRONTS</div>`:''}
    ${work?`<div class="hud-front" style="border-color:var(--green)"><strong class="green">${work.id} / 採掘 ${work.progress}%</strong><div>ENGINEER ${workers.filter(u=>u.role==='engineer').length} · CARGO ${workers.filter(u=>u.role==='cargo').length}</div></div>`:''}</div>
    ${u?`<div class="hud-focus"><h3>${u.id} / ${P.labels[u.role]}</h3><span class="${stateColor(u)}" style="font-size:12px">${P.states[u.state]}</span><div class="resources">${resource('FLT',u.flight)}${resource('WPN',u.weapon,'weapon')}${resource('STR',u.hull,'hull')}</div></div>`:''}
    <div class="hud-markers" id="hud-markers"></div><div class="crosshair"></div>
    ${s.settings.subtitles&&event?`<div class="hud-caption">${esc(event.text)}<small> / ${esc(event.action)}</small></div>`:''}
    ${h.lost?`<div class="hud-lost">${icon('radio-tower')} POWER LOST ${h.lost} / 1,280 blocks</div>`:''}
    <div class="hud-hotbar-safe">${'<i></i>'.repeat(9)}</div><div class="hud-camera">${tool('左を見る','camera:left','chevron-left')}${tool('右を見る','camera:right','chevron-right')}${tool('上を見る','camera:up','chevron-up')}${tool('下を見る','camera:down','chevron-down')}${tool('正面に戻す','camera:reset','focus')}</div><div class="hud-legend">3D DESIGN SCENE / ゲーム未接続</div></div>`;
}
function items() {
  return `<div class="page"><div class="page-toolbar"><h1>機器・アイテム</h1><span class="muted">23登録アイテム / 同一正本から生成</span></div>
    <div class="catalog-tools"><select id="item-filter" aria-label="アイテム分類">${[['all','全アイテム'],['equipment','機器・施設'],['module','役割モジュール'],['power','電力'],['material','中間素材'],['legacy','互換用兵装アイテム']].map(([v,l])=>`<option value="${v}" ${v===itemFilter?'selected':''}>${l}</option>`).join('')}</select></div>
    <div class="catalog-grid">${catalog.filter(i=>itemFilter==='all'||i.category===itemFilter).map(i=>`<button class="item-card" data-action="item:${i.id}"><img src="${itemPath(i.id)}" alt="${esc(i.name)}"><strong>${esc(i.name)}</strong><small>${i.id}</small></button>`).join('')}</div></div>`;
}
function settings() {
  return `<div class="page"><div class="page-toolbar"><h1>表示・HMI設定</h1>${button('初期値に戻す','settings:reset','rotate-ccw')}</div><div class="settings-grid"><section><h2>バイザー</h2>
    <label class="setting"><span>情報密度<small>重大事象は省略しない</small></span><select id="density">${[['adaptive','自動'],['compact','最小'],['full','詳細']].map(([v,l])=>`<option value="${v}" ${s.settings.density===v?'selected':''}>${l}</option>`).join('')}</select></label>
    <label class="setting"><span>本文サイズ<small>12px未満に縮小しない</small></span><select id="text-size">${[14,16,18].map(n=>`<option value="${n}" ${s.settings.text===n?'selected':''}>${n}px</option>`).join('')}</select></label>
    ${[['markers','近接マーカー'],['contrast','高コントラスト'],['motion','プレビューの機体移動']].map(([k,l])=>`<label class="setting"><span>${l}</span><input type="checkbox" data-setting="${k}" ${s.settings[k]?'checked':''}></label>`).join('')}</section>
    <section><h2>機械通信</h2>${[['subtitles','通信字幕'],['radio','音声通知']].map(([k,l])=>`<label class="setting"><span>${l}<small>${k==='radio'?'音声サンプル未搭載 / 設定の確認のみ':'通常完了は集約。危険・救難を優先'}</small></span><input type="checkbox" data-setting="${k}" ${s.settings[k]?'checked':''}></label>`).join('')}
    <div class="data-band"><h3>発信の優先順位</h3><div class="event amber">${icon('radio-tower')}電力喪失・回収不能</div><div class="event red">${icon('crosshair')}迎撃・増援要請</div><div class="event">${icon('battery-charging')}補給帰還・任務復帰</div><div class="event muted">${icon('check')}作業完了・定常報告</div></div></section></div>
    <section class="data-band"><h2>指示イベント</h2>${s.events.map(e=>`<div class="event"><span class="mono muted">${e.id}</span><span>${e.action}</span><span>${e.text}</span></div>`).join('')||'<p class="muted">履歴なし</p>'}</section></div>`;
}
function render() {
  if(hudScene){hudScene.dispose();hudScene=null;}
  s.fronts.forEach(f=>f.engaged=s.units.filter(u=>u.state==='COMBAT'&&u.target===f.id).length);
  root.innerHTML=chrome(({tactical,wings,missions,supply,salvage,visor,items,settings})[s.view]());
  document.documentElement.style.fontSize=s.settings.text+'px';
  document.body.classList.toggle('high-contrast',s.settings.contrast);
  window.lucide.createIcons();
  if(s.view==='tactical')drawMap();
  if(s.view==='visor')hudScene=createHudScene(document.getElementById('hud-stage'),s,P);
}
const terrain=new Image();terrain.src='terrain.png';terrain.onload=()=>{if(s.view==='tactical')drawMap();};
function drawMap(canvas=document.getElementById('map-canvas'),route=s.route) {
  if(!canvas)return;const rect=canvas.getBoundingClientRect();if(rect.width<1)return;
  const dpr=Math.min(2,devicePixelRatio||1),w=rect.width,h=rect.height;canvas.width=w*dpr;canvas.height=h*dpr;const c=canvas.getContext('2d');c.scale(dpr,dpr);
  c.fillStyle='#26332e';c.fillRect(0,0,w,h);if(terrain.complete&&terrain.naturalWidth){c.globalAlpha=.74;c.drawImage(terrain,0,0,w,h);c.globalAlpha=1;}
  c.strokeStyle='#d5ede117';c.lineWidth=1;for(let x=0;x<w;x+=32*s.zoom){c.beginPath();c.moveTo(x,0);c.lineTo(x,h);c.stroke();}for(let y=0;y<h;y+=32*s.zoom){c.beginPath();c.moveTo(0,y);c.lineTo(w,y);c.stroke();}
  const pos=p=>[w/2+(p.x-50)*w/100*s.zoom,h/2+(p.z-50)*h/100*s.zoom];
  c.lineWidth=2;c.strokeStyle='#8ee6df';c.setLineDash([7,5]);c.beginPath();route.forEach((p,i)=>{const [x,y]=pos(p);if(i)c.lineTo(x,y);else c.moveTo(x,y);});if(route.length>2&&s.routeClosed)c.closePath();c.stroke();c.setLineDash([]);
  route.forEach((p,i)=>{const[x,y]=pos(p);c.fillStyle='#14292ae8';c.beginPath();c.arc(x,y,13,0,Math.PI*2);c.fill();c.strokeStyle='#8ee6df';c.stroke();c.fillStyle='white';c.font='13px Segoe UI';c.textAlign='center';c.textBaseline='middle';c.fillText(i+1,x,y);});
  c.strokeStyle='#ffbd6966';c.fillStyle='#ffbd6918';c.beginPath();c.ellipse(w*.72,h*.72,w*.12,h*.17,0,0,Math.PI*2);c.fill();c.stroke();
  if(canvas.id==='map-canvas') {
    const node=document.getElementById('map-markers');if(!node)return;
    const shown=s.units.length>32?s.wings.map(w=>s.units.find(u=>u.wing===w.id)).filter(Boolean):s.units;
    node.innerHTML=shown.map(u=>{const[x,y]=pos(u);if(x<16||x>w-16||y<16||y>h-16)return'';return `<button class="map-marker ${s.selected.has(u.id)?'selected':''}" data-unit="${u.id}" style="left:${x}px;top:${y}px" aria-label="地図の${u.id}"><img src="../../detail-scale-v22/icons/${u.role}-map-32.png" alt="">${s.selected.has(u.id)&&s.units.indexOf(u)%8===0?`<span>${wingName(u.wing)} / ${s.units.length>32?s.units.filter(x=>x.wing===u.wing).length+'機':u.id}</span>`:''}</button>`;}).join('')+`<button class="map-marker hostile" style="left:74%;top:70%" data-action="front:F1" aria-label="FRONT 1">${icon('diamond')}<span>F1 / ${s.fronts[0]?.hostiles||0} HOSTILES</span></button>`;
    window.lucide.createIcons();
    canvas.ondblclick=e=>{const r=canvas.getBoundingClientRect(),p={x:Math.round(50+(e.clientX-r.left-r.width/2)*100/(r.width*s.zoom)),z:Math.round(50+(e.clientY-r.top-r.height/2)*100/(r.height*s.zoom)),y:84};send('move',{point:p});};
    canvas.onclick=e=>{if(s.routeEditing)canvas.ondblclick(e);};
  }
}
function modal(title,body,footer='') {
  dialog.innerHTML=`<header><h2>${title}</h2>${tool('閉じる','dialog:close','x')}</header><div class="body">${body}</div><footer>${button('閉じる','dialog:close',null)}${footer}</footer>`;
  dialog.showModal();window.lucide.createIcons();
}
function send(action,payload={}) {
  const result=P.issue(s,action,payload);if(!result.ok){notify(result.error,true);return;}
  if(dialog.open)dialog.close();render();
  setTimeout(()=>{const answer=P.ack(s,true);notify(s.notice,!answer.ok);render();},400);
}
function routeModal() {
  const error=P.eligible(s,'route');if(error){notify(error,true);return;}
  routeDraft=s.route.map(p=>({...p}));showRoute();
}
function showRoute() {
  if(dialog.open)dialog.close();
  modal('ルート割当',`<div class="route-edit"><div class="route-preview"><canvas id="route-canvas" aria-label="経由点地図"></canvas></div><div class="route-controls"><strong>${s.selected.size}機 / ${routeDraft.length}経由点</strong>
    <div class="route-list">${routeDraft.map((p,i)=>`<div class="route-point"><span class="mono cyan">${i+1}</span><div class="coords"><input type="number" aria-label="点${i+1} X" data-point="${i}" data-axis="x" value="${p.x}"><input type="number" aria-label="点${i+1} Z" data-point="${i}" data-axis="z" value="${p.z}"></div>${tool('点'+(i+1)+'を上へ',`route:up:${i}`,'arrow-up',i===0?'disabled':'')}${tool('点'+(i+1)+'を下へ',`route:down:${i}`,'arrow-down',i===routeDraft.length-1?'disabled':'')}${tool('点'+(i+1)+'を削除',`route:remove:${i}`,'x')}</div>`).join('')}</div>
    ${button('経由点を追加','route:add','plus',routeDraft.length>=8?'disabled':'')}<label><span>共通飛行高度 Y</span><input type="number" aria-label="ルート高度" id="route-altitude" value="${routeDraft[0]?.y||84}" min="-64" max="320"></label>
    <div class="notice-inline">開始点: 現在位置から最も近い経由点<br>1点: 周辺旋回 / 2点: 往復 / 3点以上: 周回</div></div></div>`,button('ルートを送信','route:send','send','class="primary"'));
  requestAnimationFrame(()=>drawMap(document.getElementById('route-canvas'),routeDraft));
}
function commandModal(action) {
  const error=P.eligible(s,action);if(error){notify(error,true);return;}
  if(action==='route')return routeModal();
  if(['follow','hold','return'].includes(action))return send(action);
  if(action==='store')return modal('選択機を格納',`<p>${s.selected.size}機をアイテム化します。機体ID、搭載品、電力を保持します。</p>`,button('格納を確定','confirm:store','archive','class="danger"'));
  if(action==='cargo')return modal('輸送任務',`<label><span>搬出元</span><select id="cargo-source">${s.containers.map(c=>`<option value="${c.id}" ${s.source===c.id?'selected':''}>${c.name} / ${c.count} items</option>`).join('')}</select></label><label><span>搬入先</span><select id="cargo-target">${s.containers.map(c=>`<option value="${c.id}" ${s.destination===c.id?'selected':''}>${c.name}${c.full?' / 満杯':''}</option>`).join('')}</select></label><div class="notice-inline">コンテナの利用権を取得後に搬出。満杯・破壊・権限変更は再判定。</div>`,button('輸送を送信','confirm:cargo','send','class="primary"'));
  if(action==='work')return modal('作業任務',`<label><span>作業</span><select id="work-type"><option value="EXCAVATE">領域採掘</option><option value="ORE">鉱石調査・採掘</option><option value="FORESTRY">伐採・植林</option></select></label><label><span>アンカー</span><select id="work-anchor"><option>採掘セクター / 192, 72, -48</option><option>森林セクター / 88, 68, 224</option></select></label><dl class="kv"><dt>Scout</dt><dd>領域調査とデータ共有</dd><dt>Engineer</dt><dd>領域を順に作業</dd><dt>Cargo</dt><dd>回収開始まで作業円で待機</dd><dt>終了時</dt><dd>未回収品・次任務・補給を再判定</dd></dl>`,button('作業を送信','confirm:work','send','class="primary"'));
  if(action==='guard')return modal('迎撃指示',`<label><span>目標戦線</span><select id="guard-target">${s.fronts.map(f=>`<option value="${f.id}">${f.id} / ${f.target} / ${f.hostiles} HOSTILES</option>`).join('')}</select></label><dl class="kv"><dt>実所属</dt><dd>Wingを維持</dd><dt>戦闘中</dt><dd>一時攻撃群へ参加</dd><dt>終了時</dt><dd>元任務の有効性を確認して復帰</dd></dl>`,button('迎撃を送信','confirm:guard','send','class="primary"'));
  if(action==='salvage'){s.view='salvage';render();}
}
function itemDetail(id) {
  const it=catalog.find(i=>i.id===id);if(!it)return;
  modal(it.name,`<div class="item-detail"><div><img class="hero" id="item-view" src="${it.viewBase}/hero.png" alt="${esc(it.name)} 実モデル"><div class="view-strip">${[['hero','俯瞰'],['front','前'],['rear','後'],['top','上'],['bottom','下'],['left','左'],['right','右']].map(([v,l])=>button(l,`item-view:${id}:${v}`,null)).join('')}</div></div><div><p class="section-label">CANONICAL MODEL</p><h2>${it.id}</h2><p style="margin:14px 0;line-height:1.8">${esc(it.recognition)}</p><div class="swatches"><span class="swatch" style="background:#30373a" title="Graphite"></span><span class="swatch" style="background:#7e8e92" title="Titanium"></span><span class="swatch" style="background:#72dce0" title="Data cyan"></span><span class="swatch" style="background:#e5ab5c" title="Safety amber"></span></div><p class="section-label">ACTUAL PIXEL SIZE</p><div class="native-icons">${[24,32,64].map(n=>`<div><img src="../items/${id}-${n}.png" width="${n}" height="${n}" alt="${n}px"><small style="display:block">${n}px</small></div>`).join('')}</div><dl class="kv"><dt>正本</dt><dd>Blender polygon mesh</dd><dt>対称軸</dt><dd>X = 0</dd><dt>ID</dt><dd>既存IDを保持</dd><dt>反映</dt><dd>設計候補 / ゲーム未反映</dd></dl><a href="${it.glb}">GLBモデル</a> · <a href="${it.blend}">Blender正本</a>${it.category==='legacy'?'<div class="notice-inline" style="margin-top:12px">互換用の既存アイテム。新しい機体外付け兵装モジュール方式の採用を意味しません。</div>':''}</div></div>`);
}
function detailUnit() {
  const u=selected()[0];if(!u)return;
  modal(`${u.id} / ${P.labels[u.role]}`,`<div class="item-detail"><img class="hero" src="../../detail-scale-v22/${u.role}/active.png" alt="${u.role} 作動状態"><div><div class="resources">${resource('飛行電力',u.flight)}${resource('兵装電力',u.weapon,'weapon')}${resource('耐久',u.hull,'hull')}</div><dl class="kv"><dt>状態</dt><dd>${P.states[u.state]}</dd><dt>所属Wing</dt><dd>${wingName(u.wing)}</dd><dt>現任務</dt><dd>${u.mission}</dd><dt>中断任務</dt><dd>${u.resume||'なし'}</dd><dt>目標</dt><dd>${u.target||'なし'}</dd><dt>弾薬</dt><dd>${u.ammo} / MISSILE ${u.missiles}</dd><dt>HEAT</dt><dd>${u.heat}% / 自然冷却</dd></dl><label><span>Dock装着ロール</span><select id="new-role" ${u.state==='DOCKED'?'':'disabled'}>${P.roles.map(r=>`<option value="${r}" ${u.role===r?'selected':''}>${P.labels[r]}</option>`).join('')}</select></label>${u.state!=='DOCKED'?'<small>役割変更には着艦が必要</small>':''}</div></div>`,button('役割を適用','confirm:role','check',`${u.state==='DOCKED'?'':'disabled'} class="primary"`));
}
function handleAction(action) {
  const [type,arg,extra]=action.split(':');
  if((type==='mission'&&arg==='toggle'||type==='dock'&&arg==='apply'||type==='wing'&&['join','new','undo'].includes(arg))&&(!s.connected||!s.permission||s.pending)){
    notify(!s.connected?'通信断のため変更できません':!s.permission?'操作権限がありません':'前の指示の応答待ちです',true);return;
  }
  if(type==='view'){s.view=arg;render();return;}
  if(type==='dialog'){dialog.close();return;}
  if(type==='select'){if(arg==='none')s.selected.clear();else P.selectScope(s,arg);render();return;}
  if(type==='page'){const last=Math.max(0,Math.ceil(P.visible(s).length/s.pageSize)-1);s.page=arg==='first'?0:arg==='last'?last:arg==='next'?s.page+1:s.page-1;render();return;}
  if(type==='mobile'){document.querySelector('.tactical')?.classList.remove('show-roster','show-command');if(arg!=='map')document.querySelector('.tactical')?.classList.add('show-'+arg);drawMap();return;}
  if(type==='zoom'){s.zoom=arg==='reset'?1:Math.max(.5,Math.min(3,s.zoom+(arg==='in'?.25:-.25)));drawMap();return;}
  if(type==='map'){s.routeEditing=!s.routeEditing;render();return;}
  if(type==='camera'){hudScene?.look(arg);return;}
  if(type==='command'){commandModal(arg);return;}
  if(type==='unit-detail'){detailUnit();return;}
  if(type==='item'){itemDetail(arg);return;}
  if(type==='item-view'){const it=catalog.find(i=>i.id===arg);document.getElementById('item-view').src=`${it.viewBase}/${extra}.png`;return;}
  if(type==='route') {
    const i=Number(extra);
    if(arg==='add'&&routeDraft.length<8)routeDraft.push({x:Math.min(86,25+routeDraft.length*11),z:Math.min(85,28+routeDraft.length*9),y:84});
    if(arg==='remove')routeDraft.splice(i,1);
    if(arg==='up'&&i>0)[routeDraft[i-1],routeDraft[i]]=[routeDraft[i],routeDraft[i-1]];
    if(arg==='down'&&i<routeDraft.length-1)[routeDraft[i+1],routeDraft[i]]=[routeDraft[i],routeDraft[i+1]];
    if(arg==='send'){const y=Number(document.getElementById('route-altitude').value);send('route',{route:routeDraft.map(p=>({...p,y}))});return;}
    showRoute();return;
  }
  if(type==='confirm') {
    if(arg==='cargo')send(arg,{source:document.getElementById('cargo-source').value,destination:document.getElementById('cargo-target').value});
    else if(arg==='work')send(arg,{type:document.getElementById('work-type').value,anchor:document.getElementById('work-anchor').value});
    else if(arg==='guard')send(arg,{target:document.getElementById('guard-target').value});
    else if(arg==='salvage')send(arg,{target:salvageTarget,destination:selected()[0]?.dock});
    else if(arg==='role')send(arg,{role:document.getElementById('new-role').value});
    else send(arg);
    return;
  }
  if(type==='wing') {
    if(arg==='select'||arg==='map'){s.selected=new Set(s.units.filter(u=>u.wing===extra).map(u=>u.id));if(arg==='map')s.view='tactical';render();return;}
    if(arg==='undo'&&lastWingEdit){s.units=lastWingEdit.units;s.wings=lastWingEdit.wings;s.revision++;lastWingEdit=null;render();return;}
    lastWingEdit={units:s.units.map(u=>({...u})),wings:s.wings.map(w=>({...w}))};
    const result=arg==='new'?P.newWing(s):P.moveToWing(s,[...s.selected],document.getElementById('join-target').value||null);
    notify(result.ok?'Wing構成を更新しました':result.error,!result.ok);render();return;
  }
  if(type==='mission') {
    const m=s.missions.find(m=>m.id===extra);if(!m)return;
    if(arg==='toggle'){m.status=m.status==='ACTIVE'?'PAUSED':'ACTIVE';s.revision++;render();return;}
    modal(m.name,`<dl class="kv"><dt>現在</dt><dd>${m.status} / ${m.progress}%</dd><dt>担当</dt><dd>${s.wings.filter(w=>w.mission===m.id).map(w=>w.name).join(', ')}</dd><dt>中断理由</dt><dd>補給・迎撃・救難は個別に追跡</dd><dt>再開判定</dt><dd>対象・所有・ディメンション・残作業を再照合</dd><dt>対象消失</dt><dd>同任務の残作業 → Wing最新任務 → 安全帰投</dd></dl>`);return;
  }
  if(type==='dock') {
    const d=s.docks.find(d=>d.id===extra)||s.docks[0];
    if(arg==='assign'){modal('ホームDock割当',`<label><span>帰投先</span><select id="assign-dock">${s.docks.map(d=>`<option value="${d.id}">${d.name} ${d.occupied?' / 使用中':' / 空き'}</option>`).join('')}</select></label>`,button('割当を確認','dock:apply','check','class="primary"'));return;}
    if(arg==='apply'){const d=s.docks.find(d=>d.id===document.getElementById('assign-dock').value);if(s.selected.size!==1||d.occupied||s.units.some(u=>u.dock===d.id&&!s.selected.has(u.id))){notify('1機を選択し、未登録の空きDockを指定してください',true);return;}selected()[0].dock=d.id;s.revision++;dialog.close();notify('帰投先を更新しました');render();return;}
    modal(`${d.name} / SERVICE`, `<div class="item-detail"><img class="hero" src="../../detail-scale-v22/dock/hero.png" alt="Dock実モデル"><div>${resource('給電資源',d.energy)}${resource('兵装資源',d.ammo,'weapon')}<dl class="kv"><dt>着艦</dt><dd>${d.occupied||'なし'}</dd><dt>帰還理由</dt><dd>飛行電力 / 兵装を独立判定</dd><dt>再出撃</dt><dd>復帰先が有効・安全帰投可能・任務遂行資源あり</dd><dt>枯渇時</dt><dd>安全量あり: 部分補給で復帰<br>不足: 代替施設へ / 救難要請</dd></dl>${d.energy?'':'<div class="notice-inline">RESOURCE EMPTY / 誤って「充電中」と表示しない</div>'}</div></div>`);return;
  }
  if(type==='cargo'){if(arg==='source')s.source=extra;else s.destination=extra;render();return;}
  if(type==='salvage') {
    if(arg==='select'){s.selected=new Set(s.units.filter(u=>u.role==='salvage'&&u.state!=='POWER_LOST').map(u=>u.id));notify(`${s.selected.size}機のSalvageを選択`);render();return;}
    const target=s.units.find(u=>u.id===extra),rescuer=s.units.find(u=>u.role==='salvage'&&u.state!=='POWER_LOST');if(!target||!rescuer)return;
    salvageTarget=target.id;s.selected=new Set([rescuer.id]);modal('救難回収計画',`<dl class="kv"><dt>対象</dt><dd>${target.id} / POWER LOST</dd><dt>担当</dt><dd>${rescuer.id}</dd><dt>搬送先</dt><dd>${rescuer.dock} / 出発Dock</dd><dt>座標</dt><dd>最終受信位置。到達後に存在を再確認</dd><dt>対象消失</dt><dd>回収を取り消し、元任務へ復帰</dd></dl>`,button('回収を送信','confirm:salvage','send','class="primary"'));return;
  }
  if(type==='front'){const front=s.fronts.find(f=>f.id===arg),units=s.units.filter(u=>u.state==='COMBAT'&&u.target===arg);if(!front)return;modal('戦線 / '+arg,`<dl class="kv"><dt>所属Wing</dt><dd>${[...new Set(units.map(u=>wingName(u.wing)))].join(' / ')||'未割当'}</dd><dt>交戦機</dt><dd>${units.map(u=>u.id).join(' / ')||'0機'}</dd><dt>敵</dt><dd>${front.target} / ${front.hostiles}体</dd><dt>戦線</dt><dd>${front.id} / ${front.priority}</dd></dl><div class="table-wrap"><table><thead><tr><th>機体</th><th>所属</th><th>復帰先</th></tr></thead><tbody>${units.map(u=>`<tr><td>${u.id}</td><td>${wingName(u.wing)}</td><td>${u.resume||u.mission}</td></tr>`).join('')}</tbody></table></div>`);return;}
  if(type==='settings'){s.settings=P.create(0).settings;render();}
}
document.addEventListener('click',e=> {
  if(suppressClick&&e.target.closest('[data-wing]')){suppressClick=false;e.preventDefault();return;}suppressClick=false;
  const a=e.target.closest('[data-action]');if(a){handleAction(a.dataset.action);return;}
  const u=e.target.closest('[data-unit]');if(u){P.select(s,u.dataset.unit,{toggle:e.ctrlKey||e.metaKey||e.target.type==='checkbox'||s.view==='wings',range:e.shiftKey});render();}
});
document.addEventListener('keydown',e=>{const u=e.target.closest('[data-unit]');if(u&&['Enter',' '].includes(e.key)){e.preventDefault();P.select(s,u.dataset.unit,{toggle:true,range:e.shiftKey});render();}});
document.addEventListener('input',e=> {
  if(e.target.id==='unit-search'){s.query=e.target.value;s.page=0;const pos=e.target.selectionStart;render();const field=document.getElementById('unit-search');field.focus();field.setSelectionRange(pos,pos);}
  if(e.target.dataset.point!==undefined){const value=Number(e.target.value);if(Number.isFinite(value)){routeDraft[Number(e.target.dataset.point)][e.target.dataset.axis]=value;drawMap(document.getElementById('route-canvas'),routeDraft);}}
});
document.addEventListener('change',e=> {
  const id=e.target.id;
  if(id==='scenario') {const name=e.target.value,view=s.view;s=P.create(name==='empty'?0:name==='eight'?8:name==='hundred'?100:name==='large'?256:24);s.scenario=name;s.view=view;if(name==='offline')s.connected=false;if(name==='denied')s.permission=false;if(name==='critical'){s.fronts.push({id:'F3',target:'襲撃部隊',hostiles:50,engaged:0,priority:'HIGH'},{id:'F4',target:'北面接触',hostiles:7,engaged:0,priority:'HIGH'});s.units.filter(u=>u.state==='COMBAT').forEach((u,i)=>u.target=`F${i%4+1}`);s.fronts.forEach(f=>f.engaged=s.units.filter(u=>u.state==='COMBAT'&&u.target===f.id).length);}lastWingEdit=null;render();}
  if(id==='role-filter'){s.role=e.target.value;s.page=0;render();}if(id==='unit-sort'){s.sort=e.target.value;s.page=0;render();}
  if(id==='item-filter'){itemFilter=e.target.value;render();}if(id==='density'){s.settings.density=e.target.value;render();}if(id==='text-size'){s.settings.text=Number(e.target.value);render();}
  if(e.target.dataset.setting){s.settings[e.target.dataset.setting]=e.target.checked;render();}
});
document.addEventListener('pointerdown',e=>{const u=e.target.closest('[data-drag-unit]');if(!u||e.button!==0)return;suppressClick=false;pointerDrag={x:e.clientX,y:e.clientY,id:e.pointerId,source:u,active:false,ids:s.selected.has(u.dataset.dragUnit)?[...s.selected]:[u.dataset.dragUnit]};u.setPointerCapture(e.pointerId);});
document.addEventListener('pointermove',e=>{if(!pointerDrag||pointerDrag.id!==e.pointerId)return;const d=pointerDrag;if(Math.hypot(e.clientX-d.x,e.clientY-d.y)>7)d.active=true;if(!d.active)return;d.source.classList.add('dragging');document.querySelectorAll('.drag-over').forEach(n=>n.classList.remove('drag-over'));document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-wing]')?.classList.add('drag-over');});
document.addEventListener('pointerup',e=>{
  const d=pointerDrag;if(!d||d.id!==e.pointerId)return;pointerDrag=null;
  d.source.releasePointerCapture(e.pointerId);d.source.classList.remove('dragging');
  document.querySelectorAll('.drag-over').forEach(n=>n.classList.remove('drag-over'));if(!d.active)return;
  suppressClick=true;setTimeout(()=>suppressClick=false,0);
  const node=document.elementFromPoint(e.clientX,e.clientY),wing=node?.closest('[data-wing]');if(!node?.closest('.page'))return;
  lastWingEdit={units:s.units.map(u=>({...u})),wings:s.wings.map(w=>({...w}))};
  const answer=P.moveToWing(s,d.ids,wing?.dataset.wing||null);notify(answer.ok?'所属を更新しました':answer.error,!answer.ok);render();
});
document.addEventListener('pointercancel',()=>{pointerDrag?.source.classList.remove('dragging');pointerDrag=null;document.querySelectorAll('.drag-over').forEach(n=>n.classList.remove('drag-over'));});
window.addEventListener('resize',()=>{if(s.view==='tactical')drawMap();});
render();
