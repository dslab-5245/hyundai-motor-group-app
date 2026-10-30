# Headless smoke test on the Pleos Connect emulator: boot -> install -> start demo -> collect evidence.
#   powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\emulator-smoke.ps1 [-Apk path] [-Seconds 40] [-KeepRunning]
# Evidence lands in <repo>\build\smoke\<timestamp>\ : logcat.txt, crashes.txt, top.txt, media_session.txt, audio.txt, screen_*.png
param(
    [string]$Apk = "C:\Users\user\dev\drive-symphony\app\build\outputs\apk\debug\app-debug.apk",
    [int]$Seconds = 40,
    [switch]$KeepRunning
)
$ErrorActionPreference = 'Continue'
$sdk = 'C:\Users\user\dev-tools\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$pkg = 'io.drivesymphony.app'
$out = "C:\Users\user\dev\drive-symphony\build\smoke\" + (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force $out | Out-Null
$env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk

function Adb { param([Parameter(ValueFromRemainingArguments)]$a) & $adb @a }

Write-Host "== emulator boot (headless, swiftshader, cold)" -ForegroundColor Cyan
$running = (& $adb devices) -match 'emulator-'
if (-not $running) {
    Start-Process -FilePath $emu -ArgumentList @('-avd', 'pleos_connect', '-no-window', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-accel', 'auto', '-memory', '4096', '-logcat-output', "$out\emulator-logcat.txt") -WindowStyle Hidden
}
Adb wait-for-device | Out-Null
$booted = $false
for ($i = 0; $i -lt 180; $i++) {
    $v = (& $adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($v -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $booted) { Write-Host "boot timeout"; exit 2 }
Write-Host "booted: $(& $adb shell getprop ro.build.display.id) / sdk $(& $adb shell getprop ro.build.version.sdk)"
Adb shell settings put global window_animation_scale 0 | Out-Null

Write-Host "== install $Apk" -ForegroundColor Cyan
Adb install -r -g $Apk
Adb logcat -c

Write-Host "== launch: activity, then demo story scenario via DEMO action, then play via media session" -ForegroundColor Cyan
Adb shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 4
Adb shell am startservice -n "$pkg/.audio.SymphonyService" -a io.drivesymphony.action.DEMO --ez on true --es scenario story | Out-Null
Adb shell am startservice -n "$pkg/.audio.SymphonyService" -a io.drivesymphony.action.PLAY | Out-Null
Start-Sleep -Seconds 6
Adb exec-out screencap -p > "$out\screen_01_playing.png"
Write-Host "== running the demo for $Seconds s, sampling CPU" -ForegroundColor Cyan
$samples = @()
$steps = [math]::Max(1, [int]($Seconds / 10))
for ($i = 0; $i -lt $steps; $i++) {
    Start-Sleep -Seconds 10
    $top = & $adb shell top -b -n 1 2>$null | Select-String $pkg
    $samples += ($top | Out-String).Trim()
    Adb exec-out screencap -p > ("$out\screen_{0:d2}.png" -f ($i + 2))
}
$samples | Out-File "$out\top.txt" -Encoding utf8

Write-Host "== Gleo-style voice command: switch to jazz, ask for the score" -ForegroundColor Cyan
Adb shell am startservice -n "$pkg/.audio.SymphonyService" -a setGenre --es ACTION_PARAM '{\"genre\":\"JAZZ\"}' | Out-Null
Start-Sleep -Seconds 3
Adb shell am startservice -n "$pkg/.audio.SymphonyService" -a howIsMyDriving | Out-Null
Start-Sleep -Seconds 2

Write-Host "== evidence" -ForegroundColor Cyan
& $adb shell dumpsys media_session 2>$null | Out-File "$out\media_session.txt" -Encoding utf8
& $adb shell dumpsys audio 2>$null | Select-String -Pattern 'focus|drivesymphony|USAGE_MEDIA' | Out-File "$out\audio.txt" -Encoding utf8
& $adb logcat -d 2>$null | Out-File "$out\logcat.txt" -Encoding utf8
Select-String -Path "$out\logcat.txt" -Pattern 'FATAL EXCEPTION|AndroidRuntime|drivesymphony.*Exception|ANR in' | Out-File "$out\crashes.txt" -Encoding utf8
Select-String -Path "$out\logcat.txt" -Pattern 'SymphonyService|PleosVehicleSource|PleosNaviSource|AudioOutput|DrivingStateObserver|gleo reply' | Select-Object -Last 40 | Out-File "$out\app-log-tail.txt" -Encoding utf8

$crashCount = (Get-Content "$out\crashes.txt" | Measure-Object -Line).Lines
Write-Host "crash lines: $crashCount (see crashes.txt)"; Write-Host "cpu samples:"; Get-Content "$out\top.txt"
Write-Host "evidence dir: $out" -ForegroundColor Green
if (-not $KeepRunning) { Adb shell am force-stop $pkg | Out-Null; Adb emu kill | Out-Null }
