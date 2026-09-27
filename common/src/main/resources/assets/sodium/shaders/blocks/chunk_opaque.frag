#version 450 core
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
#extension GL_EXT_shader_16bit_storage : require

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_FragDistance;
layout(location = 3) in float fadeFactor;

layout(set = 0, binding = 0) uniform sampler2D u_BlockTex;
layout(set = 0, binding = 1) uniform sampler2D u_LightTex;

layout(push_constant) uniform MaterialData {
    vec4 u_FogColor;
    vec2 u_EnvironmentFog;
    vec2 u_RenderFog;
    float u_AlphaCutout;
};

layout(location = 0) out vec4 fragColor;

void main() {
    // 1. Сэмплирование текстуры блока и лайтмапа
    vec4 tex = texture(u_BlockTex, v_TexCoord);
    vec4 light = texture(u_LightTex, v_TexCoord);

    // 2. Перевод вычислений интерполяции освещения и цветов биомов на FP16 (Rapid Packed Math)
    f16vec4 fpTex = f16vec4(tex);
    f16vec4 fpLight = f16vec4(light);
    f16vec4 fpVertexColor = f16vec4(v_Color);

    // Спаренное перемножение цвета биома и освещения
    f16vec4 fpColor = fpTex * fpLight * fpVertexColor;

    // 3. Быстрый альфа-тест на полуточной сетке
    if (float16_t(fpColor.a) < float16_t(u_AlphaCutout)) {
        discard;
    }

    // 4. Расчет тумана через fma() за 1 такт ALU (mix(a, b, t) == fma(b - a, t, a))
    f16vec4 fpFog = f16vec4(u_FogColor);
    float16_t dist = float16_t(v_FragDistance.y);
    float16_t fogStart = float16_t(u_RenderFog.x);
    float16_t fogEnd = float16_t(u_RenderFog.y);
    float16_t fogFactor = clamp((dist - fogStart) / max(fogEnd - fogStart, float16_t(0.001)), float16_t(0.0), float16_t(1.0));

    f16vec3 finalRgb = fma(fpFog.rgb - fpColor.rgb, f16vec3(fogFactor), fpColor.rgb);

    fragColor = vec4(vec3(finalRgb), float(fpColor.a));
}
