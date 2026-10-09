(function(root){
'use strict';
const scenes={
 normal:{title:'通常航行',reason:'変化のない正常情報は集約し、視界を優先する。近傍機は点と向きだけを表示する。',summary:'稼働 24 / 帰還 2',ops:['北稜哨戒 / 2 Wing','採掘 / 進行中'],markers:3},
 focus:{title:'注視した機体',reason:'短時間の注視で1機を展開。武器を合算せず、帰還・再出撃を妨げている資源を特定できるようにする。',summary:'稼働 24 / 帰還 2',ops:['北稜哨戒 / 2 Wing'],markers:3,detail:true},
 combat:{title:'複数の場所で同時戦闘',reason:'全機のラベルや射線を常時表示せず、戦線ごとに敵と味方の数を集約。選択した戦線だけ関係を展開する。',summary:'戦線 4 / 交戦 18機',ops:['北門 / ゾンビ 12 / 8機','東門 / ウォーデン 1 / 6機','西尾根 / 襲撃者 6 / 4機','南岸 / ゾンビ 4 / 増援待ち'],markers:5,detail:true},
 recovery:{title:'回収と任務復帰',reason:'電力喪失は緊急通知を1件だけ展開。回収担当・搬送先・復帰までの段階はコントローラーで追う。',summary:'稼働 22 / 回収対象 2',ops:['MG-020 / 回収機が接近中','MG-023 / 回収機待ち'],markers:2,alert:'MG-023 電力喪失 / 1.8 km / 回収機待ち'},
 stale:{title:'受信が途切れた状態',reason:'未知を0や故障と決めつけない。位置は最終確認として扱い、実位置へ追従しているように動かさない。',summary:'受信 20 / 未受信 4',ops:['北稜哨戒 / 最終確認 12秒前'],markers:1,detail:true,stale:true,alert:'4機の情報が更新されていません'},
 dense:{title:'100機の運用',reason:'機体数に比例して表示を増やさない。正常群は要約し、固定対象と緊急情報に枠を確保する。',summary:'登録 100 / 稼働 86 / 要対応 3',ops:['北門 / 交戦 24機','採掘場 / 作業 16機','北稜 / 哨戒 32機','東岸 / 回収 3機','基地 / 補給 11機'],markers:5,detail:true}
};
function layout(scene,width){const s=scenes[scene]||scenes.normal;const narrow=width<900;const limit=narrow?1:3;return {...s,ops:s.ops.slice(0,limit),hidden:Math.max(0,s.ops.length-limit),markers:Math.min(s.markers,narrow?2:5),fontSize:14,compact:narrow};}
function retainFocus(previous,candidates){return candidates.includes(previous)?previous:(candidates[0]||null);}
const api={scenes,layout,retainFocus};if(typeof module!=='undefined')module.exports=api;else root.VisorPolicy=api;
})(typeof window==='undefined'?{}:window);
