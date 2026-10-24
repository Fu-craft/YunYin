// Clean-room mesh-like flow for YunYin. The QPlayer Mesh Gradient files
// (meshgradient.sksl / AMLLMeshGradient.java) are AGPL-3.0, so no code or mesh presets
// from them are used here. This is an independent procedural warp of the adjusted cover.
uniform float2 resolution;
uniform float time;
uniform shader cover;
uniform shader coverPrev;
uniform float fade;

const float TEX_SIZE = 256.0;

float2 meshUv(float2 uv, float t) {
    float2 p = uv - 0.5;
    p += 0.06 * float2(sin(uv.y * 5.0 + t * 0.33), cos(uv.x * 4.0 - t * 0.27));
    p *= 1.0 + 0.04 * sin(t * 0.21 + uv.x * 3.0);
    return clamp(p + 0.5, 0.002, 0.998);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float2 warped = meshUv(uv, time);
    float3 current = cover.eval(warped * TEX_SIZE).rgb;
    float3 previous = coverPrev.eval(warped * TEX_SIZE).rgb;
    float3 rgb = mix(previous, current, fade);
    float vignette = smoothstep(0.8, 0.3, distance(uv, float2(0.5)));
    rgb *= 0.6 + vignette * 0.4;
    float dither = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453);
    rgb += float3((dither - 0.5) / 255.0);
    return half4(rgb, 1.0);
}
