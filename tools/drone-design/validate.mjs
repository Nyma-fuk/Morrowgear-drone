import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { DOCK_INNER_WIDTH, ROLES, createModel } from "./model.mjs";

const radians = degrees => degrees * Math.PI / 180;

function rotatedHalfExtents(part) {
  const [rx, ry, rz] = part.rotation.map(radians);
  const cx = Math.cos(rx), sx = Math.sin(rx);
  const cy = Math.cos(ry), sy = Math.sin(ry);
  const cz = Math.cos(rz), sz = Math.sin(rz);
  const matrix = [
    [cy * cz, cz * sx * sy - cx * sz, sx * sz + cx * cz * sy],
    [cy * sz, cx * cz + sx * sy * sz, cx * sy * sz - cz * sx],
    [-sy, cy * sx, cx * cy]
  ];
  const half = part.size.map(value => value / 2);
  return matrix.map(row => row.reduce((sum, value, index) => sum + Math.abs(value) * half[index], 0));
}

function bounds(parts) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (const part of parts) {
    const extent = rotatedHalfExtents(part);
    for (let axis = 0; axis < 3; axis++) {
      min[axis] = Math.min(min[axis], part.center[axis] - extent[axis]);
      max[axis] = Math.max(max[axis], part.center[axis] + extent[axis]);
    }
  }
  return { min, max, size: max.map((value, axis) => value - min[axis]) };
}

function sameValues(left, right, epsilon = 1e-9) {
  return left.length === right.length && left.every((value, index) => Math.abs(value - right[index]) <= epsilon);
}

function validateRole(role) {
  const model = createModel(role);
  const failures = [];
  const ids = new Set();
  for (const part of model.parts) {
    if (ids.has(part.id)) failures.push(`duplicate id: ${part.id}`);
    ids.add(part.id);
    if (!["box", "cylinder", "extruded", "torus"].includes(part.shape)) failures.push(`unsupported shape: ${part.id}`);
    if (part.size.some(value => value <= 0)) failures.push(`invalid size: ${part.id}`);
  }

  const mirrorGroups = Map.groupBy(model.parts.filter(part => part.mirrorKey), part => part.mirrorKey);
  for (const [key, pair] of mirrorGroups) {
    const left = pair.find(part => part.side === "left");
    const right = pair.find(part => part.side === "right");
    if (!left || !right || pair.length !== 2) {
      failures.push(`mirror pair count: ${key}`);
      continue;
    }
    const expectedCenter = [-right.center[0], right.center[1], right.center[2]];
    const expectedRotation = [right.rotation[0], -right.rotation[1], -right.rotation[2]];
    if (!sameValues(left.center, expectedCenter)) failures.push(`mirror center: ${key}`);
    if (!sameValues(left.size, right.size)) failures.push(`mirror size: ${key}`);
    if (!sameValues(left.rotation, expectedRotation)) failures.push(`mirror rotation: ${key}`);
    if (left.material !== right.material) failures.push(`mirror material: ${key}`);
  }

  const measured = bounds(model.parts);
  if (measured.size[0] > DOCK_INNER_WIDTH) failures.push(`dock width exceeded: ${measured.size[0].toFixed(3)}`);
  const rotorLeft = model.parts.find(part => part.id === "rotor_hub_left");
  const rotorRight = model.parts.find(part => part.id === "rotor_hub_right");
  if (!rotorLeft || !rotorRight || rotorLeft.center[0] !== -rotorRight.center[0]) failures.push("rotor centers not symmetric");
  if (model.parts.filter(part => part.id.startsWith("rotor_hub_")).length !== 2) failures.push("Family A must have exactly two rotors");
  const bodyWidths = ["fuselage_lower", "fuselage_mid", "fuselage_top"]
    .map(id => model.parts.find(part => part.id === id)?.size[0]);
  if (!(bodyWidths[0] > bodyWidths[1] && bodyWidths[1] > bodyWidths[2])) failures.push("nose taper is not monotonic");
  const rotorDucts = model.parts.filter(part => part.id.startsWith("rotor_duct_"));
  if (rotorDucts.length !== 2 || rotorDucts.some(part => part.shape !== "extruded"
    || part.outline.length < 6 || part.holes.length !== 1 || part.holes[0].radius <= 5))
    failures.push("wing-shaped partial duct missing");
  const requiredRoleGroup = model.parts.filter(part => part.group === role);
  if (requiredRoleGroup.length === 0) failures.push(`role silhouette parts missing: ${role}`);
  const digest = createHash("sha256").update(JSON.stringify(model)).digest("hex");
  return {
    role,
    pass: failures.length === 0,
    failures,
    partCount: model.parts.length,
    mirrorPairCount: mirrorGroups.size,
    bounds: measured,
    dockClearance: DOCK_INNER_WIDTH - measured.size[0],
    modelSha256: digest
  };
}

await mkdir("../../docs/design/drone-family-a/validated", { recursive: true });
const results = ROLES.map(validateRole);
for (const role of ROLES) {
  await writeFile(`../../docs/design/drone-family-a/validated/family-a-${role}-model.json`, `${JSON.stringify(createModel(role), null, 2)}\n`);
}
const report = {
  generatedAt: new Date().toISOString(),
  method: "single numeric mesh model; mirrored parts generated from one side; orthographic views rendered from identical geometry",
  pass: results.every(result => result.pass),
  results
};
await writeFile("../../docs/design/drone-family-a/validated/model-validation.json", `${JSON.stringify(report, null, 2)}\n`);
if (!report.pass) {
  console.error(JSON.stringify(report, null, 2));
  process.exit(1);
}
for (const result of results) {
  console.log(`${result.role}: PASS parts=${result.partCount} mirrored=${result.mirrorPairCount} width=${result.bounds.size[0].toFixed(2)} clearance=${result.dockClearance.toFixed(2)} sha256=${result.modelSha256.slice(0, 12)}`);
}
