(function(root){
'use strict';
const fronts={
 '01':{id:'01',place:'北東 180m',enemy:'ウォーデン ×1',units:6,team:'RAVEN隊',action:'レーザー照射中'},
 '02':{id:'02',place:'西 95m',enemy:'ゾンビ群 ×12',units:4,team:'FALCON隊',action:'機関砲掃射中'},
 '03':{id:'03',place:'南 320m',enemy:'襲撃者 ×5',units:0,team:'未割当',action:'増援待ち'},
 '04':{id:'04',place:'南西 410m',enemy:'ゾンビ群 ×3',units:2,team:'RAVEN 07・08',action:'迎撃位置へ移動'}
};
const steps=[
 {title:'通常哨戒',active:[],events:[]},
 {title:'最初の交戦',active:['01'],events:[['start','01','交戦開始','RAVEN隊 → ウォーデン']]},
 {title:'別地点でも交戦',active:['01','02'],events:[['start','02','交戦開始','FALCON隊 → ゾンビ群']]},
 {title:'増援が不足',active:['01','02','03'],events:[['warning','03','増援待ち','襲撃者を発見 / 攻撃可能機を確認中']]},
 {title:'4地点で同時対応',active:['01','02','03','04'],events:[['start','04','交戦開始','RAVEN 07・08 → ゾンビ群']]},
 {title:'増援が参加',active:['01','02','03','04'],reinforced:true,events:[['join','03','増援参加','SECURITY 09・10 / 2機']]},
 {title:'連続撃破を集約',active:['01','02','03','04'],reinforced:true,zombies:7,events:[['kill','02','目標撃破','ゾンビ ×5 / 残り7体']]},
 {title:'目標を見失う',active:['01','02','03'],reinforced:true,zombies:7,events:[['lost','04','追跡喪失','最終確認 南西 410m / 撃破未確認']]},
 {title:'1つの戦闘が終了',active:['02','03'],reinforced:true,zombies:7,events:[['kill','01','目標撃破','ウォーデン'],['end','01','戦闘終了','残存脅威なし / RAVEN隊は哨戒へ復帰']]},
 {title:'さらに脅威を排除',active:['03'],reinforced:true,events:[['kill','02','目標撃破','ゾンビ ×7'],['end','02','戦闘終了','残存脅威なし / FALCON隊は元任務へ']]},
 {title:'戦闘から離脱',active:[],events:[['withdraw','03','離脱','補給を優先 / 敵の残存あり']]},
 {title:'未解決事項を保持',active:[],events:[]},
 {title:'通知は消え、記録は残る',active:[],events:[]}
];
function frame(index,{selected=null,width=1920}={}){
 const n=Math.max(0,Math.min(steps.length-1,index)),s=steps[n];
 const active=s.active.map(id=>({...fronts[id],...(id==='03'&&s.reinforced?{units:2,team:'SECURITY 09・10',action:'迎撃位置へ移動'}:{}),...(id==='02'&&s.zombies?{enemy:`ゾンビ群 ×${s.zombies}`}:{})}));
 const history=steps.slice(0,n+1).flatMap((x,i)=>x.events.map((e,j)=>({id:`${i}-${j}`,step:i,type:e[0],front:e[1],label:e[2],text:e[3]})));
 const limit=width<900?1:3;
 return {title:s.title,index:n,active,visible:active.slice(0,limit),hidden:Math.max(0,active.length-limit),selected:selected?active.find(f=>f.id===selected)||{id:selected,closed:true}:null,logs:history.filter(e=>n-e.step<2).slice(-3),history,unresolved:[...(n>=7?['交戦04 / 追跡喪失 / 撃破未確認']:[]),...(n>=10?['交戦03 / 離脱 / 敵の残存あり']:[])]};
}
const api={steps,frame};if(typeof module!=='undefined')module.exports=api;else root.CombatScenario=api;
})(typeof window==='undefined'?{}:window);
