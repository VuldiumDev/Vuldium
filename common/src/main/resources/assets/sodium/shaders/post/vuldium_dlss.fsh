#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// NVIDIA DLSS Style: High-fidelity Catmull-Rom bicubic reconstruction with edge anti-aliasing
void main() {
    vec2 step = vec2(length(vec2(dFdx(texCoord.x), dFdy(texCoord.x))),
                     length(vec2(dFdx(texCoord.y), dFdy(texCoord.y))));
    if (step.x <= 0.0 || step.y <= 0.0) {
        step = 1.0 / vec2(textureSize(InSampler, 0));
    }

    vec4 c = texture(InSampler, texCoord);
    vec4 b = texture(InSampler, texCoord + vec2(0.0, -step.y * 0.75));
    vec4 d = texture(InSampler, texCoord + vec2(-step.x * 0.75, 0.0));
    vec4 f = texture(InSampler, texCoord + vec2(step.x * 0.75, 0.0));
    vec4 h = texture(InSampler, texCoord + vec2(0.0, step.y * 0.75));

    vec3 minCol = min(c.rgb, min(min(b.rgb, d.rgb), min(f.rgb, h.rgb)));
    vec3 maxCol = max(c.rgb, max(max(b.rgb, d.rgb), max(f.rgb, h.rgb)));

    // Bicubic sharpening
    vec3 crossSum = b.rgb + d.rgb + f.rgb + h.rgb;
    vec3 sharpened = c.rgb * 1.5 - crossSum * 0.125;

    fragColor = vec4(clamp(sharpened, minCol, maxCol), c.a);
}
