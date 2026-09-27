#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// Intel XeSS Style: High-frequency texture and edge enhancement with adaptive contrast clamping
void main() {
    vec2 step = vec2(length(vec2(dFdx(texCoord.x), dFdy(texCoord.x))),
                     length(vec2(dFdx(texCoord.y), dFdy(texCoord.y))));
    if (step.x <= 0.0 || step.y <= 0.0) {
        step = 1.0 / vec2(textureSize(InSampler, 0));
    }

    vec4 c = texture(InSampler, texCoord);
    vec4 top = texture(InSampler, texCoord + vec2(0.0, -step.y * 0.75));
    vec4 btm = texture(InSampler, texCoord + vec2(0.0, step.y * 0.75));
    vec4 lft = texture(InSampler, texCoord + vec2(-step.x * 0.75, 0.0));
    vec4 rgt = texture(InSampler, texCoord + vec2(step.x * 0.75, 0.0));

    vec3 minCol = min(c.rgb, min(min(top.rgb, btm.rgb), min(lft.rgb, rgt.rgb)));
    vec3 maxCol = max(c.rgb, max(max(top.rgb, btm.rgb), max(lft.rgb, rgt.rgb)));

    // Laplacian edge boost
    vec3 laplacian = (top.rgb + btm.rgb + lft.rgb + rgt.rgb) - 4.0 * c.rgb;
    vec3 detail = c.rgb - laplacian * 0.35;

    fragColor = vec4(clamp(detail, minCol, maxCol), c.a);
}
