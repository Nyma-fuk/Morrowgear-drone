export const UNIT_PER_BLOCK = 16;
export const DOCK_INNER_WIDTH = 48;

export const MATERIALS = Object.freeze({
  armor: { color: "#202a30", roughness: 0.64, metalness: 0.8, texture: "armor" },
  armorMid: { color: "#46525a", roughness: 0.52, metalness: 0.84, texture: "armorMid" },
  mechanism: { color: "#10171b", roughness: 0.5, metalness: 0.74, texture: "mechanism" },
  edge: { color: "#aebbc1", roughness: 0.3, metalness: 0.92, texture: "edge" },
  orange: { color: "#c96820", roughness: 0.44, metalness: 0.68, texture: "orange" },
  cyan: { color: "#20d7df", emissive: "#20d7df", emissiveIntensity: 2.2, roughness: 0.18 },
  cyanCore: { color: "#8effff", emissive: "#8effff", emissiveIntensity: 3.0, roughness: 0.12 },
  rotor: { color: "#080c0f", roughness: 0.38, metalness: 0.8, texture: "mechanism" }
});

const part = (id, shape, center, size, material, rotation = [0, 0, 0], group = "common", geometry = {}) => ({
  id, shape, center, size, rotation, material, group, ...geometry
});
const box = (id, center, size, material, rotation = [0, 0, 0], group = "common") =>
  part(id, "box", center, size, material, rotation, group);
const cylinder = (id, center, radius, height, material, group = "common") =>
  part(id, "cylinder", center, [radius * 2, height, radius * 2], material, [0, 0, 0], group, { radius, height, segments: 24 });
const extruded = (id, center, outline, height, material, group = "common", holes = []) => {
  const xs = outline.map(point => point[0]);
  const zs = outline.map(point => point[1]);
  return part(id, "extruded", center,
    [Math.max(...xs) - Math.min(...xs), height, Math.max(...zs) - Math.min(...zs)],
    material, [0, 0, 0], group, { outline, holes, height });
};
const torus = (id, center, radius, tube, material, rotation = [0, 0, 0], group = "common", arc = 180) =>
  part(id, "torus", center, [(radius + tube) * 2, tube * 2, (radius + tube) * 2], material, rotation, group,
    { radius, tube, radialSegments: 8, tubularSegments: 32, arc });

function mirrored(id, rightPart) {
  const [x, y, z] = rightPart.center;
  const [rx, ry, rz] = rightPart.rotation;
  const shared = { ...rightPart, mirrorKey: id };
  return [
    { ...shared, id: `${id}_left`, center: [-Math.abs(x), y, z], rotation: [rx, -ry, -rz], side: "left", mirrorX: true },
    { ...shared, id: `${id}_right`, center: [Math.abs(x), y, z], rotation: [rx, ry, rz], side: "right", mirrorX: false }
  ];
}

function mirrorBox(id, center, size, material, rotation = [0, 0, 0], group = "common") {
  return mirrored(id, box(id, center, size, material, rotation, group));
}

const FUSELAGE_LOWER = [[-4.6, 8.8], [-5.0, 3.5], [-4.4, -4.8], [-3.0, -10.8], [0, -13.0], [3.0, -10.8], [4.4, -4.8], [5.0, 3.5], [4.6, 8.8], [2.6, 12.0], [-2.6, 12.0]];
const FUSELAGE_MID = [[-3.9, 8.5], [-4.25, 2.5], [-3.6, -5.1], [-2.35, -10.5], [0, -12.0], [2.35, -10.5], [3.6, -5.1], [4.25, 2.5], [3.9, 8.5], [2.2, 10.9], [-2.2, 10.9]];
const FUSELAGE_TOP = [[-2.65, 7.0], [-3.0, 1.5], [-2.5, -5.0], [-1.55, -9.0], [0, -10.0], [1.55, -9.0], [2.5, -5.0], [3.0, 1.5], [2.65, 7.0], [1.6, 9.0], [-1.6, 9.0]];
const DUCT_OUTLINE = [[-6.0, -6.8], [-2.4, -9.0], [4.2, -8.4], [7.1, -5.8], [7.0, 6.8], [2.8, 8.8], [-5.7, 6.2]];
const DUCT_HOLE = { center: [0.3, -0.2], radius: 5.35, segments: 32 };

