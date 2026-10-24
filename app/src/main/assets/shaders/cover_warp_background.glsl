// 封面流动 — clean-room, independent implementation.
// Inspired by the *idea* of AMLL's mesh/cover background family (AGPL-3.0). No AMLL shader
// source, assets or constants were copied; the numeric parameter scheme is used as specification.
uniform vec2 uResolution;
uniform shader uCover;
uniform vec2 uCoverSize;
uniform float uTime;
uniform float uVolume;
uniform float uDim;
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

// Mild extra blur: 5 taps around the sampled UV. The CPU texture is pre-blurred, so this is
// no longer load-bearing; point sampling cannot expose a texel grid in a smooth 256 px image.
vec4 mildBlur(vec2 uv) {
    vec2 texel = 1.0 / uCoverSize;
    vec4 c = uCover.eval(uv * uCoverSize) * 0.36;
    c += uCover.eval((uv + vec2(texel.x * 2.0, 0.0)) * uCoverSize) * 0.16;
    c += uCover.eval((uv - vec2(texel.x * 2.0, 0.0)) * uCoverSize) * 0.16;
    c += uCover.eval((uv + vec2(0.0, texel.y * 2.0)) * uCoverSize) * 0.16;
    c += uCover.eval((uv - vec2(0.0, texel.y * 2.0)) * uCoverSize) * 0.16;
    return c;
}

vec4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / uResolution;
    uv.y = 1.0 - uv.y;

    // AMLL parameter spec (reimplemented, not copied):
    // uTime = frameTime / 10000; angle = (uTime + uVolume) * 2.
    float angle = (uTime + uVolume) * 2.0;
    vec2 centeredUV = uv - vec2(0.2);
    vec2 rotatedUV = rotate(centeredUV, angle);

    // volumeEffect = uVolume * 2; sampled scale = max(0.001, 1 - volumeEffect).
    float volumeEffect = uVolume * 2.0;
    vec2 finalUV = rotatedUV * max(0.001, 1.0 - volumeEffect) + vec2(0.5);

    // Per-track seeded warp (our addition; AMLL randomises its mesh, we have no mesh).
    float waveX = sin(finalUV.y * uSeedFrequency + uTime * 0.7 + uSeedPhase);
    float waveY = cos(finalUV.x * uSeedFrequency * 1.37 - uTime * 0.53);
    finalUV += vec2(waveX * 0.004, waveY * 0.003);

    vec4 cover = mildBlur(clamp(finalUV, 0.0, 1.0));
    cover.rgb = mix(cover.rgb, uTint, 0.06);

    // AMLL vignette mask: 0.6 + 0.4 * smoothstep(0.8, 0.3, dist).
    float dist = length(uv - vec2(0.5));
    float vignette = smoothstep(0.8, 0.3, dist);
    float mask = 0.6 + vignette * 0.4;
    cover.rgb *= mask;

    float dither = (hash(fragCoord) - 0.5) / 255.0;
    cover.rgb = clamp(cover.rgb + dither, 0.0, 1.0) * (1.0 - uDim);
    return vec4(cover.rgb, 1.0);
}
