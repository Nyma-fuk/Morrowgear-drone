/* The design simulator uses stable unit/Wing IDs, never roster positions. */
(function(root, factory) {
  const api = factory();
  if (typeof module !== 'undefined') module.exports = api;
  else root.MorrowHmi = api;
})(typeof window !== 'undefined' ? window : this, function() {
  const roles = ['field', 'scout', 'cargo', 'engineer', 'security', 'salvage'];
  const labels = {field:'FIELD',scout:'SCOUT',cargo:'CARGO',engineer:'ENGINEER',security:'SECURITY',salvage:'SALVAGE'};
  const states = {PATROL:'ルート巡回',WORK:'作業中',COMBAT:'交戦中',DOCKED:'着艦',RETURN:'補給帰還',POWER_LOST:'電力喪失',REJOIN:'合流中',STANDBY:'待機',FOLLOW:'追従',SALVAGE:'救難回収',MOVING:'地点移動'};
  function create(count=24) {
    const units=Array.from({length:count},(_,i)=>({
      id:`MG-${String(i+1).padStart(3,'0')}`, role:roles[i%6], wing:i<Math.ceil(count/8)*8?`W${Math.floor(i/8)+1}`:null,
      state:i===count-1?'POWER_LOST':i%8===7?'DOCKED':i%6===4?'COMBAT':i<8?'PATROL':'WORK',
      flight:i===count-1?0:Math.max(14,94-(i*7)%77),weapon:Math.max(12,100-(i*11)%87),hull:100-(i*3)%38,
      ammo:Math.max(0,480-i*7),missiles:6,heat:i%6===4?42:0,mission:i<8?'M1':i<16?'M2':'M3',
      dock:`D${i+1}`,fresh:i!==count-1,target:i%6===4?`F${Math.floor(i/6)%2+1}`:null,
      x:22+(i*19)%63,z:18+(i*13)%64,y:78+(i%3)*7
    }));
    const wings=Array.from({length:Math.ceil(count/8)},(_,i)=>({id:`W${i+1}`,name:['ALPHA','BRAVO','CHARLIE','DELTA'][i]||`WING ${i+1}`,mission:i===0?'M1':i===1?'M2':'M3'}));
    return {units,wings,selected:new Set(units.slice(0,8).map(u=>u.id)),anchor:null,query:'',role:'all',sort:'id',page:0,pageSize:8,
      view:'tactical',connected:true,permission:true,pending:null,revision:1,focus:units[0]?.id,pinned:null,zoom:1,pan:{x:0,y:0},
      route:[{x:24,z:28,y:84},{x:75,z:35,y:91},{x:59,z:76,y:84}],routeClosed:true,routeEditing:false,
      missions:[{id:'M1',name:'北稜ルート',type:'PATROL',status:'ACTIVE',progress:36},
        {id:'M2',name:'採掘セクター',type:'EXCAVATE',status:'ACTIVE',progress:62},
        {id:'M3',name:'南面警戒',type:'GUARD',status:'ACTIVE',progress:0}],
      docks:Array.from({length:count+2},(_,i)=>({id:`D${i+1}`,name:`DOCK ${String(i+1).padStart(2,'0')}`,energy:i===2?0:80-(i*5)%70,ammo:i===2?0:70,
        occupied:units.find(u=>u.dock===`D${i+1}`&&u.state==='DOCKED')?.id||null})),
      stations:[{id:'S1',name:'SOLAR NORTH',energy:64,slots:[units[2]?.id||null,units[14]?.id||null,null]}],
      containers:[{id:'C1',name:'CHEST / MINE',count:318,full:false},{id:'C2',name:'BARREL / BASE',count:82,full:false},{id:'C3',name:'CHEST / EAST',count:1728,full:true}],
      fronts:[{id:'F1',target:'ゾンビ',hostiles:12,engaged:units.filter(u=>u.state==='COMBAT'&&u.target==='F1').length,priority:'HIGH'},
        {id:'F2',target:'ウォーデン',hostiles:1,engaged:units.filter(u=>u.state==='COMBAT'&&u.target==='F2').length,priority:'CRITICAL'}],
      source:'C1',destination:'C2',notice:'',events:[],settings:{density:'adaptive',text:14,markers:true,subtitles:true,radio:false,motion:true,contrast:false},
      scenario:count===0?'empty':count===100?'hundred':'normal'};
  }
  function visible(s) {
    return s.units.filter(u=>(s.role==='all'||u.role===s.role)&&(!s.query||`${u.id} ${u.role} ${s.wings.find(w=>w.id===u.wing)?.name||''}`.toLowerCase().includes(s.query.toLowerCase())))
      .sort((a,b)=>s.sort==='flight'?a.flight-b.flight||a.id.localeCompare(b.id):s.sort==='role'?a.role.localeCompare(b.role)||a.id.localeCompare(b.id):a.id.localeCompare(b.id));
  }
  function select(s,id,{toggle=false,range=false}={}) {
    if(!s.units.some(u=>u.id===id)) return;
    if(range&&s.anchor) {
      const ids=visible(s).map(u=>u.id),a=ids.indexOf(s.anchor),b=ids.indexOf(id);
      if(a>=0&&b>=0) ids.slice(Math.min(a,b),Math.max(a,b)+1).forEach(x=>s.selected.add(x));
      else s.selected.add(id);
    } else if(toggle) {if(s.selected.has(id))s.selected.delete(id);else s.selected.add(id);}
    else s.selected=new Set([id]);
    s.anchor=id;s.focus=id;
  }
  function selectScope(s,scope) {
    const us=scope==='all'?s.units:scope==='filtered'?visible(s):visible(s).slice(s.page*s.pageSize,(s.page+1)*s.pageSize);
    s.selected=new Set(us.map(u=>u.id));
  }
  function eligible(s,action) {
    if(!s.connected)return '通信が復旧するまで指示できません';
    if(!s.permission)return 'この艦隊への指示権限がありません';
    if(s.pending)return '前の指示の応答待ちです';
    const us=s.units.filter(u=>s.selected.has(u.id));
    if(!us.length)return '対象が選択されていません';
    if(action==='store'&&us.some(u=>u.state!=='DOCKED'))return '格納には着艦が必要です';
    if(action==='role'&&us.some(u=>u.state!=='DOCKED'))return '役割変更には着艦が必要です';
    if(action==='cargo'&&!us.some(u=>u.role==='cargo'))return 'Cargoが必要です';
    if(action==='work'&&!us.some(u=>u.role==='engineer'))return 'Engineerが必要です';
    if(action==='guard'&&!us.some(u=>u.role==='security'))return 'Securityが必要です';
    if(action==='salvage'&&!us.some(u=>u.role==='salvage'&&u.state!=='POWER_LOST'))return '飛行可能なSalvageが必要です';
    if(!['salvage','store'].includes(action)&&us.some(u=>u.state==='POWER_LOST'))return '電力喪失機を除外してください';
    return '';
  }
  function issue(s,action,payload={}) {
    const error=eligible(s,action);
    if(error)return {ok:false,error};
    if(action==='route'&&!payload.route?.length)return {ok:false,error:'経由点がありません'};
    if(action==='route'&&(payload.route.length>8||payload.route.some(p=>!['x','y','z'].every(k=>Number.isFinite(p[k]))||p.y< -64||p.y>320)))return{ok:false,error:'経由点は8点以内、有限座標と有効高度が必要です'};
    if(action==='role'&&!roles.includes(payload.role))return{ok:false,error:'役割が無効です'};
    if(action==='guard'&&!s.fronts.some(f=>f.id===payload.target))return{ok:false,error:'迎撃目標がありません'};
    if(action==='salvage'&&!s.units.some(u=>u.id===payload.target&&u.state==='POWER_LOST'))return{ok:false,error:'回収対象がありません'};
    if(action==='cargo'&&(payload.source===payload.destination||!payload.source||!payload.destination))return {ok:false,error:'異なる搬出元と搬入先が必要です'};
    if(action==='cargo'&&![payload.source,payload.destination].every(id=>s.containers.some(c=>c.id===id)))return{ok:false,error:'コンテナが消失しました'};
    if(action==='cargo'&&s.containers.find(c=>c.id===payload.destination)?.full)return {ok:false,error:'搬入先が満杯です'};
    s.pending={id:`CMD-${s.revision}`,action,payload,units:[...s.selected],revision:s.revision};
    return {ok:true,command:s.pending};
  }
  function ack(s,accepted=true,reason='拒否されました') {
    const cmd=s.pending;if(!cmd)return {ok:false,error:'応答対象なし'};
    s.pending=null;
    if(!accepted||!s.connected||!s.permission||cmd.revision!==s.revision) {
      s.notice=!s.connected?'通信断。実行結果は未確定':cmd.revision!==s.revision?'対象が更新されました。再選択してください':reason;
      return {ok:false,error:s.notice};
    }
    const us=s.units.filter(u=>cmd.units.includes(u.id));
    if(us.length!==cmd.units.length){s.notice='対象が消失しました';return{ok:false,error:s.notice};}
    const mapping={follow:'FOLLOW',hold:'STANDBY',return:'RETURN',route:'PATROL',move:'MOVING',work:'WORK',cargo:'WORK',guard:'COMBAT',salvage:'SALVAGE'};
    if(cmd.action==='store') {s.units=s.units.filter(u=>!cmd.units.includes(u.id));s.selected.clear();}
    else for(const u of us) {
      if(cmd.action==='role')u.role=cmd.payload.role;
      else {
        u.state=mapping[cmd.action]||u.state;
        if(cmd.action==='guard'){u.resume=u.mission;u.target=cmd.payload.target;if(u.role!=='security')u.state='PATROL';}
        else if(cmd.action==='salvage'){u.resume=u.mission;u.target=cmd.payload.target;}
        else if(cmd.action!=='return')u.target=null;
        if(cmd.action==='return')u.resume=u.mission;
        else if(cmd.action!=='hold')u.mission=cmd.payload.mission||`M-${s.revision+3}`;
      }
    }
    if(cmd.action==='route')s.route=cmd.payload.route.map(p=>({...p}));
    if(['route','move','work','cargo','guard','salvage','follow'].includes(cmd.action)) {
      const mission=us[0]?.mission;
      for(const wing of s.wings) {
        const members=s.units.filter(u=>u.wing===wing.id);
        if(members.length&&members.every(u=>cmd.units.includes(u.id)))wing.mission=mission;
      }
      if(mission&&!s.missions.some(m=>m.id===mission))s.missions.push({id:mission,name:({route:'ルート巡回',move:'地点移動',work:'作業任務',cargo:'コンテナ輸送',guard:'迎撃',salvage:'救難回収',follow:'プレイヤー追従'})[cmd.action],type:cmd.payload.type||cmd.action.toUpperCase(),status:'ACTIVE',progress:0,payload:cmd.payload});
    }
    s.revision++;s.notice=`指示受理 / ${us.length}機`;
    s.events.unshift({id:`E${s.revision}`,text:s.notice,action:cmd.action});s.events=s.events.slice(0,20);
    return{ok:true};
  }
  function moveToWing(s,ids,wingId) {
    if(s.pending||!s.connected||!s.permission)return {ok:false,error:'編集できません。応答または通信を確認してください'};
    if(wingId&&!s.wings.some(w=>w.id===wingId))return{ok:false,error:'Wingがありません'};
    const members=s.units.filter(u=>ids.includes(u.id));
    if(members.length!==new Set(ids).size)return{ok:false,error:'機体が消失しました'};
    const occupied=s.units.filter(u=>u.wing===wingId&&!ids.includes(u.id)).length;
    if(wingId&&occupied+members.length>8)return{ok:false,error:'Wingの上限は8機です'};
    const wing=s.wings.find(w=>w.id===wingId);
    for(const u of members){u.wing=wingId; if(wing?.mission&&u.state!=='POWER_LOST'){u.resume=u.mission;u.mission=wing.mission;u.state='REJOIN';}}
    s.revision++;return{ok:true};
  }
  function newWing(s) {
    if(s.selected.size>8)return{ok:false,error:'1 Wingは8機までです'};
    if(!s.selected.size)return{ok:false,error:'機体を選択してください'};
    const id=`W${1+Math.max(0,...s.wings.map(w=>Number(w.id.slice(1))))}`;
    s.wings.push({id,name:`WING ${id.slice(1)}`,mission:null});
    const result=moveToWing(s,[...s.selected],id);
    if(!result.ok)s.wings=s.wings.filter(w=>w.id!==id);
    return {...result,id};
  }
  function removeUnit(s,id) {
    s.units=s.units.filter(u=>u.id!==id);s.selected.delete(id);
    if(s.focus===id)s.focus=null;if(s.pinned===id)s.pinned=null;s.revision++;
  }
  function hud(s,width=1920) {
    const lost=s.units.filter(u=>u.state==='POWER_LOST');
    return{unitCount:s.units.length,wingCount:s.wings.filter(w=>s.units.some(u=>u.wing===w.id)).length,
      fronts:s.fronts.map(f=>({...f,engaged:s.units.filter(u=>u.state==='COMBAT'&&u.target===f.id).length})).sort((a,b)=>Number(b.priority==='CRITICAL')-Number(a.priority==='CRITICAL')||a.id.localeCompare(b.id)).slice(0,width<700?1:3),
      extraFronts:Math.max(0,s.fronts.length-(width<700?1:3)),lost:lost.length,critical:lost.length>0,
      markers:s.settings.markers?(stateDensity(s,width)):0,
      focus:s.units.find(u=>u.id===(s.pinned||s.focus))||null};
  }
  function stateDensity(s,width){return s.settings.density==='compact'?2:width<700?3:s.settings.density==='full'?10:s.units.length>32?6:8;}
  return {roles,labels,states,create,visible,select,selectScope,eligible,issue,ack,moveToWing,newWing,removeUnit,hud};
});
