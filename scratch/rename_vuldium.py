import os
import glob

files_to_check = (
    glob.glob('common/src/main/java/**/*.java', recursive=True) +
    glob.glob('common/src/main/resources/**/*.json', recursive=True) +
    glob.glob('fabric/src/main/resources/**/*.json', recursive=True) +
    glob.glob('neoforge/src/mod/resources/**/*.toml', recursive=True)
)

count = 0
for f in files_to_check:
    try:
        with open(f, 'r', encoding='utf-8') as fp:
            content = fp.read()
    except Exception:
        continue
    
    new_content = content
    # Replace loggers and user-facing strings
    new_content = new_content.replace('LoggerFactory.getLogger("Sodkam/', 'LoggerFactory.getLogger("Vuldium/')
    new_content = new_content.replace('LoggerFactory.getLogger("Sodkam")', 'LoggerFactory.getLogger("Vuldium")')
    new_content = new_content.replace('"Sodkam ', '"Vuldium ')
    new_content = new_content.replace('"Sodkam/WorldRenderer"', '"Vuldium/WorldRenderer"')
    new_content = new_content.replace('"SodkamWorldRenderer', '"VuldiumWorldRenderer')
    new_content = new_content.replace('[Sodkam Vulkan]', '[Vuldium Vulkan]')
    new_content = new_content.replace('Sodkam Low Latency', 'Vuldium Low Latency')
    new_content = new_content.replace('Sodkam Vulkan', 'Vuldium Vulkan')
    new_content = new_content.replace('Sodkam FSR', 'Vuldium FSR')
    new_content = new_content.replace('Sodkam RT', 'Vuldium RT')
    new_content = new_content.replace('Sodkam VRS', 'Vuldium VRS')
    new_content = new_content.replace('Sodkam Meshlets', 'Vuldium Meshlets')
    new_content = new_content.replace('Sodkam Async Compute', 'Vuldium Async Compute')
    new_content = new_content.replace('Sodkam Virtual Texturing', 'Vuldium Virtual Texturing')
    new_content = new_content.replace('Sodkam Indirect Buffer', 'Vuldium Indirect Buffer')
    new_content = new_content.replace('Sodkam Quad Index Buffer', 'Vuldium Quad Index Buffer')
    new_content = new_content.replace('Sodkam Atlas Texture Uploader', 'Vuldium Atlas Texture Uploader')
    new_content = new_content.replace('Sodkam ChunkRenderer', 'Vuldium ChunkRenderer')
    new_content = new_content.replace('Sodkam Velocity Buffer', 'Vuldium Velocity Buffer')
    new_content = new_content.replace('Sodkam Velocity Pass', 'Vuldium Velocity Pass')
    new_content = new_content.replace('Sodkam Translucent Pipeline', 'Vuldium Translucent Pipeline')
    new_content = new_content.replace('Sodkam Graphics Pipeline', 'Vuldium Graphics Pipeline')
    new_content = new_content.replace('Sodkam Block Entity Batcher', 'Vuldium Block Entity Batcher')
    new_content = new_content.replace('Sodkam Upscale Bridge', 'Vuldium Upscale Bridge')
    new_content = new_content.replace('Sodkam Upscale Blit', 'Vuldium Upscale Blit')
    new_content = new_content.replace('sodkam_world', 'vuldium_world')
    
    if new_content != content:
        with open(f, 'w', encoding='utf-8') as fp:
            fp.write(new_content)
        count += 1
        print(f'Updated {f}')

print(f'Done. Updated {count} files.')
