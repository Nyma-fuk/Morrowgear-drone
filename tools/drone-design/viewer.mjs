import * as THREE from "./node_modules/three/build/three.module.js";
import { MATERIALS, createModel } from "./model.mjs";

const role = new URLSearchParams(location.search).get("role") || "field";
const model = createModel(role);
document.querySelector("#title").textContent = `MORROWGEAR // FAMILY A // ${role.toUpperCase()}`;

const views = [
  { id: "hero", label: "3/4 TOP", eye: [28, 22, -36], up: [0, 1, 0], hero: true, scale: 12.5 },
  { id: "top", label: "TOP", eye: [0, 80, 0], up: [0, 0, -1], scale: 14.5 },
  { id: "bottom", label: "BOTTOM", eye: [0, -80, 0], up: [0, 0, -1], scale: 14.5 },
  { id: "front", label: "FRONT (-Z)", eye: [0, 0, -80], up: [0, 1, 0], scale: 14.5 },
  { id: "rear", label: "REAR (+Z)", eye: [0, 0, 80], up: [0, 1, 0], scale: 14.5 },
  { id: "left", label: "LEFT (-X)", eye: [-80, 0, 0], up: [0, 1, 0], scale: 11.5 },
  { id: "right", label: "RIGHT (+X)", eye: [80, 0, 0], up: [0, 1, 0], scale: 11.5 }
];

const materialCache = new Map();

