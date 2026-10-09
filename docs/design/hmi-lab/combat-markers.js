(function(root){
'use strict';
const anchors={'01':[42,42],'02':[62,64],'03':[81,49],'04':[108,58]};
const names={'01':['RAVEN 02','RAVEN 03','RAVEN 04','RAVEN 05','RAVEN 06','RAVEN 01'],'02':['FALCON 01','FALCON 02','FALCON 03','FALCON 04'],'03':['SECURITY 09','SECURITY 10'],'04':['RAVEN 07','RAVEN 08']};
// Anchors are review fixtures; real game markers require camera projection.
function build(frame,{selected=null,pinned=null,phase=0,narrow=false}={}){
 const markers=[],links=[];let detail=null;
 for(const f of frame.active){const [ax,ay]=anchors[f.id];const offscreen=ax>100;const target={id:`target-${f.id}`,front:f.id,kind:offscreen?'edge':'enemy',x:Math.min(95,ax),y:ay,label:`交戦${f.id} / ${f.enemy}`,sub:offscreen?'画面外 / 南西 410m':f.place,selected:selected===f.id};markers.push(target);
 const expanded=selected===f.id||names[f.id].includes(pinned);
 if(!f.units)continue;
 if(!expanded){markers.push({id:`group-${f.id}`,front:f.id,kind:'group',x:Math.min(91,ax-7),y:ay-10,label:`${f.team} / ${f.units}機`,sub:f.action});continue;}
 const limit=narrow?2:3,assigned=names[f.id].slice(0,f.units);let shown=assigned.slice(0,limit);if(pinned&&assigned.includes(pinned)&&!shown.includes(pinned))shown=[...shown.slice(0,-1),pinned];
 shown.forEach((name,i)=>{const x=Math.min(90,ax-9+i*6+Math.sin(phase+i)*1.5),y=ay-15-i*3+Math.cos(phase+i)*1.2;markers.push({id:name,front:f.id,kind:'drone',x,y,label:name,sub:f.action,pinned:name===pinned});links.push({from:name,to:target.id,front:f.id});if(name===pinned)detail={name,front:f.id,target:f.enemy,action:f.action};});
 if(assigned.length>shown.length)markers.push({id:`more-${f.id}`,front:f.id,kind:'group',x:Math.min(91,ax+7),y:ay-7,label:`ほか${assigned.length-shown.length}機`,sub:'同一目標に参加'});
 }
 if(frame.index>=7)markers.push({id:'lost-04',front:'04',kind:'stale',x:95,y:58,label:'交戦04 / 最終確認',sub:'追跡喪失・撃破未確認'});
 if(frame.index>=10)markers.push({id:'withdraw-03',front:'03',kind:'stale',x:81,y:49,label:'交戦03 / 残存脅威',sub:'離脱・現在位置は未確認'});
 if(pinned&&!detail){const front=Object.keys(names).find(id=>names[id].includes(pinned));if(front){detail={name:pinned,front,target:null,action:front==='04'?'捜索へ移行':front==='03'?'補給へ帰還':'元任務へ復帰'};markers.push({id:pinned,front,kind:'drone',x:32,y:64,label:pinned,sub:detail.action,pinned:true});}}
 if(frame.index===0&&!pinned)markers.push({id:'patrol',front:null,kind:'group',x:42,y:46,label:'RAVEN隊 / 哨戒',sub:'攻撃割当なし'});
 return {markers,links,detail};
}
const api={build};if(typeof module!=='undefined')module.exports=api;else root.CombatMarkers=api;
})(typeof window==='undefined'?{}:window);
