// SPDX-License-Identifier: AGPL-3.0-only
// Portions ported from QPlayer (Apache-2.0 wrapper) / AMLL Core (AGPL-3.0-only),
// © AMLL Contributors. Source: meshgradient.sksl.
uniform float2 resolution;
uniform float time;
uniform shader lookup;
uniform shader lookupPrev;
uniform shader cover;
uniform shader coverPrev;
uniform float fade;

const float TEX_SIZE = 256.0;
const float INV_255 = 1.0 / 255.0;
const float HALF_INV_255 = 0.5 / 255.0;

float gradientNoise(float2 uv) {
    return fract(52.9829189 * fract(dot(uv, float2(0.06711056, 0.00583715))));
}

float2 rotated(float2 uv, float angle) {
    float2 centered = uv - float2(0.3);
    float s = sin(angle);
    float c = cos(angle);
    return float2(c * centered.x - s * centered.y,
                  s * centered.x + c * centered.y);
}

half4 main(float2 fragCoord) {
    float2 uv = lookup.eval(fragCoord).rg;
    float2 uvPrev = lookupPrev.eval(fragCoord).rg;
    float2 currentUv = rotated(uv, time * 0.2) + float2(0.5);
    float2 previousUv = rotated(uvPrev, time * 0.2) + float2(0.5);
    float3 current = cover.eval(currentUv * TEX_SIZE).rgb;
    float3 previous = coverPrev.eval(previousUv * TEX_SIZE).rgb;
    float3 rgb = mix(previous, current, fade);
    float dither = INV_255 * gradientNoise(fragCoord) - HALF_INV_255;
    rgb += float3(dither);
    float dist = distance(uv, float2(0.5));
    float vignette = smoothstep(0.8, 0.3, dist);
    rgb *= 0.6 + vignette * 0.4;
    return half4(rgb, 1.0);
}