function proceduralTexture(name, baseColor) {
  const canvas = document.createElement("canvas");
  canvas.width = 128;
  canvas.height = 128;
  const context = canvas.getContext("2d");
  context.fillStyle = baseColor;
  context.fillRect(0, 0, 128, 128);
  let seed = [...name].reduce((value, char) => value * 31 + char.charCodeAt(0), 17) >>> 0;
  const random = () => {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    return seed / 0xffffffff;
  };
  for (let i = 0; i < 1100; i++) {
    const value = random() > 0.5 ? 255 : 0;
    context.fillStyle = `rgba(${value},${value},${value},${0.015 + random() * 0.035})`;
    context.fillRect(Math.floor(random() * 128), Math.floor(random() * 128), 1, 1);
  }
  context.strokeStyle = "rgba(8,12,14,0.32)";
  context.lineWidth = 1;
  for (const offset of [16, 48, 80, 112]) {
    context.beginPath(); context.moveTo(offset, 0); context.lineTo(offset, 128); context.stroke();
    context.beginPath(); context.moveTo(0, offset); context.lineTo(128, offset); context.stroke();
  }
  context.strokeStyle = "rgba(220,236,240,0.12)";
  for (let i = 0; i < 18; i++) {
    const x = random() * 120;
    const y = random() * 120;
    context.beginPath(); context.moveTo(x, y); context.lineTo(x + 2 + random() * 8, y + random() * 2); context.stroke();
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
  texture.repeat.set(1.6, 1.6);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

function materialFor(name) {
  if (materialCache.has(name)) return materialCache.get(name);
  const definition = MATERIALS[name];
  const material = new THREE.MeshStandardMaterial({
    color: definition.texture ? "#ffffff" : definition.color,
    map: definition.texture ? proceduralTexture(definition.texture, definition.color) : null,
    emissive: definition.emissive || "#000000",
    emissiveIntensity: definition.emissiveIntensity || 0,
    roughness: definition.roughness ?? 0.6,
    metalness: definition.metalness ?? 0.7,
    side: THREE.DoubleSide
  });
  materialCache.set(name, material);
  return material;
}

function extrudedGeometry(part) {
  const shape = new THREE.Shape();
  part.outline.forEach(([x, z], index) => index === 0 ? shape.moveTo(x, z) : shape.lineTo(x, z));
  shape.closePath();
  for (const hole of part.holes || []) {
    const path = new THREE.Path();
    path.absarc(hole.center[0], hole.center[1], hole.radius, 0, Math.PI * 2, true);
    shape.holes.push(path);
  }
  const geometry = new THREE.ExtrudeGeometry(shape, { depth: part.height, steps: 1, bevelEnabled: false, curveSegments: 24 });
  geometry.rotateX(Math.PI / 2);
  geometry.translate(0, part.height / 2, 0);
  return geometry;
}

function geometryFor(part) {
  if (part.shape === "box") return new THREE.BoxGeometry(...part.size);
  if (part.shape === "cylinder") return new THREE.CylinderGeometry(part.radius, part.radius, part.height, part.segments, 1, false);
  if (part.shape === "extruded") return extrudedGeometry(part);
  if (part.shape === "torus") return new THREE.TorusGeometry(part.radius, part.tube, part.radialSegments, part.tubularSegments, THREE.MathUtils.degToRad(part.arc));
  throw new Error(`Unsupported geometry: ${part.shape}`);
}

function makeDrone() {
  const group = new THREE.Group();
  for (const part of model.parts) {
    const geometry = geometryFor(part);
    const mesh = new THREE.Mesh(geometry, materialFor(part.material));
    mesh.name = part.id;
    mesh.position.set(...part.center);
    mesh.rotation.set(...part.rotation.map(THREE.MathUtils.degToRad));
    if (part.mirrorX) mesh.scale.x = -1;
    group.add(mesh);
    const edges = new THREE.LineSegments(
      new THREE.EdgesGeometry(geometry, 22),
      new THREE.LineBasicMaterial({ color: 0xaebbc1, transparent: true, opacity: 0.58 })
    );
    edges.position.copy(mesh.position);
    edges.rotation.copy(mesh.rotation);
    edges.scale.copy(mesh.scale);
    group.add(edges);
  }
  return group;
}

function pixelStats(canvas) {
  const data = canvas.getContext("webgl2") || canvas.getContext("webgl");
  // WebGL readback is handled through the renderer's preserveDrawingBuffer canvas copy.
  const copy = document.createElement("canvas");
  copy.width = canvas.width;
  copy.height = canvas.height;
  const context = copy.getContext("2d", { willReadFrequently: true });
  context.drawImage(canvas, 0, 0);
  const pixels = context.getImageData(0, 0, copy.width, copy.height).data;
  let occupied = 0;
  let mismatch = 0;
  for (let y = 0; y < copy.height; y++) {
    for (let x = 0; x < Math.floor(copy.width / 2); x++) {
      const left = pixels[(y * copy.width + x) * 4 + 3] > 12;
      const right = pixels[(y * copy.width + (copy.width - 1 - x)) * 4 + 3] > 12;
      if (left) occupied++;
      if (right) occupied++;
      if (left !== right) mismatch++;
    }
  }
  return {
    occupiedPixels: occupied,
    mirrorMismatchRatio: occupied === 0 ? 1 : mismatch / occupied
  };
}

function renderView(view) {
  const figure = document.createElement("figure");
  if (view.hero) figure.classList.add("hero");
  const canvas = document.createElement("canvas");
  const caption = document.createElement("figcaption");
  caption.textContent = view.label;
  figure.append(canvas, caption);
  document.querySelector("#views").append(figure);

  const width = figure.clientWidth * 2;
  const height = figure.clientHeight * 2;
  canvas.width = width;
  canvas.height = height;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1);
  renderer.setSize(width, height, false);
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.shadowMap.enabled = view.hero;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const scene = new THREE.Scene();
  scene.add(new THREE.HemisphereLight(0xd8f5ff, 0x101418, 1.7));
  const key = new THREE.DirectionalLight(0xffffff, 2.6);
  key.position.set(...view.eye);
  key.castShadow = view.hero;
  key.shadow.mapSize.set(2048, 2048);
  scene.add(key);
  const fill = new THREE.DirectionalLight(0x5fdfea, 0.65);
  fill.position.set(-20, 18, 30);
  scene.add(fill);
  const drone = makeDrone();
  drone.traverse(object => {
    if (object.isMesh) {
      object.castShadow = view.hero;
      object.receiveShadow = true;
    }
  });
  scene.add(drone);
  if (view.hero) {
    const floor = new THREE.Mesh(
      new THREE.PlaneGeometry(90, 70),
      new THREE.ShadowMaterial({ color: 0x05080a, opacity: 0.34 })
    );
    floor.rotation.x = -Math.PI / 2;
    floor.position.y = -6.9;
    floor.receiveShadow = true;
    scene.add(floor);
  }

  const aspect = width / height;
  const camera = view.hero
    ? new THREE.PerspectiveCamera(23, aspect, 0.1, 300)
    : new THREE.OrthographicCamera(-view.scale * aspect, view.scale * aspect, view.scale, -view.scale, 0.1, 300);
  camera.position.set(...view.eye);
  camera.up.set(...view.up);
  camera.lookAt(0, -0.5, 0);
  camera.updateProjectionMatrix();
  renderer.render(scene, camera);
  return { id: view.id, canvas, ...pixelStats(canvas) };
}

function compareSilhouette(left, right, mirrorRight) {
  const width = left.width;
  const height = left.height;
  const read = canvas => {
    const copy = document.createElement("canvas");
    copy.width = width;
    copy.height = height;
    const context = copy.getContext("2d", { willReadFrequently: true });
    context.drawImage(canvas, 0, 0, width, height);
    return context.getImageData(0, 0, width, height).data;
  };
  const leftPixels = read(left);
  const rightPixels = read(right);
  let occupied = 0;
  let mismatch = 0;
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const otherX = mirrorRight ? width - 1 - x : x;
    const a = leftPixels[(y * width + x) * 4 + 3] > 12;
    const b = rightPixels[(y * width + otherX) * 4 + 3] > 12;
    if (a) occupied++;
    if (b) occupied++;
    if (a !== b) mismatch++;
  }
  return occupied === 0 ? 1 : mismatch / occupied;
}

const rendered = views.map(renderView);
const stats = rendered.map(({ canvas, ...stat }) => stat);
const left = rendered.find(item => item.id === "left").canvas;
const right = rendered.find(item => item.id === "right").canvas;
const sideMirrorMismatchRatio = compareSilhouette(left, right, true);
document.querySelector("#summary").textContent = `${model.parts.length} PARTS / ${stats.length} VIEWS / SAME GEOMETRY`;
window.renderSummary = { role, partCount: model.parts.length, sideMirrorMismatchRatio, stats };
window.renderReady = true;
