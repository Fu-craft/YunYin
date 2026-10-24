// 色阶流动 — clean-room, independent implementation.
// Inspired by the *idea* of AMLL's Isolation background (AGPL-3.0): four cover colours, OkLab
// mixing, noise-warped gradient axis and sine ripples. No AMLL shader source, assets or constants
// were copied; the transform is the published OkLab specification.
uniform vec2 uResolution;
uniform float uTime;
uniform float uVolume;
uniform float uDim;
uniform vec3 uColor0;
uniform vec3 uColor1;
uniform vec3 uColor2;
uniform vec3 uColor3;
uniform float uFlowFrequency;
uniform float uFlowAmplitude;
uniform float uFlowSpeed;
uniform float uFlowTilt;
uniform float uAngleJitter;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float gradientNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = dot(-1.0 + 2.0 * hash(i), f);
    float b = dot(-1.0 + 2.0 * hash(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
    float c = dot(-1.0 + 2.0 * hash(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
    float d = dot(-1.0 + 2.0 * hash(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));
    return 0.5 + 0.5 * mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

vec2 rotate(vec2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

float encodeSrgb(float c) {
    return c <= 0.0031308 ? 12.92 * c : 1.055 * pow(max(c, 0.0), 1.0 / 2.4) - 0.055;
}

vec3 okLabToSrgb(vec3 c) {
    float l = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
    float m = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
    float s = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
    l = l * l * l; m = m * m * m; s = s * s * s;
    vec3 linear = vec3(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    );
    return vec3(encodeSrgb(linear.r), encodeSrgb(linear.g), encodeSrgb(linear.b));
}

float gradientHash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / uResolution;
    vec2 p = uv - 0.5;

    float degree = gradientNoise(vec2(uTime * 0.10 + uAngleJitter * 3.0,
                                     p.x * p.y * 2.7 + uAngleJitter * 1.7));
    float noiseAngle = ((degree - 0.5) * 720.0 + 180.0) * 3.141592653589793 / 180.0;
    p = rotate(p, noiseAngle + uAngleJitter);

    float speed = uTime * uFlowSpeed;
    p.x += sin(p.y * uFlowFrequency + speed) / uFlowAmplitude;
    p.y += sin(p.x * uFlowFrequency * 1.5 + speed) / (uFlowAmplitude * 0.5);

    float rotatedX = rotate(p, uFlowTilt).x;
    float horizontal = smoothstep(-0.3, 0.2, rotatedX);
    vec3 color = mix(
        mix(uColor0, uColor1, horizontal),
        mix(uColor2, uColor3, horizontal),
        1.0 - smoothstep(-0.3, 0.5, p.y)
    );

    // Optional beat modulation: subtle lightness, never a hard flash.
    float beatWave = sin(uTime * 1.7 + p.y * 4.0) * 0.5 + 0.5;
    color.x = clamp(color.x + uVolume * 0.10 * beatWave, 0.0, 1.0);

    vec3 srgb = okLabToSrgb(color);
    float dither = (gradientHash(fragCoord) - 0.5) / 255.0;
    srgb = clamp(srgb + dither, 0.0, 1.0) * (1.0 - uDim);
    return vec4(srgb, 1.0);
}
