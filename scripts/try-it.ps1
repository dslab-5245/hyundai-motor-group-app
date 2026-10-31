# Try Drive Symphony by hand on the Pleos Connect emulator (with a window), then drive it from the Virtual Console.
#   powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\try-it.ps1 [-Demo] [-Apk path]
# What it does: boots the AVD `pleos_connect` if it is not running, installs the release APK, forwards the Virtual
# Console (host http://127.0.0.1:18088 -> guest 8088), opens the app and, with -Demo, starts the demo story playing.
param(
    [string]$Apk = "C:\Users\user\dev\drive-symphony\app\build\outputs\apk\release\app-release.apk",
    [switch]$Demo,
    [switch]$SoftwareGpu   # default is the host GPU: on this machine the whole app drops from ~12 % to ~5 % of a core and audio stays gapless
)
$ErrorActionPreference = 'Continue'
$sdk = 'C:\Users\user\dev-tools\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$pkg = 'io.drivesymphony.app'
$svc = "$pkg/.audio.SymphonyService"
$repo = Split-Path -Parent $PSScriptRoot
$env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk
function Adb { & $adb @args }

if (-not ((Adb devices) -match 'emulator-')) {
    Write-Host "== booting the Pleos emulator (window, cold boot: about 1-2 minutes)" -ForegroundColor Cyan
    $gpu = if ($SoftwareGpu) { 'swiftshader_indirect' } else { 'host' }
    Start-Process -FilePath $emu -ArgumentList @('-avd', 'pleos_connect', '-no-boot-anim', '-no-snapshot', '-gpu', $gpu, '-accel', 'auto', '-memory', '4096')
}
Adb wait-for-device | Out-Null
for ($i = 0; $i -lt 180; $i++) {
    if (((Adb shell getprop sys.boot_completed 2>$null) | Out-String).Trim() -eq '1') { break }
    Start-Sleep -Seconds 2
}
# The Pleos home puts the navigation app into the app slot a second or two after boot; an app launched before that
# ends up hidden behind it (blank grey pane). Launch, verify the pane really shows our UI (screenshot: the pane is a
# flat grey when another task covers us; our screens have text, ring and chips), relaunch while it does not.
function Ensure-Front([int]$tries = 5) {
    Add-Type -AssemblyName System.Drawing
    for ($k = 0; $k -lt $tries; $k++) {
        Adb shell am start -n "$pkg/.MainActivity" | Out-Null
        Start-Sleep -Seconds 4
        Adb shell screencap -p /sdcard/ds_front.png | Out-Null
        Adb pull /sdcard/ds_front.png "$env:TEMP\ds_front.png" | Out-Null
        $flat = $true
        try {
            $bmp = [System.Drawing.Bitmap]::FromFile("$env:TEMP\ds_front.png")
            $lo = 255; $hi = 0
            for ($x = 950; $x -lt 2500; $x += 80) { for ($y = 180; $y -lt 1200; $y += 85) {
                $c = $bmp.GetPixel($x, $y); $l = [int](0.3 * $c.R + 0.59 * $c.G + 0.11 * $c.B)
                if ($l -lt $lo) { $lo = $l }; if ($l -gt $hi) { $hi = $l }
            } }
            $bmp.Dispose()
            $flat = ($hi - $lo) -lt 10
        } catch { $flat = $false }
        if (-not $flat) { return $true }
        Write-Host "   app pane is flat grey (another task covers the app) -> relaunching" -ForegroundColor Yellow
        Start-Sleep -Seconds 3
    }
    Write-Host "   WARNING: the app pane still looks blank" -ForegroundColor Red
    return $false
}

Write-Host "== installing $Apk" -ForegroundColor Cyan
Adb install -r -g $Apk
Adb forward tcp:18088 tcp:8088 | Out-Null
Ensure-Front | Out-Null
if ($Demo) {
    Start-Sleep -Seconds 3
    Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on true --es scenario story | Out-Null
    Adb shell am startservice -n $svc -a io.drivesymphony.action.PLAY | Out-Null
}

Write-Host ""
Write-Host "Ready." -ForegroundColor Green
Write-Host "  App:              on the emulator screen (right pane). 작곡실 -> 연주 시작, or run this script with -Demo."
Write-Host "  Virtual Console:  http://127.0.0.1:18088  (browser; keep the tab open while you drive)"
Write-Host "  Or from a shell (repo = $repo):"
Write-Host "    python -I scripts\virtual-console.py --adb `"$adb`" set gear Drive"
Write-Host "    python -I scripts\virtual-console.py --adb `"$adb`" set steering -220      # then: set steering 0"
Write-Host "    python -I scripts\virtual-console.py --adb `"$adb`" set brake 70           # then: set brake 0 (3 taps = pump)"
Write-Host "    python -I scripts\virtual-console.py --adb `"$adb`" set turn BlinkLeft | wiper Low | cruise longitudinal | seat rear_left true"
Write-Host "    python -I scripts\virtual-console.py --adb `"$adb`" cue                    # the whole PT cue sheet, ~85 s"
Write-Host "  Gleo-style commands:"
Write-Host "    adb shell am startservice -n $svc -a howIsMyDriving"
Write-Host "    adb shell am startservice -n $svc -a setGenre --es ACTION_PARAM '{\`"genre\`":\`"LOFI\`"}'"
Write-Host "  Logs:  adb logcat -s SymphonyService PleosVehicleSource   (drive event / audio load / steering / brake)"
