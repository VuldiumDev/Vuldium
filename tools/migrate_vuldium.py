import os
import re
import subprocess

def run_git(cmd):
    result = subprocess.run(["git"] + cmd, capture_output=True, text=True, check=True)
    return result.stdout.strip()

def migrate_branch():
    print("--- 1. Renaming Sodkam files with git mv ---")
    tracked_files = run_git(["ls-files"]).splitlines()
    for file_path in tracked_files:
        filename = os.path.basename(file_path)
        dirname = os.path.dirname(file_path)
        new_filename = None
        
        if "Sodkam" in filename:
            new_filename = filename.replace("Sodkam", "Vuldium")
        elif "sodkam" in filename:
            new_filename = filename.replace("sodkam", "vuldium")
            
        if new_filename:
            new_path = os.path.join(dirname, new_filename).replace("\\", "/")
            print(f"Renaming: {file_path} -> {new_path}")
            subprocess.run(["git", "mv", "-f", file_path, new_path], check=True)

    print("--- 2. Updating file contents ---")
    tracked_files = run_git(["ls-files"]).splitlines()
    text_extensions = {
        ".java", ".json", ".glsl", ".comp", ".fsh", ".vsh", 
        ".toml", ".accesswidener", ".md", ".txt", ".gradle", 
        ".kt", ".properties"
    }

    slop_replacements = [
        (
            re.compile(r'/\*\*[\s\S]*?High-performance Lazy DFU module for Vuldium[\s\S]*?\*/', re.MULTILINE),
            '/**\n * Lazy DFU compiler for Vuldium.\n * Defers DFU schema compilation to JIT on demand.\n */'
        ),
        (
            'LOGGER.info("[Vuldium/LazyDFU] Attached high-efficiency LazyDataFixer JIT proxy to engine fixer-upper.");',
            '// Attached JIT proxy'
        ),
        (
            'LOGGER.info("[Vuldium/LazyDFU] Suppressed eager DFU schema compilation #{} for {} types. Compilation deferred to JIT.",\n                count, types != null ? types.size() : 0);',
            'if (count == 1) LOGGER.info("[Vuldium/LazyDFU] Deferring eager DFU schema compilation to JIT.");\n        LOGGER.debug("[Vuldium/LazyDFU] Suppressed eager DFU schema compilation #{} for {} types.", count, types != null ? types.size() : 0);'
        ),
        (
            re.compile(r'/\*\*[\s\S]*?High-performance NIO Memory-Mapped Region File Manager for Vuldium[\s\S]*?\*/', re.MULTILINE),
            '/**\n * Memory-mapped region file I/O with an inflater pool for chunk decompression.\n */'
        ),
        (
            re.compile(r'/\*\*[\s\S]*?Multithreaded Async WorldGen Dispatcher for Vuldium[\s\S]*?\*/', re.MULTILINE),
            '/**\n * Asynchronous worker pool for background world generation stages.\n */'
        ),
        (
            'LOGGER.info("[Vuldium] Initialized Async WorldGen Engine with {} worker threads.", PARALLELISM);',
            'LOGGER.info("[Vuldium] WorldGen worker pool initialized with {} threads.", PARALLELISM);'
        ),
        (
            re.compile(r'/\*\*[\s\S]*?Вариативный шейдинг второго поколения \(Screen-Space VRS Tier 2\)[\s\S]*?\*/', re.MULTILINE),
            '/**\n * Вариативный шейдинг (Screen-Space VRS Tier 2).\n * Динамически настраивает частоту затенения периферии кадра.\n */'
        ),
        (
            re.compile(r'/\*\*[\s\S]*?Низколатентный менеджер WSI и презентации кадров SodkamWsiManager[\s\S]*?\*/', re.MULTILINE),
            '/**\n * Менеджер WSI и презентации кадров VuldiumWsiManager.\n * Управление VSync, Wayland tearing и режимами презентации swapchain.\n */'
        ),
        (
            '"Vuldium is a next-generation Vulkan & OpenGL rendering engine for Minecraft featuring Multi-AI Frame Generation (x2/x3), FSR/DLSS/XeSS Upscaling, Hardware Ray Tracing, Hi-Z GPU Culling, Translucent Sorting, and zero-allocation chunk pipelines."',
            '"Vuldium is a high-performance Vulkan & OpenGL rendering engine for Minecraft featuring upscaling, hardware ray tracing, Hi-Z GPU culling, translucent sorting, and optimized chunk pipelines."'
        ),
        (
            '"sodium.options.pages.vuldium": "Vuldium (AI & Vulkan)"',
            '"sodium.options.pages.vuldium": "Vuldium"'
        ),
        (
            '"sodium.options.pages.sodkam": "Vuldium (AI & Vulkan)"',
            '"sodium.options.pages.vuldium": "Vuldium"'
        ),
    ]

    for file_path in tracked_files:
        ext = os.path.splitext(file_path)[1].lower()
        if ext not in text_extensions:
            continue
        
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
        except (UnicodeDecodeError, FileNotFoundError):
            continue

        orig_content = content

        for pattern, repl in slop_replacements:
            if isinstance(pattern, re.Pattern):
                content = pattern.sub(repl, content)
            else:
                content = content.replace(pattern, repl)

        content = content.replace("sodkam$", "vuldium$")
        content = content.replace("USE_SODKAM_PACKED_VERTEX", "USE_VULDIUM_PACKED_VERTEX")
        content = content.replace("SODKAM_VERTEX_SCALE", "VULDIUM_VERTEX_SCALE")
        content = content.replace("SODKAM_VERTEX_OFFSET", "VULDIUM_VERTEX_OFFSET")
        content = content.replace("SODKAM_", "VULDIUM_")
        content = content.replace("Sodkam", "Vuldium")
        content = content.replace("sodkam", "vuldium")

        if content != orig_content:
            with open(file_path, "w", encoding="utf-8") as f:
                f.write(content)
            print(f"Updated content: {file_path}")

    print("--- 3. Updating LICENSE.md ---")
    update_license()

