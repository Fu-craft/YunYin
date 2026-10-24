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

// 13-tap, bounded blur around the *warped* UV (centre + 4 axis + 4 diagonal + 4 outer).
// At the 128 px upload the outer taps are 2.6 texels from the centre; combined with
// Paint.isFilterBitmap this removes every visible texel edge at zoom 0.9–1.1.
vec4 blurredCover(vec2 uv) {
    vec2 texel = 1.0 / uCoverSize;
    vec4 c = uCover.eval(uv * uCoverSize) * 0.20;

    c += uCover.eval((uv + vec2(texel.x * 1.4, 0.0)) * uCoverSize) * 0.10;
    c += uCover.eval((uv - vec2(texel.x * 1.4, 0.0)) * uCoverSize) * 0.10;
    c += uCover.eval((uv + vec2(0.0, texel.y * 1.4)) * uCoverSize) * 0.10;
    c += uCover.eval((uv - vec2(0.0, texel.y * 1.4)) * uCoverSize) * 0.10;

    c += uCover.eval((uv + vec2(texel.x, texel.y)) * uCoverSize) * 0.06;
    c += uCover.eval((uv + vec2(-texel.x, texel.y)) * uCoverSize) * 0.06;
    c += uCover.eval((uv + vec2(texel.x, -texel.y)) * uCoverSize) * 0.06;
    c += uCover.eval((uv + vec2(-texel.x, -texel.y)) * uCoverSize) * 0.06;

    c += uCover.eval((uv + vec2(texel.x * 2.6, 0.0)) * uCoverSize) * 0.04;
    c += uCover.eval((uv - vec2(texel.x * 2.6, 0.0)) * uCoverSize) * 0.04;
    c += uCover.eval((uv + vec2(0.0, texel.y * 2.6)) * uCoverSize) * 0.04;
    c += uCover.eval((uv - vec2(0.0, texel.y * 2.6)) * uCoverSize) * 0.04;

    return c;
}

vec4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / uResolution;
    uv.y = 1.0 - uv.y;
    vec2 p = uv - 0.5;

    // Slow drift (0.012 rad/s) plus at most 0.6 rad from audio — no whip-around.
    float angle = uRotation + uTime * 0.012 + uVolume * 0.6;
    p = rotate(p, angle);
    // Gentle volume zoom: 1.0 → 0.65 at full level, clamped so it never collapses.
    p *= max(0.65, 1.0 - uVolume * 0.35);

    // Gentle, seeded sine warp: no large displacement, no hard directional stretch.
    float waveX = sin(p.y * uSeedFrequency + uTime * 0.7 + uSeedPhase);
    float waveY = cos(p.x * uSeedFrequency * 1.37 - uTime * 0.53);
    p += vec2(waveX * 0.006, waveY * 0.004);

    vec2 sampleUv = clamp(p + 0.5, 0.0, 1.0);
    vec4 cover = blurredCover(sampleUv);

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
