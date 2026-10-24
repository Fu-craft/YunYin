// 苹果 iOS — now-playing background.
// Portions ported from Lyricify-Backgrounds (Apache-2.0), © Lyricify contributors.
// Source: Lyricify.Backgrounds.AppleMusicInspired.Ios.Shared/Resources/AppleMusicIosBackground.hlsl
// (Apple Music Inspired iOS modern material). This is an AGSL reimplementation for Android.
uniform float2 uResolution;
uniform float uTime;
uniform float uScrimAlpha;
uniform float uDitherStrength;
uniform float uAudioLevel;
uniform float uCoverMix;
uniform shader uCoverCurrent;
uniform shader uCoverPrevious;
uniform float2 uCoverSizeCurrent;
uniform float2 uCoverSizePrevious;

const float PI = 3.14159265358979323846;

float2 rotate(float2 p, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return float2(c * p.x - s * p.y, s * p.x + c * p.y);
}

float3 applySaturation(float3 color, float saturation) {
    // Exact Rec.709 saturation matrix from the HLSL.
    float3 redColumn = float3(
        0.2126 + 0.7873 * saturation,
        0.2126 - 0.2126 * saturation,
        0.2126 - 0.2126 * saturation);
    float3 greenColumn = float3(
        0.7152 - 0.7152 * saturation,
        0.7152 + 0.2848 * saturation,
        0.7152 - 0.7152 * saturation);
    float3 blueColumn = float3(
        0.0722 - 0.0722 * saturation,
        0.0722 - 0.0722 * saturation,
        0.0722 + 0.9278 * saturation);
    return redColumn * color.r + greenColumn * color.g + blueColumn * color.b;
}

float3 treat(float3 color, float scrimAlpha) {
    color = applySaturation(color, 1.4);
    color = clamp(color, -0.752941, 1.25098);
    color = applySaturation(color, 0.70);
    return mix(color, float3(0.0, 0.0, 0.0), scrimAlpha);
}

float2 viewScale(float2 resolution) {
    float aspect = resolution.x / max(resolution.y, 1.0);
    return aspect >= 1.0
        ? float2(1.0, aspect)
        : float2(1.0 / aspect, 1.0);
}

float coverage(float2 local) {
    float edge = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.96, 1.0, edge);
}

float2 breatheUv(float2 uv, float mixValue) {
    // 10 s cycle: acos(sin(t*pi/5))/pi -> smoothstep. The original warps a 6/9-point
    // mesh; this analytic approximation keeps the same inhale/exhale at 3.5% amplitude.
    float2 centred = uv - 0.5;
    centred *= 1.0 - 0.035 * mixValue;
    centred.y += 0.004 * mixValue * sin(uv.x * 6.2831853 + uTime * 0.35);
    return centred + 0.5;
}

