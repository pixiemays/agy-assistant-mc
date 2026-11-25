$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$uvPath = Join-Path $env:USERPROFILE ".local\bin"
if (Test-Path $uvPath) {
    $env:Path = "$uvPath;$env:Path"
}

Write-Host "Syncing bridge environment (including PyInstaller)..."
uv sync --python 3.12 --extra build

Write-Host "Building agy-bridge.exe..."
uv run pyinstaller `
    --noconfirm `
    --clean `
    --onefile `
    --noconsole `
    --name agy-bridge `
    --collect-binaries google.antigravity `
    --collect-data google.antigravity `
    --hidden-import uvicorn.logging `
    --hidden-import uvicorn.loops `
    --hidden-import uvicorn.loops.auto `
    --hidden-import uvicorn.protocols `
    --hidden-import uvicorn.protocols.http `
    --hidden-import uvicorn.protocols.http.auto `
    --hidden-import uvicorn.protocols.websockets `
    --hidden-import uvicorn.protocols.websockets.auto `
    --hidden-import uvicorn.lifespan `
    --hidden-import uvicorn.lifespan.on `
    src/agy_bridge/entry.py

$exePath = Join-Path $PSScriptRoot "dist\agy-bridge.exe"
if (Test-Path $exePath) {
    $sizeMb = [math]::Round((Get-Item $exePath).Length / 1MB, 1)
    Write-Host "Built successfully: $exePath ($sizeMb MB)"
} else {
    throw "Build failed: $exePath not found"
}
