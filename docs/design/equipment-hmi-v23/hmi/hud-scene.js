import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

export function createHudScene(host,state,policy) {
  const renderer=new THREE.WebGLRenderer({antialias:true,alpha:false,preserveDrawingBuffer:true});
  renderer.setPixelRatio(Math.min(devicePixelRatio||1,2));renderer.setClearColor(0x9eb9ca);
  renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.18;
  renderer.domElement.setAttribute('aria-label','実メッシュによる3D HMD検証シーン');host.prepend(renderer.domElement);
  const scene=new THREE.Scene();scene.fog=new THREE.Fog(0x9eb9ca,85,220);
  const camera=new THREE.PerspectiveCamera(64,1,.1,500);camera.position.set(0,5,25);
  scene.add(new THREE.HemisphereLight(0xdceeff,0x4e6450,2.5));
  const sun=new THREE.DirectionalLight(0xfff1dd,4.2);sun.position.set(-25,45,18);scene.add(sun);
  const ground=new THREE.PlaneGeometry(400,400,1,1);ground.rotateX(-Math.PI/2);
  const texture=new THREE.TextureLoader().load('terrain.png');texture.colorSpace=THREE.SRGBColorSpace;texture.wrapS=texture.wrapT=THREE.RepeatWrapping;texture.repeat.set(6,6);texture.magFilter=THREE.NearestFilter;
  const land=new THREE.Mesh(ground,new THREE.MeshStandardMaterial({map:texture,roughness:1}));land.position.y=-.1;scene.add(land);
  const obstacles=[];
  for(const [x,z,w,h] of [[-24,-24,12,10],[26,-55,18,17],[-43,-82,19,23],[55,-28,12,6]]) {
    const block=new THREE.Mesh(new THREE.BoxGeometry(w,h,w*.8),new THREE.MeshStandardMaterial({color:0x7d8884,roughness:.95}));
    block.position.set(x,h/2,z);scene.add(block);obstacles.push(block);
  }
  const loader=new GLTFLoader(),aircraft=[],mixers=[],labels=document.getElementById('hud-markers'),ray=new THREE.Raycaster();
  let disposed=false,request=0,heading=0,pitch=.08,time=0,previous=performance.now(),frames=0;
  const geometryResources=new Set(),materialResources=new Set();
  const targets=state.units.filter(u=>!['POWER_LOST','DOCKED'].includes(u.state)).sort((a,b)=>Number(b.id===(state.pinned||state.focus))-Number(a.id===(state.pinned||state.focus))||Number(b.state==='COMBAT')-Number(a.state==='COMBAT')||a.id.localeCompare(b.id)).slice(0,8);
  const count=targets.length;host.dataset.models='0';
  const getResources=object=>object.traverse(o=>{if(o.geometry)geometryResources.add(o.geometry);if(o.material)(Array.isArray(o.material)?o.material:[o.material]).forEach(m=>materialResources.add(m));});
  for(const role of new Set(targets.map(u=>u.role)))loader.load(`../../detail-scale-v22/${role}/airframe.glb`,gltf=> {
    if(disposed){gltf.scene.traverse(o=>{o.geometry?.dispose();o.material?.dispose?.();});return;}
    getResources(gltf.scene);
    for(let i=0;i<count;i++) {
      if(targets[i].role!==role)continue;
      const model=gltf.scene.clone(true);model.scale.setScalar(1.25);scene.add(model);
      const u=targets[i];aircraft.push({model,u,phase:i*2*Math.PI/count,label:null});
      if(gltf.animations.length){const mixer=new THREE.AnimationMixer(model);mixer.clipAction(gltf.animations[0]).play();mixers.push(mixer);}
    }
    aircraft.sort((a,b)=>targets.indexOf(a.u)-targets.indexOf(b.u));
    host.dataset.models=String(aircraft.length);
  },undefined,()=>{host.dataset.assetError='airframe';});
  loader.load('../../detail-scale-v22/dock/dock.glb',gltf=>{
    if(disposed){gltf.scene.traverse(o=>{o.geometry?.dispose();o.material?.dispose?.();});return;}
    getResources(gltf.scene);
    for(let i=-1;i<=1;i++){const model=gltf.scene.clone(true);model.position.set(i*7,0,8);scene.add(model);}
  },undefined,()=>{host.dataset.assetError='dock';});
  const projected=new THREE.Vector3(),point=new THREE.Vector3(),direction=new THREE.Vector3();
  function resize(){const {width,height}=host.getBoundingClientRect();renderer.setSize(width,height,false);camera.aspect=width/height;camera.updateProjectionMatrix();}
  const observer=new ResizeObserver(resize);observer.observe(host);resize();
  const intersect=(a,b)=>a.x<b.x+b.w&&a.x+a.w>b.x&&a.y<b.y+b.h&&a.y+a.h>b.y;
  function projectLabels(w,h) {
    const budget=policy.hud(state,w).markers,occupied=[{x:w*.43,y:h*.40,w:w*.14,h:h*.20}],bounds=host.getBoundingClientRect();
    for(const panel of host.querySelectorAll('.hud-top,.hud-ops,.hud-focus,.hud-caption,.hud-lost,.hud-camera,.hud-hotbar-safe,.hud-legend')){const r=panel.getBoundingClientRect();if(r.width&&r.height)occupied.push({x:r.x-bounds.x-6,y:r.y-bounds.y-6,w:r.width+12,h:r.height+12});}
    labels.replaceChildren();let shown=0;
    for(const a of aircraft) {
      if(shown>=budget)break;
      a.model.getWorldPosition(point);point.y+=.7;
      direction.copy(point).sub(camera.position);const distance=direction.length();direction.normalize();
      ray.set(camera.position,direction);const blocked=ray.intersectObjects(obstacles,false).some(hit=>hit.distance<distance);
      projected.copy(point).project(camera);
      const front=direction.dot(camera.getWorldDirection(new THREE.Vector3()))>0;
      let x=(projected.x*.5+.5)*w,y=(-projected.y*.5+.5)*h;
      const edge=!front||Math.abs(projected.x)>1||Math.abs(projected.y)>1;
      if(edge){x=Math.max(22,Math.min(w-22,front?x:w-x));y=Math.max(50,Math.min(h-85,front?y:h-y));}
      const label=document.createElement('div');label.className='hud-label'+(a.u.state==='POWER_LOST'?' critical':'');label.style.left=x+'px';label.style.top=y+'px';
      label.dataset.unit=a.u.id;label.dataset.depth=distance.toFixed(2);label.dataset.occluded=String(blocked);label.dataset.edge=String(edge);
      const glyph=document.createElement('span');glyph.textContent=edge?'›':blocked?'◇':'⌜';glyph.style.background='transparent';glyph.style.fontSize='24px';label.append(glyph);
      const labelWidth=a.u.state==='COMBAT'?184:155;
      const candidates=[{x:x+14,y:y-12,w:labelWidth,h:28},{x:x-labelWidth-17,y:y-12,w:labelWidth,h:28},{x:x-labelWidth/2,y:y+19,w:labelWidth,h:28}];
      const box=candidates.find(r=>r.x>4&&r.x+r.w<w-4&&r.y>42&&r.y+r.h<h-58&&!occupied.some(o=>intersect(r,o)));
      if(box){const text=document.createElement('span');text.textContent=`${a.u.id}${a.u.state==='COMBAT'?' → '+a.u.target:''} / ${blocked?'遮蔽':Math.round(distance)+'m'}`;text.style.position='absolute';text.style.left=(box.x-x)+'px';text.style.top=(box.y-y)+'px';label.append(text);occupied.push(box);}
      labels.append(label);shown++;
    }
    host.dataset.visibleMarkers=String(shown);
  }
  function tick(now) {
    if(disposed)return;
    const dt=Math.min(.05,(now-previous)/1000);previous=now;
    if(state.settings.motion&&!matchMedia('(prefers-reduced-motion: reduce)').matches)time+=dt;
    const forward=new THREE.Vector3(Math.sin(heading)*Math.cos(pitch),Math.sin(pitch),-Math.cos(heading)*Math.cos(pitch));camera.lookAt(camera.position.clone().add(forward));
    for(const a of aircraft) {
      const angle=a.phase+time*.18;
      a.model.position.set(Math.sin(angle)*17,9+Math.cos(angle*1.5)*1.8,Math.cos(angle)*9-19);
      a.model.rotation.y=-angle;
    }
    mixers.forEach(m=>m.update(state.settings.motion?dt:0));
    renderer.render(scene,camera);projectLabels(host.clientWidth,host.clientHeight);
    host.dataset.frames=String(++frames);host.dataset.camera=heading.toFixed(2)+','+pitch.toFixed(2);
    request=requestAnimationFrame(tick);
  }
  request=requestAnimationFrame(tick);
  return {look(action){if(action==='reset'){heading=0;pitch=.08;}else if(action==='left')heading-=.24;else if(action==='right')heading+=.24;else pitch=Math.max(-.8,Math.min(.8,pitch+(action==='up'?.16:-.16)));},
    dispose(){disposed=true;cancelAnimationFrame(request);observer.disconnect();geometryResources.forEach(g=>g.dispose());materialResources.forEach(m=>m.dispose());scene.traverse(o=>{if(o.isMesh&&!geometryResources.has(o.geometry)){o.geometry.dispose();o.material.dispose();}});texture.dispose();renderer.dispose();renderer.domElement.remove();}};
}
