// 封面流动 — clean-room, independent implementation.
// Inspired by the *idea* of AMLL's mesh/cover background family (AGPL-3.0). No AMLL shader
// source, assets or constants were copied; every line here was written for YunYin.
uniform vec2 uResolution;
uniform shader uCover;
uniform vec2 uCoverSize;
uniform float uTime;
uniform float uVolume;
uniform float uDim;
uniform float uRotation;
uniform float uSeedPhase;
uniform float uSeedFrequency;
uniform vec3 uTint;

vec2 rotate(vec2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / uResolution;
    uv.y = 1.0 - uv.y;
    vec2 p = uv - 0.5;

    float angle = uRotation + uTime * 0.035 + uVolume * 1.6;
    p = rotate(p, angle);
    p *= max(0.55, 1.0 - uVolume * 0.9);

    float waveX = sin(p.y * uSeedFrequency + uTime * 0.7 + uSeedPhase);
    float waveY = cos(p.x * uSeedFrequency * 1.37 - uTime * 0.53);
    p += vec2(waveX * 0.012, waveY * 0.008);

    vec2 sampleUv = clamp(p + 0.5, 0.0, 1.0);
    vec4 cover = uCover.eval(sampleUv * uCoverSize);

    // Palette transition is visible as a faint tint so the shared 1 s cross-fade reads even
    // when the artwork itself barely changes between tracks.
    cover.rgb = mix(cover.rgb, uTint, 0.06);

    float dist = length(uv - 0.5);
    float mask = 0.60 + 0.40 * smoothstep(0.8, 0.3, dist);
    cover.rgb *= mask;

    float dither = (hash(fragCoord) - 0.5) / 255.0;
    cover.rgb = clamp(cover.rgb + dither, 0.0, 1.0) * (1.0 - uDim);
    return vec4(cover.rgb, 1.0);
}
