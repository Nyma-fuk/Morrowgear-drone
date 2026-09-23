/* Approval-only local sample. No network calls or game state writes. */
'use strict';
const P = window.OperationsPolicy;
const assets = '../../../src/main/resources/assets/morrowgear_drone/textures/';
const app = document.querySelector('#app');
const dialog = document.querySelector('#dialog');
const state = {
  nav: 'operations', view: '地図', pane: 'map', surface: 'operations', units: P.roster(100),
  selected: new Set([1,2,3,4,5,6,7,8]), scope: 'wing', filter: '', sort: 'id', offset: 0,
  anchor: 1, mode: null, auto: false, link: true, permitted: true, historyFilter: '',
  enabled: true, range: '48', completion: false, focus: null, pinned: false, zoom: 1,
  notice: '', storeConfirm: null, picked: null, pickedOrigin: null, slotInfo: 'Dock 27枠 / 所持品 36枠', voice: true, voiceVolume: 70, voiceMode: 'IMPORTANT',
  inventory: Array.from({length:63}, () => null),
  events: [
    { sequence:1, time:'14:32:18', kind:'warning', priority:0, sender:'北東戦線', text:'2機 電力喪失 / 回収待ち', detail:'W03 / X 128 Z -64 / 救難信号を保持', ack:false },
    { sequence:2, time:'14:32:09', kind:'warning', priority:1, sender:'Dock 03', text:'弾薬不足 / 補給待ち', detail:'機関砲弾薬 0 / 出撃保留', ack:false },
    { sequence:3, time:'14:31:54', kind:'radio', priority:2, sender:'W01', text:'補給帰還へ移行', detail:'MG-003 / 飛行電力 14% / 所属・任務維持', ack:false },
    { sequence:4, time:'14:31:38', kind:'completion', priority:3, sender:'W02', text:'鉱脈処理 完了', detail:'回収物 24 / 通常完了通知 OFF', ack:false }
  ]
};
state.inventory[0] = {id:'field_drone_unit',name:'フィールド機体',n:1};
state.inventory[1] = {id:'reinforced_battery_pack',name:'強化バッテリー',n:1};
state.inventory[2] = {id:'security_module',name:'警備モジュール',n:1};
state.inventory[3] = {id:'autocannon_module',name:'機関砲モジュール',n:1};
state.inventory[4] = {id:'power_cell',name:'パワーセル',n:8};
state.inventory[5] = {id:'morrow_alloy',name:'Morrow Alloy',n:12};
state.inventory[8] = {id:'high_density_battery_pack',name:'高密度バッテリー',n:1};
state.inventory[9] = {id:'field_drone_unit',name:'回収機体',n:1};
state.inventory[18] = {id:'power_cell',name:'パワーセル',n:32};
state.inventory[19] = {id:'morrow_alloy',name:'Morrow Alloy',n:24};
state.inventory[27] = {id:'power_cell',name:'パワーセル',n:16};
state.inventory[28] = {id:'scout_module',name:'偵察モジュール',n:1};
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = name => `<img class="icon" alt="" src="${assets}gui/symbols/${name}.png">`;
const role = (name, cls='role') => `<img class="${cls}" alt="${P.roleNames[name]}" src="${assets}gui/roles/${name}.png">`;
const item = (id, name='') => `<img alt="${escape(name)}" src="${assets}item/${id}.png">`;
const action = (id,label,symbol,extra='') => `<button data-action="${id}" ${extra}>${symbol ? icon(symbol) : ''}${label}</button>`;
const tool = (id,label,symbol,extra='') => `<button class="icon-button" data-action="${id}" aria-label="${label}" data-tip="${label}" ${extra}>${icon(symbol)}</button>`;
function disabled(reason) { return reason ? `disabled title="${escape(reason)}"` : ''; }
function visibleUnits() {
  return state.units.filter(u => !state.filter || u.role === state.filter).slice().sort((a,b) =>
    (state.sort === 'power' ? a.power-b.power : state.sort === 'role' ? a.role.localeCompare(b.role) : 0) || a.id-b.id);
}
function selectionSummary() { return P.summary(state.units,state.selected,new Set(visibleUnits().map(u=>u.id))); }
function record(text,kind='command',detail='ローカル提案の操作記録') {
  state.events.push({sequence:state.events.length+1,time:new Date().toLocaleTimeString('ja-JP'),kind,priority:kind==='warning'?1:3,sender:'指揮端末',text,detail,ack:false});
  state.notice = text;
}
function cancelMode() { state.mode=null; state.storeConfirm=null; }
function selectionChanged() { cancelMode(); }
function restorePicked() {
  if (state.picked && state.pickedOrigin!==null) {
    const previous=state.inventory[state.pickedOrigin];
    if (previous && previous.id===state.picked.id) previous.n+=state.picked.n;
    else state.inventory[state.pickedOrigin]=state.picked;
  }
  state.picked=null; state.pickedOrigin=null;
}
function render() {
  const summary=selectionSummary();
  if (state.surface==='dock') { app.innerHTML=dock(); return; }
  if (state.surface==='hud') { app.innerHTML=hud(); return; }
  const nav=P.nav.find(n=>n.id===state.nav);
  app.innerHTML=`<header class="app-header"><div class="brand">MORROWGEAR<span>C2</span></div><nav class="primary-nav" aria-label="主ナビ">${P.nav.map(n=>`<button role="tab" data-nav="${n.id}" aria-selected="${n.id===state.nav}">${icon(n.icon)}${n.label}</button>`).join('')}</nav><span class="link-state ${state.link?'good':'bad'}">${state.link?'LINK ONLINE':'LINK LOST'}</span></header>
    <section class="selection" aria-label="選択サマリー"><strong>${summary.count}機 選択中</strong><span class="detail">Wing 完全 ${summary.full} / 部分 ${summary.partial} / 未所属 ${summary.ungrouped} / フィルター外 ${summary.hidden}</span>${!state.link?'<span class="bad">通信断</span>':!state.permitted?'<span class="warn">権限なし</span>':''}${tool('selection','選択の詳細','list-checks')}${tool('clear','選択解除','x')}</section>
    <div class="secondary-row"><nav class="subnav" aria-label="下位ビュー">${nav.views.map(v=>`<button role="tab" data-view="${v}" aria-selected="${v===state.view}">${v}</button>`).join('')}</nav>${state.nav==='operations'&&state.view==='地図'?`<select id="pane-picker" aria-label="表示領域">${[['roster','一覧'],['map','地図'],['commands','命令']].map(([id,label])=>`<option value="${id}" ${state.pane===id?'selected':''}>${label}</option>`).join('')}</select>`:''}</div>
    ${state.nav==='operations' && state.view==='地図' ? operations() : content()}`;
}
function roster() {
  const units=visibleUnits();
  const paged=P.page(units,state.offset,8); state.offset=paged.start;
  return `<aside class="roster" aria-label="機体一覧"><div class="section-head"><h2>部隊</h2><span class="muted">${units.length} / ${state.units.length}機</span></div>
    <div class="filters"><select id="role-filter" aria-label="役割フィルター"><option value="">全ロール</option>${P.roles.map(r=>`<option value="${r}" ${state.filter===r?'selected':''}>${P.roleNames[r]}</option>`).join('')}</select><select id="sort" aria-label="並び順"><option value="id" ${state.sort==='id'?'selected':''}>ID順</option><option value="role" ${state.sort==='role'?'selected':''}>役割順</option><option value="power" ${state.sort==='power'?'selected':''}>電力順</option></select></div>
    <div class="scope" role="group" aria-label="選択範囲">${[['unit','機体'],['wing','Wing'],['all','全機']].map(([id,label])=>`<button data-scope="${id}" aria-pressed="${state.scope===id}">${label}</button>`).join('')}</div>
    <div class="rows">${paged.items.map(u=>`<button class="unit-row" data-unit="${u.id}" aria-pressed="${state.selected.has(u.id)}">${role(u.role)}<span class="unit-text"><span>${u.label} <small>${u.wing||'未所属'}</small></span><small>${P.roleNames[u.role]} / ${u.state}</small></span><span class="power ${u.power<20?'warn':''}">${u.power}%</span></button>`).join('')||'<p class="empty">対象機なし</p>'}</div>
    <div class="pager">${tool('first','先頭ページ','chevrons-left',disabled(!state.offset?'先頭':''))}${tool('previous','前ページ','chevron-left',disabled(!state.offset?'先頭':''))}<output>${units.length?paged.start+1:0}-${Math.min(paged.start+8,units.length)} / ${units.length}</output>${tool('next','次ページ','chevron-right',disabled(paged.start+8>=units.length?'末尾':''))}${tool('last','末尾ページ','chevrons-right',disabled(paged.start+8>=units.length?'末尾':''))}</div></aside>`;
}
function commands() {
  const a=P.availability(state.units,state.selected,state.link,state.permitted);
  const selected=state.units.filter(u=>state.selected.has(u.id));
  const first=selected[0];
  return `<aside class="commands" aria-label="対象への命令"><div class="section-head"><h2>対象への命令</h2><span class="accent">${selected.length}機</span></div>
    ${first?`<div class="unit-detail">${role(first.role)}<span>${selected.length===1?first.label:first.wing+' ほか'}<small>${selected.length===1?first.state:'飛行電力 最低 '+Math.min(...selected.map(u=>u.power))+'%'}</small></span></div>`:''}
    <div class="command-section"><h3>移動・待機</h3><div class="command-grid">${[['follow','追従','navigation'],['standby','待機','pause'],['return','自分へ帰還','corner-down-left'],['dock','Dock帰投','dock'],['orbit','周回','rotate-ccw'],['move','地点移動','map-pin']].map(([id,label,sym])=>action(id,label,sym,disabled(a.common))).join('')}</div>${a.common?`<p class="reason">${a.common}</p>`:''}</div>
    <div class="command-section"><h3>作戦</h3><label><input type="checkbox" id="auto" ${state.auto?'checked':''}>自動編成</label><div class="command-grid">${[['ore','鉱脈処理','pickaxe',a.work],['excavate','掘削','mountain',a.work],['forestry','樹体処理','tree-pine',a.work],['guard','警戒','shield',a.guard],['route','巡回路','route',a.common],['track','追跡','target',a.common]].map(([id,label,sym,reason])=>action(id,label,sym,disabled(state.auto&&['ore','excavate','forestry'].includes(id)?(!state.link?'通信断':!state.permitted?'操作権限なし':''):reason))).join('')}</div>${a.guard?`<p class="reason">警戒: ${a.guard}</p>`:''}</div>
    <div class="command-section"><h3>輸送</h3><div class="command-grid">${action('cargo-source','搬出元','package-minus',disabled(a.cargo))}${action('cargo-target','搬入先','package-plus',disabled(a.cargo))}</div>${a.cargo?`<p class="reason">${a.cargo}</p>`:''}</div>
    <div class="command-section">${action('store','機体を格納','package',`class="danger" ${disabled(a.common)}`)}</div></aside>`;
}
const modeLabels={move:'地点移動',ore:'鉱脈処理',excavate:'掘削',forestry:'樹体処理',guard:'警戒',route:'巡回路',track:'追跡'};
function operations() {
  const groups=[...new Set(state.units.map(u=>u.wing))].slice(0,8);
  const m=state.mode;
  return `<main class="workspace" data-pane="${state.pane}">${roster()}<section class="center" aria-label="作戦地図"><div class="map-area" id="map-area" role="region" aria-label="地図の対象指定">
      <img class="map-image" alt="V23既存地形見本" src="../equipment-hmi-v23/hmi/terrain.png" style="transform:scale(${state.zoom})">
      <div class="map-heading"><h2>作戦地図 <span class="muted">/ Overworld</span></h2><div class="map-tools">${tool('zoom-out','縮小','minus')}${tool('zoom-in','拡大','plus')}${tool('map-reset','現在地','locate-fixed')}</div></div>
      ${groups.map((w,i)=>`<button class="map-marker" data-wing="${w}" style="left:${20+(i%4)*20}%;top:${32+Math.floor(i/4)*30}%">${role(state.units.find(u=>u.wing===w).role)}${w}</button>`).join('')}
      <button class="map-marker enemy" data-target="北東接触" style="left:80%;top:76%">${icon('target')}接触 3</button>
      <button class="map-marker base-marker" data-action="open-dock" style="left:18%;top:80%">${item('dock_item')}Dock 03</button>
    </div><div class="map-mode ${m?'targeting':''}" aria-live="polite"><strong>${m?modeLabels[m.kind]+' / 対象指定':state.notice||'閲覧中'}<span class="mode-detail">${m?(state.auto?'自動編成':m.recipients.length+'機')+' / '+(m.points.length?m.points.map(p=>p.label).join(' → '):'未指定'):'戦線 2 / 交戦 12機 / 要対応 3件'}</span></strong>${m?tool('cancel','対象指定を取消','x')+action('confirm-target','確定','check',disabled(!m.points.length?'地点未指定':'')):tool('enter-move','地点移動を指定','map-pin',disabled(P.availability(state.units,state.selected,state.link,state.permitted).common))}</div></section>${commands()}</main>`;
}
function dataRow(title,detail,stateText,reason,button='',img='') {
  return `<div class="data-row"><span class="title">${img}<span>${title}<small>${detail}</small></span></span><span>${stateText}</span><span class="muted">${reason}</span>${button||'<span></span>'}</div>`;
}
function content() {
  let body='';
  if(state.nav==='operations') {
    body=state.view==='任務'?[['W01','警戒 / 北東','進行中','8機 / 1機補給帰還'],['W02','鉱脈処理 / 西','72%','半径32 / 発見24'],['W03','巡回路 / 東','2 / 4地点','要回収2機']].map(r=>dataRow(...r,action('show-map','地図','map'))).join(''):
      [['北東戦線','W01・W03','交戦 12機','高脅威 / 回収待ち2機'],['西側戦線','W02','護衛 4機','任務継続']].map(r=>dataRow(...r,action('show-map','地図','map'))).join('');
  } else if(state.nav==='fleet'&&state.view==='機体') {
    return `<main class="workspace" data-pane="roster">${roster()}<section class="table-view"><div class="section-head"><h2>機体詳細</h2></div>${state.units.filter(u=>state.selected.has(u.id)).map(u=>dataRow(u.label,u.wing,P.roleNames[u.role]+' / '+u.state,'飛行 '+u.power+'%',action('equipment','装備','wrench'),role(u.role))).join('')||'<p class="empty">機体未選択</p>'}</section></main>`;
  } else if(state.nav==='fleet'&&state.view==='Wing') {
    body=`<div class="toolbar">${action('new-wing','Wingを作成','plus')}${action('join-wing','編入','log-in',disabled(state.selected.size!==1?'1機を選択':''))}${action('leave-wing','所属解除','log-out',disabled(!state.selected.size?'対象未選択':''))}</div>`+[...new Set(state.units.map(u=>u.wing))].map(w=>dataRow(w||'未所属','恒久所属',state.units.filter(u=>u.wing===w).length+' / 8機','任務群とは独立',`<button data-wing="${w}">${icon('check')}選択</button>`)).join('');
  } else if(state.nav==='fleet'&&state.view==='装備') {
    const a=P.availability(state.units,state.selected,state.link,state.permitted);
    body=`<div class="toolbar"><span class="${a.role?'warn':'good'}">${a.role||'装備変更可能'}</span></div><div class="equipment-list">${['scout_module','cargo_module','engineer_module','security_module','salvage_module','autocannon_module','laser_module','missile_module'].map(id=>`<div class="equipment">${item(id)}<span>${itemName(id)}<button data-module="${id}" ${disabled(a.role)}>装着</button></span></div>`).join('')}</div><div class="toolbar">${action('field-module','汎用に戻す','field',disabled(a.role))}${action('capacity','搭載容量を拡張','plus',disabled(a.role||state.selected.size!==1?'着艦中の1機が必要':''))}</div>`;
  } else if(state.nav==='base'&&state.view==='Dock') {
    body=[['Dock 01','西基地','準備完了','電力100% / 空き'],['Dock 02','西基地','充電中','電力74% / MG-015'],['Dock 03','東基地','弾薬待ち','電力62% / 弾薬不足']].map(r=>dataRow(...r,action('open-dock','開く','dock'),item('dock_item'))).join('');
  } else if(state.nav==='base'&&state.view==='補給網') {
    body=`<div class="toolbar"><span class="good">供給元 2</span><span class="muted">予約 8 / 配送中 16 / 待機 1</span></div>`+
      dataRow('西基地倉庫','X -24 / Y 64 / Z 16','セル 64 / 予約8','利用可能56',action('supply-edit','補給設定','sliders-horizontal'),item('power_cell'))+
      dataRow('Dock 03','優先度: 高','要求 32 / 配送16','弾薬の在庫なし',action('supply-request','補給要求','send'),item('dock_item'))+
      dataRow('浮遊サービス拠点','充電子機 2 / 3','蓄電 82%','待機1機',action('show-map','地図','map'),item('solar_service_station'));
  } else if(state.nav==='base'&&state.view==='回収') {
    body=dataRow('MG-017','W03 / Overworld','電力喪失','X 128 / Y 64 / Z -64',action('recovery','回収詳細','wrench'),role('salvage'))+
      dataRow('MG-018','W03 / Overworld','搬入先待ち','Dock 03 / 出力枠を確認',action('open-dock','Dock','dock'),role('salvage'));
  } else if(state.nav==='base'&&state.view==='母艦') {
    body=`<div class="toolbar"><span class="warn">接続なし / API未接続</span></div>`+
      dataRow('母艦','所属・任務を変更しない','状態未取得','船ID / 次元 / 最終更新: 不明',action('carrier-detail','詳細','list-checks'))+
      `<div class="toolbar">${action('carrier-launch','艦載機展開','send', 'disabled title="母艦API未接続"')}${action('carrier-recover','母艦へ帰還','dock','disabled title="母艦API未接続"')}${action('carrier-stop','緊急停止','square','class="danger" disabled title="母艦API未接続"')}</div>`;
  } else if(state.nav==='base'&&state.view==='機器') {
    body=`<div class="equipment-list">${['controller','tactical_visor','recovery_tool','field_drone_unit','dock_item','solar_service_station','scout_module','cargo_module','engineer_module','security_module','salvage_module','power_cell','standard_battery_pack','reinforced_battery_pack','high_density_battery_pack','raw_morrow_composite','morrow_alloy','lightweight_frame','basic_control_board','flight_actuator','autocannon_module','laser_module','missile_module'].map(id=>`<div class="equipment">${item(id)}<span>${itemName(id)}<small>${id}</small></span></div>`).join('')}</div>`;
  } else if(state.nav==='history') {
    const kind={'警告':'warning','通信':'radio','命令':'command'}[state.view];
    body=`<div class="toolbar"><input id="history-filter" type="search" placeholder="対象・内容を検索" aria-label="履歴検索" value="${escape(state.historyFilter)}"><span class="muted">最新 ${state.events.length}件</span></div>`+
      state.events.slice().reverse().filter(e=>(!kind||e.kind===kind)&&[e.text,e.sender,e.detail].some(t=>t.includes(state.historyFilter))).map(e=>dataRow(e.sender,e.time,e.text,e.ack?'確認済み':e.detail,`<button data-event="${e.sequence}">${icon('list-checks')}詳細</button>`)).join('');
  } else if(state.nav==='settings') {
    body=state.view==='バイザー'?`<div class="setting"><label for="hud-enabled">バイザー表示</label><input id="hud-enabled" type="checkbox" ${state.enabled?'checked':''}></div><div class="setting"><label for="range">近接マーカー距離</label><select id="range">${['0','24','48','96'].map(v=>`<option value="${v}" ${state.range===v?'selected':''}>${v==='0'?'非表示':v+' m'}</option>`).join('')}</select></div><div class="setting"><label for="completion">通常の完了通知</label><input id="completion" type="checkbox" ${state.completion?'checked':''}></div><div class="toolbar">${action('open-hud','HUDを確認','eye')}</div>`:
      state.view==='通信'?`<div class="setting"><label for="voice-enabled">通信音声</label><input type="checkbox" id="voice-enabled" ${state.voice?'checked':''}></div><div class="setting"><label for="voice-mode">読み上げ対象</label><select id="voice-mode" ${state.voice?'':'disabled'}><option value="IMPORTANT" ${state.voiceMode==='IMPORTANT'?'selected':''}>重要のみ</option><option value="STANDARD" ${state.voiceMode==='STANDARD'?'selected':''}>標準</option></select></div><div class="setting"><span>字幕・履歴</span><span class="good">常時利用</span></div><div class="setting"><label for="radio-volume">音量 ${state.voiceVolume}%</label><input type="range" id="radio-volume" min="0" max="100" value="${state.voiceVolume}" aria-label="通信音量"></div>`:
      `<div class="setting"><span>GUI倍率</span><span class="muted">Minecraft設定に追従</span></div><div class="setting"><span>文字・アイコン</span><span>等倍 / 省略時は詳細へ</span></div>`;
  }
  return `<main class="table-view"><div class="section-head"><h1>${state.view}</h1>${state.notice?`<span class="accent">${escape(state.notice)}</span>`:''}</div>${body}</main>`;
}
function itemName(id) {
  return {controller:'コントローラー',tactical_visor:'バイザー',recovery_tool:'回収ツール',field_drone_unit:'機体',dock_item:'Dock',solar_service_station:'浮遊サービス拠点',scout_module:'偵察モジュール',cargo_module:'輸送モジュール',engineer_module:'作業モジュール',security_module:'警備モジュール',salvage_module:'回収モジュール',power_cell:'パワーセル',standard_battery_pack:'標準電池',reinforced_battery_pack:'強化電池',high_density_battery_pack:'高密度電池',raw_morrow_composite:'複合材',morrow_alloy:'Morrow Alloy',lightweight_frame:'軽量フレーム',basic_control_board:'制御基板',flight_actuator:'アクチュエーター',autocannon_module:'機関砲',laser_module:'レーザー',missile_module:'ミサイル'}[id]||id;
}
function slots(start,end,labels=false) {
  return Array.from({length:end-start},(_,i)=>{
    const index=start+i, it=state.inventory[index], label=P.slotLabels[index]||`所持品 ${index-26}`;
    return `<div class="slot-wrap">${labels?`<div class="slot-label">${index===8?'蓄電':label}</div>`:''}<button class="slot ${!it?'empty-slot':''} ${index===7||index>=9&&index<=17?'output':''}" data-slot="${index}" aria-label="${label}: ${it?it.name+' '+it.n:'空'}" data-tip="${label} / ${it?it.name+' ×'+it.n:'空'}" ${!state.link||!state.permitted?'disabled':''}>${it?item(it.id)+`<span class="quantity">${it.n}</span>`:''}</button></div>`;
  }).join('');
}
function dock() {
  return `<header class="app-header"><div class="brand">MORROWGEAR<span>DOCK 03</span></div><div class="section-head" style="flex:1"><h1>Dock 03</h1>${tool('back','作戦端末へ戻る','x')}</div></header><section class="selection"><strong>MG-008 / 着艦</strong><span class="detail">登録Dock 03 / W01</span><span class="warn">弾薬待ち</span></section>
    <main class="dock-body"><section aria-label="Dockインベントリ"><div class="dock-state">${item('dock_item')}<span>東基地 / X 48 Y 64 Z 32<small>蓄電 6,200 / 10,000</small><span class="warn">出撃保留: 機関砲弾薬なし</span></span></div>
      <div class="inventory"><h3>装備・燃料・弾薬 <span class="muted">0-8</span></h3><div class="slot-grid equipment-slots">${slots(0,9,true)}</div></div>
      <div class="inventory"><h3>回収出力 <span class="muted">9-17</span></h3><div class="slot-grid">${slots(9,18)}</div></div>
      <div class="inventory"><h3>共通補給 <span class="muted">18-26</span></h3><div class="slot-grid">${slots(18,27)}</div></div>
      <div class="inventory"><h3>所持品</h3><div class="slot-grid">${slots(27,54)}</div><div class="slot-grid" style="margin-top:8px">${slots(54,63)}</div></div>
      <p class="slot-status" aria-live="polite">${escape(state.picked?'保持中: '+state.picked.name+' ×'+state.picked.n:state.slotInfo)}</p></section>
    <aside class="dock-info"><h3>整備状態</h3><dl class="data-list"><dt>飛行電力</dt><dd class="good">100%</dd><dt>兵装電力</dt><dd class="good">100%</dd><dt>弾薬</dt><dd class="warn">0 / 240</dd><dt>推進 / センサー / 作業</dt><dd>100 / 100 / 100</dd><dt>出力枠</dt><dd>1 / 10</dd><dt>要求中</dt><dd>弾薬 240</dd></dl><h3>停止理由</h3><p class="dock-message">${!state.link?'通信断 / 最終値を表示':!state.permitted?'操作権限なし':'弾薬不足 / 補給元に在庫なし'}</p><div class="toolbar">${action('supply-from-dock','補給網','package')}${action('dock-launch','出撃','send','disabled title="弾薬不足"')}</div><h3>補給品</h3><dl class="data-list"><dt>機関砲マガジン</dt><dd>120発</dd><dt>レーザーセル</dt><dd>1,000電力</dd><dt>小型ミサイルパック</dt><dd>5発</dd></dl></aside></main>`;
}
function hud() {
  const event=P.topMessage(state.events,state.completion);
  const focus=state.units.find(u=>u.id===state.focus);
  return `<main class="hud" aria-label="HUD配置提案"><img class="hud-ground" alt="V23既存地形 / HUD配置確認用" src="../equipment-hmi-v23/hmi/terrain.png">${state.enabled?`
    <div class="hud-strip"><strong>戦線 ${state.units.length?2:0}</strong><span>交戦 ${state.units.length?12:0}</span><span class="warn">要対応 ${state.units.length?3:0}</span><span class="muted">MORROWGEAR / ${state.units.length}機</span>${tool('back','作戦端末へ戻る','x')}</div>
    <span class="hud-center" aria-hidden="true">+</span>${state.units.length?`<button class="hud-unit" data-action="focus-unit" aria-label="MG-001に注目">${role('security')}W01</button>`:''}
    ${focus?`<section class="hud-focus" aria-label="注目機詳細"><h3>${focus.label} ${tool('pin',state.pinned?'固定解除':'注目機を固定',state.pinned?'pin-off':'pin')}</h3><div class="data-list"><dt>飛行 / 兵装</dt><dd>${focus.power}% / 82%</dd><dt>所属 / 状態</dt><dd>${focus.wing} / ${focus.state}</dd><dt class="extra">リンク / 系統</dt><dd class="extra">正常 / 100%</dd></div></section>`:''}
    ${event&&state.units.length?`<section class="hud-message" aria-label="優先通知"><span>${event.sender} / ${event.text}<small>${event.detail}</small></span><button class="icon-button" data-event="${event.sequence}" aria-label="通知の履歴を開く" data-tip="履歴">${icon('clock')}</button></section>`:''}`:`<div class="hud-strip"><span>HUD OFF</span>${action('back','作戦端末','corner-down-left')}</div>`}</main>`;
}
function openDialog(title,body,buttons='') {
  dialog.innerHTML=`<div class="dialog-head"><h2 id="dialog-title">${title}</h2>${tool('close-dialog','閉じる','x')}</div><div class="dialog-body">${body}</div><div class="dialog-actions">${buttons||action('close-dialog','閉じる','x')}</div>`;
  dialog.showModal();
}
function selectUnit(id,event) {
  const unit=state.units.find(u=>u.id===id); if(!unit)return;
  if(state.scope==='all') state.selected=new Set(state.units.map(u=>u.id));
  else if(event.shiftKey&&state.scope==='unit') {
    const visible=visibleUnits(), from=visible.findIndex(u=>u.id===state.anchor), to=visible.findIndex(u=>u.id===id);
    if(!event.ctrlKey)state.selected.clear();
    visible.slice(Math.max(0,Math.min(from,to)),Math.max(from,to)+1).forEach(u=>state.selected.add(u.id));
  } else {
    const group=state.scope==='wing'&&unit.wing?state.units.filter(u=>u.wing===unit.wing):[unit];
    const remove=event.ctrlKey&&group.every(u=>state.selected.has(u.id));
    if(!event.ctrlKey)state.selected.clear();
    group.forEach(u=>remove?state.selected.delete(u.id):state.selected.add(u.id));
    state.anchor=id;
  }
  selectionChanged(); render();
}
function inventoryClick(index,split=false) {
  if(!state.link||!state.permitted)return;
  const target=state.inventory[index];
  if(!state.picked) {
    if(!target){state.slotInfo=(P.slotLabels[index]||'所持品')+': 空';render();return;}
    const n=split?Math.ceil(target.n/2):target.n;
    state.picked={...target,n}; state.pickedOrigin=index; target.n-=n;
    if(target.n===0)state.inventory[index]=null;
  } else {
    const id=state.picked.id;
    let reason='';
    if(index===7||index>=9&&index<18)reason='出力枠には挿入できません';
    else if(index===0&&id!=='field_drone_unit')reason='機体専用';
    else if((index===1||index===8)&&!id.includes('battery_pack'))reason='バッテリー専用';
    else if(index===2&&!['scout_module','cargo_module','engineer_module','security_module','salvage_module'].includes(id))reason='役割モジュール専用';
    else if(index===3&&!['autocannon_module','laser_module','missile_module'].includes(id))reason='兵装モジュール専用';
    else if(index===4&&id!=='power_cell'&&!id.includes('battery_pack'))reason='燃料が不適合';
    else if(index===5&&id!=='morrow_alloy')reason='整備素材が不適合';
    else if(index===6)reason='弾薬が不適合';
    else if(index>=18&&index<27&&!['power_cell','morrow_alloy','standard_battery_pack','reinforced_battery_pack','high_density_battery_pack'].includes(id))reason='共通補給品ではありません';
    if(reason) { state.slotInfo=reason; openDialog('投入不可',`<p>${reason}</p><p>保持中: ${state.picked.name} ×${state.picked.n}</p>`);return; }
    if(target&&target.id!==id) { state.slotInfo='空き枠または同種の枠を選択';openDialog('投入不可','<p>別のアイテムが入っています。</p>');return; }
    const cap=index<=3||index===8?1:64;
    const amount=Math.min(split?1:state.picked.n,cap-(target?.n||0));
    if(amount<=0){openDialog('投入不可','<p>枠が満杯です。</p>');return;}
    state.inventory[index]={...state.picked,n:(target?.n||0)+amount}; state.picked.n-=amount;
    if(!state.picked.n) {state.picked=null;state.pickedOrigin=null;}
    state.slotInfo=(P.slotLabels[index]||'所持品')+'へ '+amount+'個 移動';
  }
  render();
}
function handleAction(id) {
  if(id==='close-dialog'){dialog.close();return;}
  if(id==='selection') {const s=selectionSummary();openDialog('選択の詳細',`<p>${s.count}機 / 完全Wing ${s.full} / 部分Wing ${s.partial}</p><p>未所属 ${s.ungrouped} / フィルター外 ${s.hidden}</p><p>${state.units.filter(u=>state.selected.has(u.id)).map(u=>u.label).join(', ')||'なし'}</p>`);return;}
  if(id==='clear'){state.selected.clear();selectionChanged();}
  else if(['first','previous','next','last'].includes(id))state.offset=id==='first'?0:id==='previous'?Math.max(0,state.offset-8):id==='next'?state.offset+8:Math.max(0,visibleUnits().length-8);
  else if(id==='cancel'){cancelMode();state.notice='対象指定を取消';}
  else if(id==='back'){restorePicked();state.surface='operations';document.querySelector('#surface').value='operations';}
  else if(id==='open-dock'){cancelMode();state.surface='dock';document.querySelector('#surface').value='dock';}
  else if(id==='open-hud'){cancelMode();state.surface='hud';document.querySelector('#surface').value='hud';}
  else if(id==='supply-from-dock'){restorePicked();state.surface='operations';document.querySelector('#surface').value='operations';state.nav='base';state.view='補給網';}
  else if(id==='show-map'){state.nav='operations';state.view='地図';state.pane='map';}
  else if(id==='equipment'){state.nav='fleet';state.view='装備';}
  else if(id==='zoom-in')state.zoom=Math.min(2,state.zoom+.25);
  else if(id==='zoom-out')state.zoom=Math.max(1,state.zoom-.25);
  else if(id==='map-reset')state.zoom=1;
  else if(id==='focus-unit'){state.focus=1;}
  else if(id==='pin')state.pinned=!state.pinned;
  else if(modeLabels[id]||id==='enter-move') {
    const kind=id==='enter-move'?'move':id;
    state.mode=P.arm(kind,state.selected);state.nav='operations';state.view='地図';state.pane='map';state.notice='';
  } else if(id==='confirm-target') {
    const m=state.mode;
    if(!m||!m.points.length||!P.sameRecipients(m,state.selected))return;
    record(modeLabels[m.kind]+' / '+m.points.map(p=>p.label).join(' → '),'command',`${m.recipients.length}機 / サンプル上で受付`); cancelMode();
  } else if(id==='store') {
    state.storeConfirm={ids:[...state.selected].sort((a,b)=>a-b),expires:Date.now()+10000};
    openDialog('機体を格納',`<p>対象 ${state.selected.size}機</p><p>${state.units.filter(u=>state.selected.has(u.id)).map(u=>u.label).join(', ')}</p>`,action('close-dialog','取消','x')+action('confirm-store','格納を確定','package','class="danger"'));return;
  } else if(id==='confirm-store') {
    const c=state.storeConfirm;
    if(!c||Date.now()>=c.expires||c.ids.join(',')!==[...state.selected].sort((a,b)=>a-b).join(',')) {dialog.close();openDialog('確認期限切れ','<p>対象を確認して、もう一度格納を選択してください。</p>');return;}
    record(c.ids.length+'機 格納要求');state.storeConfirm=null;dialog.close();
  } else if(['follow','standby','return','dock','orbit'].includes(id)) {
    cancelMode();record(({follow:'追従',standby:'待機',return:'自分へ帰還',dock:'Dock帰投',orbit:'周回'})[id]+' / '+state.selected.size+'機');
  } else if(id==='new-wing'||id==='join-wing') {
    openDialog(id==='new-wing'?'Wingを作成':'Wingへ編入',`<p>対象 ${state.selected.size}機 / 最大8機</p><select id="wing-destination" aria-label="編入先">${[...new Set(state.units.map(u=>u.wing)), 'W14'].map(w=>`<option>${w}</option>`).join('')}</select>`,action('close-dialog','取消','x')+action('confirm-wing','確定','check'));return;
  } else if(id==='confirm-wing') {
    const wing=document.querySelector('#wing-destination').value;
    const newcomers=state.units.filter(u=>state.selected.has(u.id)&&u.wing!==wing);
    if(state.units.filter(u=>u.wing===wing).length+newcomers.length>8){dialog.close();openDialog('編入不可','<p>Wing上限8機</p>');return;}
    newcomers.forEach(u=>P.joinWing(state.units,u.id,wing));record(wing+'に編入');dialog.close();selectionChanged();
  } else if(id==='leave-wing'){state.units.filter(u=>state.selected.has(u.id)).forEach(u=>u.wing='');record('所属解除 / 任務状態を維持');selectionChanged();}
  else if(id==='supply-edit'||id==='supply-request') {
    openDialog(id==='supply-edit'?'補給元設定':'Dock 03 補給要求',`<p>西基地倉庫 / 利用可能56 / 予約8</p><div class="setting"><label for="request-count">要求数</label><input id="request-count" type="number" min="1" max="56" value="32" style="width:80px"></div><div class="setting"><label for="priority">優先度</label><select id="priority"><option>高</option><option>通常</option><option>低</option></select></div>`,action('close-dialog','取消','x')+action('confirm-supply','要求を確認','check',disabled(!state.link?'通信断':!state.permitted?'権限なし':'')));return;
  } else if(id==='confirm-supply') {
    const count=Number(document.querySelector('#request-count').value);
    if(!Number.isInteger(count)||count<1||count>56){document.querySelector('#request-count').reportValidity();return;}
    record('補給要求 '+count+' / '+document.querySelector('#priority').value,'command','サンプル / 実際のSupply APIは未接続');dialog.close();
  } else if(id==='carrier-detail') {openDialog('母艦 / 接続なし','<p>航路・搭載機・サービスベイ・電力・内部収納・乗艦権限: 未取得</p><p>母艦API未接続。対象範囲の確定前に掘削・照射は開始しません。</p>');return;}
  else if(id==='recovery') {openDialog('MG-017 / 回収待ち','<p>電力喪失 / W03 / Overworld</p><p>X 128 Y 64 Z -64</p><p>回収機未割当 / 受入Dockの出力枠確認が必要</p>',action('close-dialog','閉じる','x')+action('recovery-map','地図へ','map'));return;}
  else if(id==='recovery-map'){dialog.close();state.nav='operations';state.view='地図';state.pane='map';}
  else if(id==='cargo-source'||id==='cargo-target') {openDialog(id==='cargo-source'?'搬出元':'搬入先','<p>西基地倉庫 / X -24 Y 64 Z 16</p><p>対象: 選択中の輸送機</p>',action('close-dialog','取消','x')+action('confirm-cargo','割当','check'));return;}
  else if(id==='confirm-cargo'){record('輸送コンテナ割当');dialog.close();}
  else if(id==='capacity'){openDialog('搭載容量を拡張','<p>Tier 0 → 1</p><p>合金8 / ダイヤ1 / RSブロック2</p>',action('close-dialog','取消','x')+action('confirm-capacity','拡張','plus'));return;}
  else if(id==='confirm-capacity'){record('搭載容量 拡張要求');dialog.close();}
  else if(id==='field-module')record('汎用ロールへ変更要求');
  render();
}
document.addEventListener('click',event=>{
  const b=event.target.closest('button');
  if(b) {
    if(b.disabled)return;
    if(b.dataset.nav){cancelMode();state.nav=b.dataset.nav;state.view=P.nav.find(n=>n.id===state.nav).views[0];render();}
    else if(b.dataset.view){cancelMode();state.view=b.dataset.view;render();}
    else if(b.dataset.pane){state.pane=b.dataset.pane;render();}
    else if(b.dataset.scope){state.scope=b.dataset.scope;if(state.scope==='all')state.selected=new Set(state.units.map(u=>u.id));else if(state.scope==='unit')state.selected=new Set([...state.selected].slice(0,1));selectionChanged();render();}
    else if(b.dataset.unit)selectUnit(Number(b.dataset.unit),event);
    else if(b.dataset.wing!==undefined){state.scope='wing';state.selected=new Set(state.units.filter(u=>u.wing===b.dataset.wing).map(u=>u.id));selectionChanged();render();}
    else if(b.dataset.target&&state.mode?.kind==='track'){state.mode.points=[{label:b.dataset.target}];render();}
    else if(b.dataset.event){const e=state.events.find(e=>e.sequence===Number(b.dataset.event));openDialog(e.sender+' / '+e.time,`<p>${escape(e.text)}</p><p>${escape(e.detail)}</p>`);}
    else if(b.dataset.slot!==undefined)inventoryClick(Number(b.dataset.slot));
    else if(b.dataset.module){record(itemName(b.dataset.module)+' 装着要求');render();}
    else if(b.dataset.action)handleAction(b.dataset.action);
    return;
  }
  const map=event.target.closest('#map-area');
  if(map&&state.mode&&state.mode.kind!=='track') {
    const box=map.getBoundingClientRect();
    const x=Math.round((event.clientX-box.left-box.width/2)/state.zoom), z=Math.round((event.clientY-box.top-box.height/2)/state.zoom);
    const point={label:`X ${x} Z ${z}`};
    if(state.mode.kind==='route') { if(state.mode.points.length<8)state.mode.points.push(point); }
    else state.mode.points=[point];render();
  }
});
document.addEventListener('change',event=>{
  const e=event.target;
  if(e.id==='surface'){restorePicked();cancelMode();state.surface=e.value;}
  else if(e.id==='scenario'){restorePicked();cancelMode();const count=['offline','denied'].includes(e.value)?100:Number(e.value);state.units=P.roster(count);state.selected=new Set(state.units.slice(0,8).map(u=>u.id));state.link=e.value!=='offline';state.permitted=e.value!=='denied';state.offset=0;state.focus=null;}
  else if(e.id==='role-filter'){state.filter=e.value;state.offset=0;}
  else if(e.id==='sort'){state.sort=e.value;state.offset=0;}
  else if(e.id==='pane-picker')state.pane=e.value;
  else if(e.id==='auto'){state.auto=e.checked;cancelMode();}
  else if(e.id==='hud-enabled')state.enabled=e.checked;
  else if(e.id==='completion')state.completion=e.checked;
  else if(e.id==='range')state.range=e.value;
  else if(e.id==='voice-enabled')state.voice=e.checked;
  else if(e.id==='voice-mode')state.voiceMode=e.value;
  else if(e.id==='radio-volume')state.voiceVolume=Number(e.value);
  else return;
  render();
});
document.addEventListener('input',event=>{if(event.target.id==='history-filter'){const position=event.target.selectionStart;state.historyFilter=event.target.value;render();const input=document.querySelector('#history-filter');input.focus();input.setSelectionRange(position,position);}});
document.addEventListener('contextmenu',event=>{const slot=event.target.closest('[data-slot]');if(slot){event.preventDefault();inventoryClick(Number(slot.dataset.slot),true);}});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!dialog.open){restorePicked();cancelMode();state.notice='取消';render();}});
document.addEventListener('pointerover',event=>{const tip=event.target.closest('[data-tip]');const el=document.querySelector('#tooltip');if(!tip){el.hidden=true;return;}el.textContent=tip.dataset.tip;el.hidden=false;const r=tip.getBoundingClientRect();el.style.left=Math.max(4,Math.min(innerWidth-el.offsetWidth-4,r.left))+'px';el.style.top=Math.max(4,Math.min(innerHeight-el.offsetHeight-4,r.bottom+4))+'px';});
document.addEventListener('pointerout',()=>{document.querySelector('#tooltip').hidden=true;});
render();