vec4 main(float2 fragCoord) {
    float2 resolution = max(uResolution, float2(1.0));
    float2 p = (fragCoord / resolution) * 2.0 - 1.0;
    p.y = -p.y;

    float time = uTime;
    float rotationScale = 1.0 + 0.15 * uAudioLevel;
    float angle0 = time * rotationScale * (2.0 * PI) / 120.0;
    float angle1 = time * rotationScale * (2.0 * PI) / 70.0;
    float angle2 = time * rotationScale * (2.0 * PI) / 90.0;
    float phase = acos(sin(time * PI / 5.0)) / PI;
    float mixValue = smoothstep(0.0, 1.0, phase);
    float2 view = viewScale(resolution);

    // Invert the vertex transform in reverse order: view -> translation -> scale -> rotation.
    float2 layerLocal0 = rotate(p / view / 1.4, -angle0);
    float2 layerLocal1 = rotate((p / view - float2(-0.25, 0.15)) / 0.7, -angle1);
    float2 layerLocal2 = rotate((rotate(p, -angle0) / view - float2(0.7, 0.7)) / 0.7, -angle2);

    float cov0 = coverage(layerLocal0);
    float cov1 = coverage(layerLocal1);
    float cov2 = coverage(layerLocal2);

    float2 uv0 = breatheUv(layerLocal0 * 0.5 + 0.5, mixValue);
    float2 uv1 = breatheUv(layerLocal1 * 0.5 + 0.5, mixValue);
    float2 uv2 = breatheUv(layerLocal2 * 0.5 + 0.5, mixValue);
    uv0.y = 1.0 - uv0.y;
    uv1.y = 1.0 - uv1.y;
    uv2.y = 1.0 - uv2.y;

    float2 baseUv = p * 0.5 + 0.5;
    baseUv.y = 1.0 - baseUv.y;

    // No explicit layer weights exist in the HLSL: it draws three opaque instanced quads
    // over an aspect-fill base. For a single per-pixel pass we blend the three layers at
    // 0.5 / 0.25 / 0.25 and give the base the remaining coverage, preserving its fill.
    float w0 = 0.5 * cov0;
    float w1 = 0.25 * cov1;
    float w2 = 0.25 * cov2;
    float baseWeight = max(0.0, 1.0 - w0 - w1 - w2);
    float total = max(w0 + w1 + w2 + baseWeight, 0.0001);

    // Child shaders are sampled inline in main(). A 3-tap horizontal kernel on the current
    // cover hides the residual pre-blur texel edges; the previous cover is sampled once for
    // the 1 s cross-fade.
    float2 sizeCurrent = max(uCoverSizeCurrent, float2(1.0));
    float2 texelCurrent = 1.0 / sizeCurrent;
    float2 sizePrevious = max(uCoverSizePrevious, float2(1.0));
    float2 uv0c = clamp(uv0, 0.0, 1.0) * sizeCurrent;
    float2 uv1c = clamp(uv1, 0.0, 1.0) * sizeCurrent;
    float2 uv2c = clamp(uv2, 0.0, 1.0) * sizeCurrent;
    float2 uvBaseC = clamp(baseUv, 0.0, 1.0) * sizeCurrent;
    vec3 c0 = mix(
        uCoverPrevious.eval(clamp(uv0, 0.0, 1.0) * sizePrevious).rgb,
        (uCoverCurrent.eval(uv0c) * 0.5
            + (uCoverCurrent.eval(clamp(uv0 - float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent)
                + uCoverCurrent.eval(clamp(uv0 + float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent))
                * 0.25).rgb,
        uCoverMix) * w0;
    vec3 c1 = mix(
        uCoverPrevious.eval(clamp(uv1, 0.0, 1.0) * sizePrevious).rgb,
        (uCoverCurrent.eval(uv1c) * 0.5
            + (uCoverCurrent.eval(clamp(uv1 - float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent)
                + uCoverCurrent.eval(clamp(uv1 + float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent))
                * 0.25).rgb,
        uCoverMix) * w1;
    vec3 c2 = mix(
        uCoverPrevious.eval(clamp(uv2, 0.0, 1.0) * sizePrevious).rgb,
        (uCoverCurrent.eval(uv2c) * 0.5
            + (uCoverCurrent.eval(clamp(uv2 - float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent)
                + uCoverCurrent.eval(clamp(uv2 + float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent))
                * 0.25).rgb,
        uCoverMix) * w2;
    vec3 base = mix(
        uCoverPrevious.eval(clamp(baseUv, 0.0, 1.0) * sizePrevious).rgb,
        (uCoverCurrent.eval(uvBaseC) * 0.5
            + (uCoverCurrent.eval(clamp(baseUv - float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent)
                + uCoverCurrent.eval(clamp(baseUv + float2(texelCurrent.x, 0.0), 0.0, 1.0) * sizeCurrent))
                * 0.25).rgb,
        uCoverMix) * baseWeight;
    float effectiveScrim = uScrimAlpha * (1.0 - 0.05 * uAudioLevel);
    float3 color = treat((c0 + c1 + c2 + base) / total, effectiveScrim);

    // IGN dither, exactly as FinishMaterial(), then the source's 0.07..0.97 clamp.
    float dither = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715)))) - 0.5;
    color += dither * (uDitherStrength / 255.0);
    return vec4(vec3(clamp(color, 0.07, 0.97)), 1.0);
}