def update_license():
    polyform_text = ""
    if os.path.exists("LICENSE.md"):
        with open("LICENSE.md", "r", encoding="utf-8") as f:
            polyform_text = f.read()
        idx = polyform_text.find("# PolyForm Shield License 1.0.0")
        if idx != -1:
            polyform_text = polyform_text[idx:]

    license_content = f"""# Vuldium Dual Licensing Terms

Vuldium is built on the Sodium rendering engine and contains both upstream open-source code and proprietary Vuldium technologies.

---

## 1. Vuldium Proprietary Technologies & Innovations License

**Copyright (c) 2026 Vuldium Authors. All Rights Reserved.**

All original Vuldium proprietary source code, algorithms, architectures, shaders, and technologies—including but not limited to:
- The Vulkan rendering backend and execution context (
et.caffeinemc.mods.sodium.client.render.chunk.vulkan.*, 
et.caffeinemc.mods.sodium.client.gpu.device.vulkan.*, 
et.caffeinemc.mods.sodium.client.gpu.arena.vulkan.*)
- Zero-copy memory-mapped region I/O and SIMD loaders (
et.caffeinemc.mods.sodium.client.systems.regionio.*, ulkan.io.*)
- JIT Lazy DFU compiler proxy (
et.caffeinemc.mods.sodium.client.systems.dfu.*)
- Asynchronous chunk executor (
et.caffeinemc.mods.sodium.client.systems.worldgen.*)
- GPU Hi-Z occlusion culling and meshlet pipelines (ulkan.cull.*, ulkan.meshlet.*, ulkan.mesher.*)
- Frame pacing, WSI direct presentation, and low-latency synchronization (ulkan.pacing.*, ulkan.wsi.*, ulkan.latency.*)
- Proprietary shaders and compute programs located under ssets/sodium/shaders/compute/ and ssets/sodium/shaders/post/

**ARE STRICTLY PROPRIETARY AND PROTECTED BY COPYRIGHT LAW.**

### Restrictions:
1. **No Reproduction or Redistribution:** No part of the Vuldium proprietary technologies may be copied, reproduced, extracted, distributed, sublicensed, or integrated into any other software, modification (mod), client, or commercial product without prior express written permission from the Vuldium Authors.
2. **Source-Available for Verification Only:** Access to the source code of Vuldium proprietary modules is provided strictly for personal use, compiling personal client builds, and verification. No rights to fork, re-release, or reuse Vuldium modules in other projects are granted.

---

## 2. Upstream Sodium Engine License

Upstream Sodium codebase components created by JellySquid and CaffeineMC remain licensed under their respective original licenses (PolyForm Shield License 1.0.0 / GNU Lesser General Public License v3.0).

You are free to use, modify, and distribute the upstream Sodium functions in accordance with the terms of the PolyForm Shield License 1.0.0 below:

{polyform_text}
"""
    with open("LICENSE.md", "w", encoding="utf-8") as f:
        f.write(license_content.strip() + "\n")
    print("LICENSE.md updated successfully.")

if __name__ == "__main__":
    migrate_branch()

