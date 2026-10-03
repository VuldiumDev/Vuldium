# Script to build all supported Minecraft versions (26.2, 26.3, 26.4)
$ErrorActionPreference = "Stop"

Write-Host "=== Building Minecraft 26.2 ===" -ForegroundColor Cyan
git checkout version/26.2
./gradlew build -x test
if (!(Test-Path "dist\26.2")) { New-Item -ItemType Directory -Path "dist\26.2" }
Copy-Item "build\mods\sodium-fabric-0.9.3-SNAPSHOT+mc26.2-local.jar" -Destination "dist\26.2\vuldium-fabric-0.9.3-SNAPSHOT+mc26.2-local.jar" -Force
Copy-Item "build\mods\sodium-fabric-0.9.3-SNAPSHOT+mc26.2-local.jar" -Destination "dist\26.2\sodium-fabric-0.9.3-SNAPSHOT+mc26.2-local.jar" -Force
Copy-Item "build\mods\sodium-neoforge-0.9.3-SNAPSHOT+mc26.2-local.jar" -Destination "dist\26.2\sodium-neoforge-0.9.3-SNAPSHOT+mc26.2-local.jar" -Force

Write-Host "=== Building Minecraft 26.3 ===" -ForegroundColor Cyan
git checkout version/26.3
./gradlew build -x test
if (!(Test-Path "dist\26.3")) { New-Item -ItemType Directory -Path "dist\26.3" }
Copy-Item "build\mods\sodium-fabric-0.9.3-SNAPSHOT+mc26.3-local.jar" -Destination "dist\26.3\vuldium-fabric-0.9.3-SNAPSHOT+mc26.3-local.jar" -Force
Copy-Item "build\mods\sodium-fabric-0.9.3-SNAPSHOT+mc26.3-local.jar" -Destination "dist\26.3\sodium-fabric-0.9.3-SNAPSHOT+mc26.3-local.jar" -Force
Copy-Item "build\mods\sodium-neoforge-0.9.3-SNAPSHOT+mc26.3-local.jar" -Destination "dist\26.3\sodium-neoforge-0.9.3-SNAPSHOT+mc26.3-local.jar" -Force

Write-Host "=== Building Minecraft 26.4 ===" -ForegroundColor Cyan
git checkout version/26.4
./gradlew build -x test
if (!(Test-Path "dist\26.4")) { New-Item -ItemType Directory -Path "dist\26.4" }
Copy-Item "build\mods\sodium-fabric-0.9.4-SNAPSHOT+mc26.4-local.jar" -Destination "dist\26.4\vuldium-fabric-0.9.4-SNAPSHOT+mc26.4-local.jar" -Force
Copy-Item "build\mods\sodium-fabric-0.9.4-SNAPSHOT+mc26.4-local.jar" -Destination "dist\26.4\sodium-fabric-0.9.4-SNAPSHOT+mc26.4-local.jar" -Force
Copy-Item "build\mods\sodium-neoforge-0.9.4-SNAPSHOT+mc26.4-local.jar" -Destination "dist\26.4\sodium-neoforge-0.9.4-SNAPSHOT+mc26.4-local.jar" -Force

Write-Host "`nAll versions (26.2, 26.3, 26.4) built successfully! Jars saved to dist/." -ForegroundColor Green
