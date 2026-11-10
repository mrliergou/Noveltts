#requires -Version 5.1
<#
.SYNOPSIS
    把最新 APK 安装到设备并启动。

.EXAMPLE
    ./scripts/install.ps1 -Rebuild
#>
param(
    [string]$Serial,
    [string]$SdkPath,
    [switch]$Rebuild,
    [switch]$NoLaunch,
    [string]$Activity = 'org.nobody.multitts.replica/.MainActivity'
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

$sdkCandidates = @(
    $SdkPath,
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platform-tools\adb.exe')) }
if (-not $sdkCandidates) { throw '未找到 adb。请用 -SdkPath 或设置 ANDROID_HOME。' }
$sdk = (Resolve-Path $sdkCandidates[0]).Path
$adb = Join-Path $sdk 'platform-tools\adb.exe'

if ($Rebuild) {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { throw "构建失败（exit $LASTEXITCODE），已中止安装。" }
}

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK 不存在：$apk（先运行 scripts/build.ps1）" }

if (-not $Serial) {
    $devices = & $adb devices | Select-String -Pattern '\tdevice$' | ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -eq 1) {
        $Serial = $devices[0]
    } elseif ($devices.Count -eq 0) {
        throw '没有已授权的设备。请连接设备并允许 USB 调试。'
    } else {
        throw "检测到多台设备（$($devices -join ', ')），请用 -Serial 指定。"
    }
}

Write-Host "[install] device = $Serial" -ForegroundColor DarkGray
Write-Host "[install] apk    = $apk" -ForegroundColor DarkGray

& $adb -s $Serial install -r $apk 2>&1 | Tee-Object -Variable out
if ($LASTEXITCODE -ne 0) {
    if (($out | Out-String) -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Write-Host '签名与原安装包不一致。确认可以清数据后执行：' -ForegroundColor Yellow
        Write-Host "  & '$adb' -s $Serial uninstall org.nobody.multitts.replica" -ForegroundColor Yellow
    }
    exit 1
}

$version = (& $adb -s $Serial shell "dumpsys package org.nobody.multitts.replica | grep -m1 versionName").Trim()
Write-Host "[install] $version" -ForegroundColor DarkGray

if (-not $NoLaunch) {
    & $adb -s $Serial shell "am force-stop org.nobody.multitts.replica"
    & $adb -s $Serial shell "am start -W -n $Activity"
}
