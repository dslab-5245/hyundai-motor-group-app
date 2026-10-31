# Headless smoke test on the Pleos Connect emulator: boot -> install -> demo story -> (optional) real vehicle signals
# through the Virtual Console REST API -> collect evidence.
#   powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\emulator-smoke.ps1 [-Apk path] [-Seconds 110] [-RealSignals] [-KeepRunning]
# Evidence lands in <repo>\build\smoke\<timestamp>\ : logcat.txt, crashes.txt, top.txt (process), threads.txt (per thread),
#   media_session.txt, audio.txt, app-log-tail.txt, screen_*.png, and with -RealSignals: cue.txt + vehicle-signals.txt
param(
    [string]$Apk = "C:\Users\user\dev\drive-symphony\app\build\outputs\apk\debug\app-debug.apk",
    [int]$Seconds = 110,   # demo story is 106 s: 20 s calm, 20-28 harsh, bloom, 86 s evasive swerve, rain, 103 s PARK
    [switch]$RealSignals,  # after the demo: demo off, then drive the Virtual Console (scripts/virtual-console.py cue)
    [switch]$KeepRunning
)
$ErrorActionPreference = 'Continue'
$sdk = 'C:\Users\user\dev-tools\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$pkg = 'io.drivesymphony.app'
$svc = "$pkg/.audio.SymphonyService"
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo ("build\smoke\" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $out | Out-Null
$env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk

# A plain function (no param block) so that adb flags such as -a / -p / -n are passed through instead of being bound.
function Adb { & $adb @args }
function Shot($name) {
    # PowerShell redirection corrupts binary stdout; capture on the device and pull instead.
    Adb shell screencap -p /sdcard/ds_shot.png | Out-Null
    Adb pull /sdcard/ds_shot.png (Join-Path $out $name) | Out-Null
}
function Threads($label) {
    $p = (Adb shell pidof $pkg | Out-String).Trim()
    if ($p) { "== $label (pid $p)" | Out-File (Join-Path $out 'threads.txt') -Append -Encoding utf8
              Adb shell top -H -b -n 1 -p $p 2>$null | Select-String -Pattern 'TID|drivesymphony|Audio|Render|Dispatch|Executor|binder' | ForEach-Object { $_.Line } | Out-File (Join-Path $out 'threads.txt') -Append -Encoding utf8 }
}

Write-Host "== emulator boot (headless, swiftshader, cold)" -ForegroundColor Cyan
$running = (Adb devices) -match 'emulator-'
if (-not $running) {
    Start-Process -FilePath $emu -ArgumentList @('-avd', 'pleos_connect', '-no-window', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-accel', 'auto', '-memory', '4096', '-logcat-output', "$out\emulator-logcat.txt") -WindowStyle Hidden
}
Adb wait-for-device | Out-Null
$booted = $false
for ($i = 0; $i -lt 180; $i++) {
    $v = (Adb shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($v -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $booted) { Write-Host "boot timeout"; exit 2 }
Write-Host "booted: $(Adb shell getprop ro.build.display.id) / sdk $(Adb shell getprop ro.build.version.sdk)"
Adb shell settings put global window_animation_scale 0 | Out-Null

Write-Host "== install $Apk" -ForegroundColor Cyan
Adb install -r -g $Apk
Adb logcat -c

Write-Host "== launch: activity, demo story via DEMO action, then PLAY" -ForegroundColor Cyan
Adb shell am start -n "$pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 4
Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on true --es scenario story | Out-Null
Adb shell am startservice -n $svc -a io.drivesymphony.action.PLAY | Out-Null
Start-Sleep -Seconds 6
Shot 'screen_01_playing.png'
$state = (Adb shell dumpsys media_session 2>$null | Select-String -Pattern "state=PlaybackState" | Select-Object -First 1 | Out-String).Trim()
Write-Host "media session: $state"

Write-Host "== running the demo for $Seconds s, sampling CPU (process + threads)" -ForegroundColor Cyan
$samples = @()
$steps = [math]::Max(1, [int]($Seconds / 10))
for ($i = 0; $i -lt $steps; $i++) {
    Start-Sleep -Seconds 10
    $top = Adb shell top -b -n 1 2>$null | Select-String $pkg
    $samples += ($top | Out-String).Trim()
    if ($i % 3 -eq 1) { Threads "demo t=$(($i + 1) * 10)s" }
    Shot ("screen_{0:d2}.png" -f ($i + 2))
}
$samples | Out-File (Join-Path $out 'top.txt') -Encoding utf8

Write-Host "== Gleo-style voice command: switch to jazz, ask for the score" -ForegroundColor Cyan
# --% hands the rest of the line to adb verbatim, so the JSON keeps its quotes all the way to the device shell.
& $adb --% shell "am startservice -n io.drivesymphony.app/.audio.SymphonyService -a setGenre --es ACTION_PARAM '{\"genre\":\"JAZZ\"}'"
Start-Sleep -Seconds 3
Adb shell am startservice -n $svc -a howIsMyDriving | Out-Null
Start-Sleep -Seconds 2
Shot 'screen_gleo_jazz.png'

if ($RealSignals) {
    Write-Host "== real vehicle signals: demo off, Virtual Console cue sheet" -ForegroundColor Cyan
    Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on false | Out-Null
    Start-Sleep -Seconds 2
    $cueLog = Join-Path $out 'cue.txt'
    $job = Start-Job -ScriptBlock {
        param($repo, $adb, $log)
        Set-Location $repo
        & python -I scripts\virtual-console.py --adb $adb cue *>&1 | Out-File $log -Encoding utf8
    } -ArgumentList $repo, $adb, $cueLog
    $t = 0
    while ($job.State -eq 'Running' -and $t -lt 150) {
        Start-Sleep -Seconds 10; $t += 10
        if ($t % 30 -eq 0) { Threads "real t=${t}s" }
        Shot ("screen_real_{0:d2}.png" -f ($t / 10))
    }
    Receive-Job $job | Out-Null; Remove-Job $job -Force
    Adb logcat -d 2>$null | Select-String -Pattern 'onChangeEvent|PleosVehicleSource|STEERING|BRAKE|TURN_SIGNAL|WIPER|GEAR|CRUISE|SEAT' | Select-Object -Last 80 | ForEach-Object { $_.Line } | Out-File (Join-Path $out 'vehicle-signals.txt') -Encoding utf8
    Shot 'screen_real_end.png'
}

Write-Host "== evidence" -ForegroundColor Cyan
Adb shell dumpsys media_session 2>$null | Out-File (Join-Path $out 'media_session.txt') -Encoding utf8
Adb shell dumpsys audio 2>$null | Select-String -Pattern 'focus|drivesymphony|USAGE_MEDIA' | Out-File (Join-Path $out 'audio.txt') -Encoding utf8
Adb logcat -d 2>$null | Out-File (Join-Path $out 'logcat.txt') -Encoding utf8
Select-String -Path (Join-Path $out 'logcat.txt') -Pattern 'FATAL EXCEPTION|AndroidRuntime|drivesymphony.*Exception|ANR in' | ForEach-Object { $_.Line } | Out-File (Join-Path $out 'crashes.txt') -Encoding utf8
Select-String -Path (Join-Path $out 'logcat.txt') -Pattern 'SymphonyService|PleosVehicleSource|PleosNaviSource|AudioOutput|DrivingStateObserver|gleo reply|engine load' | Select-Object -Last 60 | ForEach-Object { $_.Line } | Out-File (Join-Path $out 'app-log-tail.txt') -Encoding utf8

$crashCount = (Get-Content (Join-Path $out 'crashes.txt') | Measure-Object -Line).Lines
Write-Host "crash lines: $crashCount (see crashes.txt)"; Write-Host "cpu samples (process):"; Get-Content (Join-Path $out 'top.txt')
Write-Host "evidence dir: $out" -ForegroundColor Green
if (-not $KeepRunning) { Adb shell am force-stop $pkg | Out-Null; Adb emu kill | Out-Null }
