(function(root){
'use strict';
const steps=[
 {name:'哨戒',event:'北稜のルートを巡回中',stage:'normal',target:null,action:'ルート航行',flight:82,laser:94},
 {name:'敵を発見',event:'SCOUT 01 が北門の敵を通報',stage:'contact',target:'ウォーデン A',action:'割当済み',flight:81,laser:94},
 {name:'迎撃へ移行',event:'北門へ3機が迎撃 / 東門でも敵を検知',stage:'approach',target:'ウォーデン A',action:'射撃位置へ移動',flight:79,laser:94},
 {name:'交戦',event:'RAVEN 02 が照射開始',stage:'fire',target:'ウォーデン A',action:'レーザー照射中',flight:76,laser:48},
 {name:'補給交代',event:'RAVEN 02 が補給へ / RAVEN 03 が攻撃を継続',stage:'relief',target:null,action:'Dockへ帰還',flight:58,laser:8},
 {name:'目標を撃破',event:'北門の脅威を排除 / 東門では交戦継続',stage:'resolved',target:null,action:'Dockで補給中',flight:70,laser:42},
 {name:'通常表示へ',event:'脅威解消 / 北稜哨戒を再開',stage:'normal',target:null,action:'ルート航行',flight:92,laser:100}
];
function frame(index,{pinned=false,focus=false,width=1920}={}){const s=steps[Math.max(0,Math.min(steps.length-1,index))];const combat=['contact','approach','fire','relief'].includes(s.stage);return {...s,index,detail:pinned||focus||s.stage==='relief',pinned,combat,rows:width<900?1:3,relations:s.target?[{drone:'RAVEN 02',target:s.target,action:s.action}]:[],fronts:combat?['北門 / ウォーデン A / 3機',...(['approach','fire','relief'].includes(s.stage)?['東門 / ゾンビ群 / 2機']:[])]:s.stage==='resolved'?['東門 / ゾンビ群 / 2機']:[],fontSize:14};}
const api={steps,frame};if(typeof module!=='undefined')module.exports=api;else root.AdaptiveVisor=api;
})(typeof window==='undefined'?{}:window);
