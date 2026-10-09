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
    point = fract(point * vec2(127.17, 311.73));
    point += dot(point, point + 41.19);
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
    float endpointFade = smoothstep(0.0, 0.018, beamUv.x)
        * smoothstep(0.0, 0.018, 1.0 - beamUv.x);
    float radial = pow(max(0.0, 1.0 - across), 1.42);
    float whiteCore = pow(max(0.0, 1.0 - across), 9.0);
    float travel = beamUv.x * 22.0 - GameTime * 760.0;
    float turbulence = valueNoise(vec2(travel, across * 9.0 + GameTime * 43.0));
    float micro = valueNoise(vec2(travel * 3.1, across * 23.0 - GameTime * 79.0));
    float pressurePhase = abs(fract(beamUv.x * 7.0 - GameTime * 19.0) - 0.5);
    float pressureFront = pow(max(0.0, 1.0 - pressurePhase * 8.0), 3.0);
    float ionizedEdge = smoothstep(0.32, 0.82, across) * (1.0 - smoothstep(0.82, 1.0, across));
    float unstableEdge = ionizedEdge * (0.45 + turbulence * 0.55);
    float alpha = vertexColor.a * radial * endpointFade
        * (0.72 + turbulence * 0.22 + micro * 0.10 + pressureFront * 0.36);
    vec3 plasma = vec3(0.45, 0.88, 1.0);
    vec3 color = vertexColor.rgb * (0.84 + turbulence * 0.24 + pressureFront * 0.42);
    color = mix(color, plasma, unstableEdge * 0.48);
    color = mix(color, vec3(1.0), whiteCore * 0.72 + pressureFront * whiteCore * 0.28);
    fragColor = apply_fog(vec4(color, alpha), sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
        FogRenderDistanceEnd, FogColor);
}
