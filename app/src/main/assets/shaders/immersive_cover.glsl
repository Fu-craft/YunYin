// SPDX-License-Identifier: AGPL-3.0-only
// Backdrop composition ideas from NeriPlayer (GPL-3.0), © NeriPlayer contributors.
// Single fragment pass; cover is sampled with a subtle sine-wave UV ripple and a bottom alpha gradient.

uniform float2 resolution;
uniform float time;
uniform float coverAspect;
uniform float2 coverSize;
uniform shader cover;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(resolution, float2(1.0));
    float2 warped = uv;
    warped.x += sin(uv.y * 11.0 + time * 0.55) * 0.006;
    warped.y += cos(uv.x * 9.0 + time * 0.42) * 0.004;

    // Full-bleed cover crop: preserve the cover's aspect and centre the crop.
    float screenAspect = resolution.x / resolution.y;
    float2 sampleUv = warped;
    if (coverAspect > screenAspect) {
        sampleUv.x = (sampleUv.x - 0.5) * (screenAspect / coverAspect) + 0.5;
    } else {
        sampleUv.y = (sampleUv.y - 0.5) * (coverAspect / screenAspect) + 0.5;
    }
    float3 rgb = cover.eval(sampleUv * coverSize).rgb;

    float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    rgb = mix(rgb, float3(luma), 0.35) * 0.58;

    float vig = smoothstep(1.18, 0.32, length(uv - float2(0.5, 0.34)));
    rgb *= 0.80 + vig * 0.20;

    // The information block below the artwork sits on the normal page background again.
    float alpha = 1.0 - smoothstep(0.44, 0.74, uv.y);
    return half4(rgb * alpha, alpha);
}
