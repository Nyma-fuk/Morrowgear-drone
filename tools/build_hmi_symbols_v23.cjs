const fs=require('node:fs'),path=require('node:path');
const runtime=path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const lucide=require(path.join(runtime,'lucide/dist/umd/lucide.js'));
const sharp=require(path.join(runtime,'sharp'));
const out=path.resolve(__dirname,'../docs/design/equipment-hmi-v23/hmi/symbols');
const names=['Map','Layers','ListChecks','BatteryCharging','Cable','ScanEye','Package','SlidersHorizontal',
  'Navigation','Pause','Play','CornerDownLeft','Route','Pickaxe','Crosshair','Archive','Scan','Send','Check','X',
  'Plus','Minus','ChevronsLeft','ChevronLeft','ChevronRight','ChevronsRight','ArrowUp','ArrowDown',
  'Search','SearchX','Square','CheckCheck','LogIn','LogOut','Undo2','Redo2','LocateFixed','MapPin','Pin','PinOff',
  'RadioTower','Wifi','WifiOff','ShieldAlert','TriangleAlert','CircleCheck','CircleX','Clock','LoaderCircle',
  'Battery','BatteryLow','BatteryWarning','Zap','Sun','Cloud','Moon','Thermometer','Flame','Wrench',
  'PackageMinus','PackagePlus','Boxes','Container','TreePine','Sprout','Mountain','Radar','Eye','EyeOff',
  'ArrowUpRight','ArrowDownLeft','RefreshCw','RotateCcw','PanelRightOpen','Focus','Volume2','VolumeX','Captions',
  'Circle','Diamond','Lock','Unlock','Shield','Target','Gauge','Home','Download','CircleHelp'];
async function main(){
  fs.mkdirSync(out,{recursive:true});const assets=[];
  for(const name of names){
    const node=lucide.icons[name];if(!node)throw Error('Missing lucide symbol '+name);
    const body=node.map(([tag,attrs])=>`<${tag} ${Object.entries(attrs).filter(([k])=>k!=='key').map(([k,v])=>`${k}="${v}"`).join(' ')}/>`).join('');
    const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#eef4f2" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
    const id=name.replace(/([a-z0-9])([A-Z])/g,'$1-$2').toLowerCase();fs.writeFileSync(path.join(out,id+'.svg'),svg);
    for(const size of[24,32,48,64])await sharp(Buffer.from(svg)).resize(size,size).png().toFile(path.join(out,`${id}-${size}.png`));
    assets.push({id,source:'lucide',sizes:[24,32,48,64]});
  }
  const roles=['field','scout','cargo','engineer','security','salvage','dock'];
  for(const role of roles){
    const base=path.resolve(out,'../../../detail-scale-v22/icons');
    fs.copyFileSync(path.join(base,role+'-role.svg'),path.join(out,role+'.svg'));
    for(const size of[24,32,48,64])fs.copyFileSync(path.join(base,`${role}-role-${size}.png`),path.join(out,`${role}-${size}.png`));
    assets.push({id:role,source:'V22 role glyph',sizes:[24,32,48,64]});
  }
  fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify({count:assets.length,structuralSymmetry:'physical objects only; directional controls intentionally directional',assets},null,2));
  console.log(assets.length+' symbols / '+assets.length*4+' PNG');
}
main().catch(e=>{console.error(e);process.exitCode=1;});
