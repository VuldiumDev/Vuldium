#version 460 core
#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require
#extension GL_EXT_shader_16bit_storage : require
#extension GL_EXT_nonuniform_qualifier : require

// Входные интерполированные атрибуты вершинного шейдера
layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_LightCoord;
layout(location = 3) in vec2 v_FragDistance;
layout(location = 4) flat in uint v_TextureId;
layout(location = 5) in vec3 v_Normal;

// Дескрипторный массив Bindless текстур
layout(set = 0, binding = 0) uniform sampler2D u_Textures[];
// Лайтмап освещения ванильного мира (блочный и небесный свет)
layout(set = 0, binding = 1) uniform sampler2D u_LightTex;

// Push Constants: параметры тумана и материала (128 байт)
layout(push_constant) uniform MaterialParameters {
    vec4  u_FogColor;        // Цвет атмосферного тумана (RGBA)
    vec2  u_FogRange;        // x: fogStart, y: fogEnd
    int   u_FogShape;        // 0 = Sphere, 1 = Cylinder
    float u_AlphaCutout;     // Порог отсечения прозрачных пикселей (Cutout)
    vec4  u_CameraPosOffset; // Мировое смещение камеры
};

// Выходные цели G-Buffer (совместимость с Iris / RT шейдерпаками)
layout(location = 0) out vec4 outFragColor;
layout(location = 1) out vec4 outNormalAO;
layout(location = 2) out vec4 outLightmapMaterial;

void main() {
    // 1. Bindless сэмплирование атласа текстур по динамическому Texture ID
    vec4 rawAlbedo = texture(u_Textures[nonuniformEXT(v_TextureId)], v_TexCoord);
    vec4 rawLight = texture(u_LightTex, v_LightCoord);

    // 2. Перевод вычислений на FP16 Rapid Packed Math (2x пропускная способность ALU)
    f16vec4 fpAlbedo = f16vec4(rawAlbedo);
    f16vec4 fpLight = f16vec4(rawLight);
    f16vec4 fpTint = f16vec4(v_Color);

    // 3. Быстрый альфа-тест на 16-битной сетке
    if (float16_t(fpAlbedo.a) < float16_t(u_AlphaCutout)) {
        discard;
    }

    // 4. Спаренное перемножение цвета освещения и тинта биома: Albedo * Light * Tint
    f16vec3 litColor = fpAlbedo.rgb * fpLight.rgb * fpTint.rgb;

    // 5. Расчет тумана с использованием векторной инструкции fma() (Fused Multiply-Add)
    // Формула: mix(litColor, fogColor, factor) = fma(fogColor - litColor, factor, litColor)
    f16vec3 fpFogColor = f16vec3(u_FogColor.rgb);
    float16_t dist = float16_t(v_FragDistance.y);
    float16_t fogStart = float16_t(u_FogRange.x);
    float16_t fogEnd = float16_t(u_FogRange.y);
    float16_t fogFactor = clamp((dist - fogStart) / max(fogEnd - fogStart, float16_t(0.001)), float16_t(0.0), float16_t(1.0));

    f16vec3 finalRgb = fma(fpFogColor - litColor, f16vec3(fogFactor), litColor);

    // 6. Запись в цели G-Buffer
    outFragColor = vec4(vec3(finalRgb), float(fpAlbedo.a));
    outNormalAO = vec4(normalize(v_Normal) * 0.5 + 0.5, float(fpTint.a)); // Нормали + AO
    outLightmapMaterial = vec4(v_LightCoord.x, v_LightCoord.y, float(v_TextureId) / 255.0, 1.0);
}
