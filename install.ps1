param(
    [string]$TargetDir = "",
    [ValidateSet("", "direct", "bridge")]
    [string]$Mode = ""
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Find-ModJar {
    $candidates = @(
        (Join-Path $PSScriptRoot "mod\build\libs\agyassistant-0.1.0.jar"),
        (Join-Path $PSScriptRoot "agyassistant-0.1.0.jar")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    $anyJar = Get-ChildItem -Path $PSScriptRoot -Filter "agyassistant-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($anyJar) { return $anyJar.FullName }
    $buildJar = Get-ChildItem -Path (Join-Path $PSScriptRoot "mod\build\libs") -Filter "agyassistant-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($buildJar) { return $buildJar.FullName }
    throw "Не найден agyassistant-*.jar. Сначала собери мод (mod\gradlew.bat build)."
}

function Find-BridgeExe {
    $candidates = @(
        (Join-Path $PSScriptRoot "bridge\dist\agy-bridge.exe"),
        (Join-Path $PSScriptRoot "agy-bridge.exe")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

function Find-MinecraftInstances {
    $found = New-Object System.Collections.Generic.List[object]

    $launcherRoots = @(
        @{ Name = "ElyPrismLauncher"; Path = "$env:APPDATA\ElyPrismLauncher\instances"; Sub = "minecraft" },
        @{ Name = "PrismLauncher";    Path = "$env:APPDATA\PrismLauncher\instances";    Sub = "minecraft" },
        @{ Name = "ModrinthApp";      Path = "$env:APPDATA\ModrinthApp\profiles";       Sub = "" },
        @{ Name = "CurseForge";       Path = "$env:USERPROFILE\curseforge\minecraft\Instances"; Sub = "" },
        @{ Name = "ATLauncher";       Path = "$env:APPDATA\ATLauncher\instances";       Sub = "" }
    )

    foreach ($lr in $launcherRoots) {
        if (!(Test-Path $lr.Path)) { continue }
        Get-ChildItem -Path $lr.Path -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $mcDir = if ($lr.Sub) { Join-Path $_.FullName $lr.Sub } else { $_.FullName }
            if (Test-Path (Join-Path $mcDir "mods")) {
                $found.Add([PSCustomObject]@{
                    Launcher = $lr.Name
                    Name     = $_.Name
                    McDir    = $mcDir
                })
            }
        }
    }

    if (Test-Path "$env:APPDATA\.minecraft\mods") {
        $found.Add([PSCustomObject]@{
            Launcher = "Vanilla"
            Name     = ".minecraft"
            McDir    = "$env:APPDATA\.minecraft"
        })
    }

    return $found
}

function Ensure-ModrinthDep {
    param(
        [string]$ModsDir,
        [string]$Pattern,
        [string]$ProjectSlug,
        [string]$DisplayName
    )
    $existing = Get-ChildItem -Path $ModsDir -Filter $Pattern -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "[OK] $DisplayName уже установлен: $($existing[0].Name)" -ForegroundColor Green
        return
    }
    Write-Host "[..] Скачиваю $DisplayName для NeoForge 1.21.1 с Modrinth..." -ForegroundColor Yellow
    $url = "https://api.modrinth.com/v2/project/$ProjectSlug/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%221.21.1%22%5D"
    $versions = Invoke-RestMethod -Uri $url -TimeoutSec 15
    if (!$versions -or $versions.Count -eq 0) {
        throw "Не удалось найти $DisplayName (NeoForge 1.21.1) на Modrinth."
    }
    $file = $versions[0].files | Where-Object { $_.primary } | Select-Object -First 1
    if (!$file) { $file = $versions[0].files[0] }
    $dst = Join-Path $ModsDir $file.filename
    Invoke-WebRequest -Uri $file.url -OutFile $dst -TimeoutSec 60
    Write-Host "[OK] Скачан $DisplayName -> $($file.filename)" -ForegroundColor Green
}

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "  AGY Assistant (Minecraft 1.21.1 NeoForge)  " -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""

$modJar = Find-ModJar
$bridgeExe = Find-BridgeExe

if ([string]::IsNullOrWhiteSpace($TargetDir)) {
    $instances = Find-MinecraftInstances
    if ($instances.Count -gt 0) {
        Write-Host "Найденные сборки:" -ForegroundColor White
        for ($i = 0; $i -lt $instances.Count; $i++) {
            $inst = $instances[$i]
            Write-Host ("  [{0}] {1} ({2})" -f ($i + 1), $inst.Name, $inst.Launcher)
        }
        Write-Host "  [0] Указать путь вручную"
        $choice = Read-Host "`nВыбери номер сборки"
        if ($choice -eq "0" -or [string]::IsNullOrWhiteSpace($choice)) {
            $TargetDir = Read-Host "Путь к папке minecraft (где лежит mods/)"
        } else {
            $idx = [int]$choice - 1
            if ($idx -lt 0 -or $idx -ge $instances.Count) { throw "Неверный номер." }
            $TargetDir = $instances[$idx].McDir
        }
    } else {
        $TargetDir = Read-Host "Инстансы не найдены. Укажи путь к папке minecraft (где лежит mods/)"
    }
}

$modsDir = Join-Path $TargetDir "mods"
if (!(Test-Path $modsDir)) {
    $altMods = Join-Path $TargetDir "minecraft\mods"
    if (Test-Path $altMods) {
        $TargetDir = Join-Path $TargetDir "minecraft"
        $modsDir = $altMods
    } else {
        New-Item -ItemType Directory -Force -Path $modsDir | Out-Null
    }
}

$cfgDir = Join-Path $TargetDir "config\agyassistant"
New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null

# 1. Зависимости
Ensure-ModrinthDep -ModsDir $modsDir -Pattern "*kotlinforforge*" -ProjectSlug "kotlin-for-forge" -DisplayName "Kotlin for Forge"
Ensure-ModrinthDep -ModsDir $modsDir -Pattern "*owo-lib*" -ProjectSlug "owo-lib" -DisplayName "oωo-lib"

# 2. Сам мод
Copy-Item -Path $modJar -Destination (Join-Path $modsDir (Split-Path $modJar -Leaf)) -Force
Write-Host "[OK] Установлен мод: $(Split-Path $modJar -Leaf)" -ForegroundColor Green

# 3. Режим работы
if ([string]::IsNullOrWhiteSpace($Mode)) {
    Write-Host "`nВыбери режим работы:" -ForegroundColor White
    Write-Host "  [1] Только мод (OpenRouter / Groq / Qwen / Ollama — без .exe, идеально для друзей)"
    Write-Host "  [2] Полный режим (Antigravity / OpenCode + agy-bridge.exe)"
    $m = Read-Host "Режим [1/2]"
    $Mode = if ($m -eq "2") { "bridge" } else { "direct" }
}

$cfgFile = Join-Path $cfgDir "config.json"

if ($Mode -eq "bridge") {
    if (!$bridgeExe) {
        Write-Host "[WARN] agy-bridge.exe не найден рядом со скриптом. Собери bridge\build_exe.ps1." -ForegroundColor Yellow
    } else {
        $running = Get-Process -Name "agy-bridge" -ErrorAction SilentlyContinue
        if ($running) {
            Write-Host "[..] Останавливаю запущенный agy-bridge.exe..." -ForegroundColor Yellow
            $running | Stop-Process -Force
            Start-Sleep -Milliseconds 500
        }
        Copy-Item -Path $bridgeExe -Destination (Join-Path $cfgDir "agy-bridge.exe") -Force
        Write-Host "[OK] Установлен agy-bridge.exe" -ForegroundColor Green
    }
} else {
    Write-Host "`nВыбери пресет API (можно поменять потом в игре через Config):" -ForegroundColor White
    Write-Host "  [1] OpenRouter (бесплатный Qwen3 :free)"
    Write-Host "  [2] Groq (быстрый бесплатный Qwen3-32B)"
    Write-Host "  [3] Ollama (локально на своей видеокарте, без ключа)"
    $p = Read-Host "Пресет [1/2/3]"

    $baseUrl = "https://openrouter.ai/api/v1"
    $modelName = "qwen/qwen3-235b-a22b:free"
    $needKey = $true

    if ($p -eq "2") {
        $baseUrl = "https://api.groq.com/openai/v1"
        $modelName = "qwen/qwen3-32b"
    } elseif ($p -eq "3") {
        $baseUrl = "http://127.0.0.1:11434/v1"
        $modelName = "qwen3:8b"
        $needKey = $false
    }

    $apiKey = ""
    if ($needKey) {
        $apiKey = Read-Host "Вставь API-ключ (или нажми Enter, чтобы ввести позже в Config)"
    }

    $configObj = [ordered]@{
        provider                 = "openai_compat"
        bridgeHost               = "127.0.0.1"
        bridgePort               = 18765
        autoStartBridge          = $false
        allowGameDirRead         = $false
        attachScreenshotByDefault = $false
        maxScreenshotWidth       = 1280
        screenshotJpegQuality    = 0.8
        customBridgeCommand      = ""
        systemPromptExtra        = ""
        model                    = ""
        apiKey                   = ""
        openaiBaseUrl            = $baseUrl
        openaiApiKey             = $apiKey.Trim()
        openaiModel              = $modelName
    }
    $configObj | ConvertTo-Json -Depth 4 | Set-Content -Path $cfgFile -Encoding UTF8
    Write-Host "[OK] Создан config.json ($baseUrl, $modelName)" -ForegroundColor Green
}

Write-Host "`nГотово! Запускай Minecraft. Хоткеи: G (чат), Alt+G (захват цели/предмета)." -ForegroundColor Cyan
