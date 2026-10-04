# Script to build all supported Minecraft versions (26.1, 26.2, 26.3, 26.4)
$ErrorActionPreference = "Stop"

Write-Host "=== Building Minecraft 26.1 ===" -ForegroundColor Cyan
git checkout version/26.1
./gradlew build -x test
if (!(Test-Path "dist\26.1")) { New-Item -ItemType Directory -Path "dist\26.1" -Force }
Get-ChildItem -Path "build\mods" -Filter "vuldium-*-mc26.1*.jar" | Copy-Item -Destination "dist\26.1" -Force

Write-Host "=== Building Minecraft 26.2 ===" -ForegroundColor Cyan
git checkout version/26.2
./gradlew build -x test
if (!(Test-Path "dist\26.2")) { New-Item -ItemType Directory -Path "dist\26.2" -Force }
Get-ChildItem -Path "build\mods" -Filter "vuldium-*-mc26.2*.jar" | Copy-Item -Destination "dist\26.2" -Force

Write-Host "=== Building Minecraft 26.3 ===" -ForegroundColor Cyan
git checkout version/26.3
./gradlew build -x test
if (!(Test-Path "dist\26.3")) { New-Item -ItemType Directory -Path "dist\26.3" -Force }
Get-ChildItem -Path "build\mods" -Filter "vuldium-*-mc26.3*.jar" | Copy-Item -Destination "dist\26.3" -Force

Write-Host "=== Building Minecraft 26.4 ===" -ForegroundColor Cyan
git checkout version/26.4
./gradlew build -x test
if (!(Test-Path "dist\26.4")) { New-Item -ItemType Directory -Path "dist\26.4" -Force }
Get-ChildItem -Path "build\mods" -Filter "vuldium-*-mc26.4*.jar" | Copy-Item -Destination "dist\26.4" -Force

Write-Host "`nAll versions (26.1, 26.2, 26.3, 26.4) built successfully! Jars saved to dist/." -ForegroundColor Green
