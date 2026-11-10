param(
    [Parameter(Mandatory = $true)][string]$SourceDir,
    [string]$OutputDir = ".",
    [string]$OutputName = ""
)

# 将语音包目录打包为 <id>-<version>.zip，并输出可直接写进 catalog.json 的字段。
# 用法： .\scripts\make-pack.ps1 -SourceDir .\samples\demo-xiaomi -OutputDir .\dist

$ErrorActionPreference = "Stop"
$src = (Resolve-Path $SourceDir).Path
$manifestPath = Join-Path $src "pack.json"
if (-not (Test-Path $manifestPath)) { throw "缺少 pack.json：$manifestPath" }

$manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
if (-not $manifest.id) { throw "pack.json 缺少 id" }
$version = if ($manifest.version) { $manifest.version } else { "1.0.0" }
$name = if ($OutputName) { $OutputName } else { "$($manifest.id)-$version" }
$outDir = (New-Item -ItemType Directory -Force -Path $OutputDir).FullName
$zipPath = Join-Path $outDir "$name.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath }

Compress-Archive -Path (Join-Path $src "*") -DestinationPath $zipPath
$zip = Get-Item $zipPath
$hash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()

Write-Host "[pack] $zipPath ($($zip.Length) B)"
Write-Host "[catalog]"
Write-Host "  `"id`": `"$($manifest.id)`","
Write-Host "  `"name`": `"$($manifest.name)`","
Write-Host "  `"version`": `"$version`","
Write-Host "  `"sha256`": `"$hash`","
Write-Host "  `"sizeBytes`": $($zip.Length),"
Write-Host "  `"voiceCount`": $(@($manifest.voices).Count),"
Write-Host "  `"url`": `"https://example.com/packs/$name.zip`""
