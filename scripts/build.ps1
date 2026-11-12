#requires -Version 5.1
<#
.SYNOPSIS
    构建 MultiTTS 复刻版。

.DESCRIPTION
    统一设置 Android SDK、Gradle Home、离线依赖缓存后再调用 Gradle，避免每次手写一长串环境变量。

    重要：Gradle 启动器依赖 JDK 的 NIO selector，而 JDK 21 在 Windows 上用 AF_UNIX
    （命名管道）建立回环连接。受限沙箱会拒绝该操作，表现为
        java.io.IOException: Unable to establish loopback connection
    此时必须以 danger-full-access 提权重新执行本脚本，而不是修改 Gradle 配置。

.EXAMPLE
    ./scripts/build.ps1

.EXAMPLE
    ./scripts/build.ps1 -Task :app:assembleRelease -Clean
#>
param(
    [string]$Task = ':app:assembleDebug',
    [string]$SdkPath,
    [string]$GradleHome,
    [switch]$NoOffline,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-Sdk([string]$explicit) {
    $candidates = @(
        @(
            $explicit,
            $env:ANDROID_HOME,
            $env:ANDROID_SDK_ROOT,
            (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
            'C:\Android\Sdk'
        ) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms')) }
    )
    if (-not $candidates) {
        throw 'Android SDK 未找到。请用 -SdkPath 或设置 ANDROID_HOME 指向包含 platforms 的目录。'
    }
    return (Resolve-Path $candidates[0]).Path
}

function Resolve-GradleHome([string]$explicit) {
    $candidates = @(
        @(
            $explicit,
            $env:GRADLE_USER_HOME,
            (Join-Path $env:LOCALAPPDATA 'MultiTTS\gradle-home'),
            (Join-Path (Split-Path $root -Parent) 'MultiTTS\gradle-home'),
            (Join-Path $root '.gradle-home')
        ) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'wrapper\dists')) }
    )
    if (-not $candidates) {
        throw 'Gradle Home 未找到（需要包含 wrapper\dists 的离线缓存）。请用 -GradleHome 指定。'
    }
    return (Resolve-Path $candidates[0]).Path
}

$sdk = Resolve-Sdk $SdkPath
$gh = Resolve-GradleHome $GradleHome

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = $gh

# 离线依赖：Gradle 只读地复用已有 modules-2 缓存，构建仍在小 workspace 内写 journal
$depCache = Join-Path $gh 'caches'
if (-not $NoOffline -and (Test-Path (Join-Path $depCache 'modules-2'))) {
    $env:GRADLE_RO_DEP_CACHE = $depCache
}

$localProperties = Join-Path $root 'local.properties'
if (-not (Test-Path $localProperties)) {
    "sdk.dir=" + ($sdk -replace '\\', '\\') | Set-Content -Path $localProperties -Encoding ASCII
    Write-Host "[build] 已生成 local.properties -> $sdk" -ForegroundColor DarkGray
}

$gradleArgs = @('--console=plain')
if ($Clean) { $gradleArgs += 'clean' }
if (-not $NoOffline) { $gradleArgs += '--offline' }
$gradleArgs += $Task

$launcher = $null
if (Test-Path (Join-Path $root 'gradlew.bat')) {
    $launcher = Join-Path $root 'gradlew.bat'
} else {
    $dist = Get-ChildItem (Join-Path $gh 'wrapper\dists') -Directory -Filter 'gradle-*-bin' -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ChildItem $_.FullName -Directory } |
        ForEach-Object { Join-Path $_.FullName 'bin\gradle.bat' } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
    if (-not $dist) { throw '未找到 gradlew.bat，也未在 Gradle Home 中找到解压后的 gradle 分发包。' }
    $launcher = $dist
}

Write-Host "[build] launcher = $launcher" -ForegroundColor DarkGray
Write-Host "[build] sdk      = $sdk" -ForegroundColor DarkGray
Write-Host "[build] gradle   = $gh" -ForegroundColor DarkGray
Write-Host "[build] task     = $gradleArgs" -ForegroundColor DarkGray

Push-Location $root
try {
    & $launcher @gradleArgs 2>&1 | Tee-Object -Variable gradleOutput
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

$joined = ($gradleOutput | Out-String)
if ($joined -match 'Unable to establish loopback connection') {
    Write-Host ''
    Write-Host '构建被沙箱拦截：JDK 需要 AF_UNIX 回环管道，受限模式下不可用。' -ForegroundColor Yellow
    Write-Host '请以 danger-full-access 权限重跑本脚本（无需修改 Gradle 配置）。' -ForegroundColor Yellow
    exit 2
}

exit $code
