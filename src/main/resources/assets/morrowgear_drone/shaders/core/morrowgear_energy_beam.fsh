#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>

in vec4 vertexColor;
in vec2 beamUv;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

float hash21(vec2 point) {
    point = fract(point * vec2(123.34, 456.21));
    point += dot(point, point + 45.32);
    return fract(point.x * point.y);
}

float valueNoise(vec2 point) {
    vec2 cell = floor(point);
    vec2 local = fract(point);
    local = local * local * (3.0 - 2.0 * local);
    float a = hash21(cell);
    float b = hash21(cell + vec2(1.0, 0.0));
    float c = hash21(cell + vec2(0.0, 1.0));
    float d = hash21(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

void main() {
    float across = abs(beamUv.y * 2.0 - 1.0);
    float radial = pow(max(0.0, 1.0 - across), 1.65);
    float endpointFade = smoothstep(0.0, 0.012, beamUv.x)
        * smoothstep(0.0, 0.012, 1.0 - beamUv.x);
    float travel = beamUv.x * 18.0 - GameTime * 520.0;
    float coarse = valueNoise(vec2(travel, beamUv.y * 5.0 + GameTime * 31.0));
    float fine = valueNoise(vec2(travel * 2.7, beamUv.y * 13.0 - GameTime * 57.0));
    float filament = smoothstep(0.38, 0.96, coarse * 0.68 + fine * 0.44);
    float pulse = 0.86 + 0.14 * sin(beamUv.x * 42.0 - GameTime * 410.0);
    float alpha = vertexColor.a * radial * endpointFade
        * mix(0.64, 1.0, filament) * pulse;
    vec3 energyColor = vertexColor.rgb * (0.82 + filament * 0.58);
    vec4 color = vec4(energyColor, alpha);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
        FogRenderDistanceEnd, FogColor);
}
