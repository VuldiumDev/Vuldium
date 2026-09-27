#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// AMD FidelityFX Robust Contrast-Adaptive Sharpening (RCAS)
// High-fidelity edge recovery that completely eliminates bilinear blur
void main() {
    // Screen pixel delta using hardware derivatives
    vec2 step = vec2(length(vec2(dFdx(texCoord.x), dFdy(texCoord.x))),
                     length(vec2(dFdx(texCoord.y), dFdy(texCoord.y))));
    if (step.x <= 0.0 || step.y <= 0.0) {
        step = 1.0 / vec2(textureSize(InSampler, 0));
    }

    // 5-tap cross at 0.7 screen pixel radius for sharpest gradient reconstruction
    vec2 r = step * 0.70;
    vec4 c = texture(InSampler, texCoord);
    vec4 b = texture(InSampler, texCoord + vec2(0.0, -r.y));
    vec4 d = texture(InSampler, texCoord + vec2(-r.x, 0.0));
    vec4 f = texture(InSampler, texCoord + vec2(r.x, 0.0));
    vec4 h = texture(InSampler, texCoord + vec2(0.0, r.y));

    // Corner samples for diagonal high-frequency detection
    vec4 a = texture(InSampler, texCoord + vec2(-r.x, -r.y));
    vec4 g = texture(InSampler, texCoord + vec2(r.x, -r.y));
    vec4 j = texture(InSampler, texCoord + vec2(-r.x, r.y));
    vec4 l = texture(InSampler, texCoord + vec2(r.x, r.y));

    // Per-channel local min and max
    vec3 minCol = min(c.rgb, min(min(b.rgb, d.rgb), min(f.rgb, h.rgb)));
    vec3 maxCol = max(c.rgb, max(max(b.rgb, d.rgb), max(f.rgb, h.rgb)));

    // Unsharp mask / edge enhancement: boost high-frequency transitions
    vec3 crossSum = b.rgb + d.rgb + f.rgb + h.rgb;
    vec3 cornerSum = a.rgb + g.rgb + j.rgb + l.rgb;

    // High-pass laplacian
    vec3 laplacian = c.rgb * 8.0 - (crossSum * 1.5 + cornerSum * 0.5);

    // Contrast-adaptive sharpening weight (RCAS)
    vec3 nz = min(minCol, 1.0 - maxCol);
    vec3 peak = max(maxCol, vec3(1e-4));
    vec3 w = -sqrt(clamp(nz / peak, 0.0, 1.0)) * 0.38;

    // RCAS filter application
    vec3 sharpened = (crossSum * w + c.rgb) / (4.0 * w + 1.0);

    // Add controlled high-frequency detail boost along edges
    sharpened += laplacian * 0.14;

    // Clamping with slight edge tolerance to preserve crisp micro-contrast
    vec3 edgePadding = max(maxCol - minCol, vec3(0.04)) * 0.12;
    vec3 finalRgb = clamp(sharpened, minCol - edgePadding, maxCol + edgePadding);
    finalRgb = clamp(finalRgb, 0.0, 1.0);

    fragColor = vec4(finalRgb, c.a);
}