function baseParts() {
  const parts = [
    extruded("fuselage_lower", [0, -0.9, 0], FUSELAGE_LOWER, 2.2, "mechanism"),
    extruded("fuselage_mid", [0, 1.0, 0], FUSELAGE_MID, 3.2, "armor"),
    extruded("fuselage_top", [0, 3.0, -0.4], FUSELAGE_TOP, 1.1, "armorMid"),
    box("top_panel_front", [0, 3.72, -5.0], [3.7, 0.22, 5.0], "armorMid"),
    box("top_panel_mid", [0, 3.72, 0.8], [4.4, 0.22, 4.6], "armorMid"),
    box("top_panel_rear", [0, 3.72, 6.0], [3.7, 0.22, 3.5], "armorMid"),
    box("sensor_visor", [0, 1.0, -12.55], [5.2, 1.15, 0.5], "cyan"),
    box("sensor_chin", [0, -0.65, -12.25], [2.4, 1.8, 1.2], "mechanism"),
    box("sensor_core", [0, -0.65, -12.9], [1.45, 1.2, 0.3], "cyanCore"),
    box("rear_service_hatch", [0, 0.8, 12.05], [4.2, 2.5, 0.35], "mechanism"),
    box("module_bay", [0, -2.25, 1.2], [5.8, 1.0, 9.6], "mechanism"),
    box("maintenance_latch_front", [0, 3.93, -2.0], [1.0, 0.22, 1.2], "orange"),
    box("maintenance_latch_rear", [0, 3.93, 6.0], [1.0, 0.22, 1.2], "orange")
  ];

  parts.push(...mirrored("rotor_duct", extruded("rotor_duct", [13.45, 0.05, 0], DUCT_OUTLINE, 1.2, "armorMid", "common", [DUCT_HOLE])));
  parts.push(...mirrorBox("duct_outer_edge", [20.15, 0.5, 0.4], [0.7, 1.1, 12.7], "edge"));
  parts.push(...mirrorBox("rotor_arm_front", [7.0, -0.1, -3.6], [7.0, 1.2, 1.5], "mechanism", [0, -8, 0]));
  parts.push(...mirrorBox("rotor_arm_rear", [7.0, -0.1, 3.5], [7.0, 1.2, 1.5], "mechanism", [0, 8, 0]));
  parts.push(...mirrored("rotor_hub", cylinder("rotor_hub", [13.75, 0.72, -0.2], 1.45, 1.2, "edge")));
  for (const angle of [0, 60, 120]) parts.push(...mirrorBox(`rotor_blade_${angle}`, [13.75, 0.65, -0.2], [10.4, 0.24, 0.75], "rotor", [0, angle, 0]));
  parts.push(...mirrored("rotor_light_arc", torus("rotor_light_arc", [13.75, 0.78, -0.2], 5.45, 0.13, "cyan", [90, 0, 20], "common", 145)));
  parts.push(...mirrorBox("rear_cooling_vent", [2.65, 0.6, 11.8], [1.0, 2.7, 0.4], "mechanism"));
  parts.push(...mirrorBox("rear_nav_light", [3.25, -0.4, 11.82], [0.55, 0.55, 0.25], "cyan"));
  parts.push(...mirrorBox("landing_strut", [4.1, -4.0, -4.7], [0.8, 5.3, 0.8], "mechanism", [0, 0, 20]));
  parts.push(...mirrorBox("landing_skid", [4.9, -6.5, -4.2], [4.8, 0.65, 1.0], "edge"));
  return parts;
}

function scoutParts() {
  return [
    ...mirrorBox("scout_antenna", [1.75, 5.25, 7.4], [0.55, 4.6, 0.85], "armorMid", [-18, 0, 0], "scout"),
    cylinder("scout_optic", [0, -3.2, -8.7], 1.0, 1.8, "cyanCore", "scout")
  ];
}

function engineerParts() {
  return [
    cylinder("engineer_emitter_mount", [0, -3.0, -5.6], 1.6, 1.6, "mechanism", "engineer"),
    cylinder("engineer_emitter", [0, -5.3, -5.6], 1.05, 4.0, "armorMid", "engineer"),
    cylinder("engineer_laser_aperture", [0, -7.45, -5.6], 0.65, 0.35, "cyanCore", "engineer"),
    ...mirrorBox("engineer_tool_housing", [4.75, -1.6, -4.2], [2.2, 2.8, 4.8], "armorMid", [0, 0, 0], "engineer"),
    ...mirrorBox("engineer_tool_latch", [4.8, -1.6, -6.65], [0.8, 1.1, 0.3], "orange", [0, 0, 0], "engineer")
  ];
}

function fieldParts() {
  return [
    ...mirrorBox("field_module_rail", [5.2, 0.0, 0.5], [1.7, 2.4, 9.0], "armorMid", [0, 0, 0], "field"),
    ...mirrorBox("field_module_latch", [5.45, 0.1, -2.0], [0.35, 1.0, 1.3], "orange", [0, 0, 0], "field")
  ];
}

function guardParts() {
  const shoulderOutline = [[-1.8, -4.2], [1.5, -3.5], [2.0, 3.0], [0.7, 4.0], [-1.8, 3.3]];
  return [
    ...mirrored("guard_shoulder", extruded("guard_shoulder", [5.4, 0.5, -5.6], shoulderOutline, 3.0, "armorMid", "guard")),
    ...mirrorBox("guard_hardpoint_cover", [5.25, -1.6, 0.8], [2.4, 1.8, 5.6], "mechanism", [0, 0, 0], "guard"),
    ...mirrorBox("guard_forward_sensor", [3.15, -0.3, -10.8], [1.0, 1.0, 0.45], "cyanCore", [0, 0, 0], "guard"),
    ...mirrorBox("guard_oblique_sensor", [5.5, -0.2, -7.3], [0.45, 0.9, 1.1], "cyan", [0, -8, 0], "guard")
  ];
}

const ROLE_PARTS = Object.freeze({ scout: scoutParts, engineer: engineerParts, field: fieldParts, guard: guardParts });
export const ROLES = Object.freeze(Object.keys(ROLE_PARTS));

export function createModel(role) {
  if (!ROLE_PARTS[role]) throw new Error(`Unknown role: ${role}`);
  return { schemaVersion: 2, family: "A", role, frontAxis: "-Z", unitsPerBlock: UNIT_PER_BLOCK, parts: [...baseParts(), ...ROLE_PARTS[role]()] };
}
