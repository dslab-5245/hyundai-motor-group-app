# Records the demo-video raw material on the Pleos emulator and (unless -NoSync) assembles the videos:
#   * the 106 s demo story: screen (adb screenrecord, 1080p) + gapless engine audio (in-app WAV tap, RECORD action)
#   * unless -SkipConsole: an ~85 s Virtual Console segment (real vehicle signals through the SDK, no demo)
# then scripts/video_sync.py aligns audio to video (the REC badge is the clapperboard), writes Korean captions,
# title cards and cuts a 16:9 demo video plus a 9:16 short. Output: build/video/<timestamp>/ (see README.txt there).
#   powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\make-demo-video.ps1 [-Apk path] [-SkipConsole] [-Window] [-KeepRunning] [-NoSync]
param(
    [string]$Apk = "C:\Users\user\dev\drive-symphony\app\build\outputs\apk\release\app-release.apk",
    [string]$Out = "",
    [switch]$SkipConsole,
    [switch]$Window,
    [switch]$SoftwareGpu,
    [switch]$KeepRunning,
    [switch]$NoSync,
    [string]$Genre = 'LOFI'   # the app remembers the last genre (e.g. the smoke's Gleo JAZZ step); the video uses this one
)
$ErrorActionPreference = 'Continue'
$sdk = 'C:\Users\user\dev-tools\android-sdk'
$adb = "$sdk\platform-tools\adb.exe"
$emu = "$sdk\emulator\emulator.exe"
$pkg = 'io.drivesymphony.app'
$svc = "$pkg/.audio.SymphonyService"
$repo = Split-Path -Parent $PSScriptRoot
$env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk
if (-not $Out) { $Out = Join-Path $repo ("build\video\" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $Out | Out-Null
function Adb { & $adb @args }
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
# The app runs as a secondary user on Pleos (10): adb cannot read that user's files, so the service publishes the WAV
# to MediaStore Downloads and we stream it out through the content provider (cmd redirection keeps it binary-safe).
function Pull-Recording([string]$name, [string]$dest) {
    $user = ((Adb shell am get-current-user) | Out-String).Trim()
    if (-not $user) { $user = '0' }
    # no --where: its quoting does not survive PowerShell -> adb -> shell; the list is short, match the name here.
    # MediaStore keeps earlier rows of the same name (a stale 0-byte one made the pull empty once) and names the
    # new file 'ds_remix.wav (1)': accept the numbered suffix and take the newest row (highest _id).
    $base = [regex]::Escape([IO.Path]::GetFileNameWithoutExtension($name)); $ext = [regex]::Escape([IO.Path]::GetExtension($name))
    $row = (Adb shell content query --user $user --uri content://media/external/downloads --projection _id:_display_name 2>$null | Where-Object { $_ -match "_display_name=$base( \(\d+\))?$ext\b" } | Sort-Object { if ($_ -match '_id=(\d+)') { [long]$Matches[1] } else { 0 } } | Select-Object -Last 1 | Out-String)
    if ($row -match '_id=(\d+)') {
        $id = $Matches[1]
        & cmd /c "`"$adb`" exec-out content read --user $user --uri content://media/external/downloads/$id > `"$dest`""
    } else { Write-Host "   recording '$name' not found in MediaStore (user $user): $row" -ForegroundColor Yellow }
}
$timing = @{}

# The Pleos home puts the navigation app into the app slot a second or two after boot; an app launched before that
# ends up hidden behind it. Launch, then check that OUR activity is the slot's resumed one (the first
# `topResumedActivity=` line of `dumpsys activity activities` is the app-slot display); relaunch while it is not.
function Ensure-Front([int]$tries = 6) {
    for ($k = 0; $k -lt $tries; $k++) {
        Adb shell am start -n "$pkg/.MainActivity" | Out-Null
        Start-Sleep -Seconds 4
        $top = (Adb shell dumpsys activity activities 2>$null | Select-String -Pattern 'topResumedActivity=' | Select-Object -First 1 | Out-String).Trim()
        if ($top -match 'io\.drivesymphony\.app') { return $true }
        Write-Host "   another task is in front ($top) -> relaunching" -ForegroundColor Yellow
        Start-Sleep -Seconds 3
    }
    Write-Host "   WARNING: the app is still not the resumed activity of the app slot" -ForegroundColor Red
    return $false
}

if (-not ((Adb devices) -match 'emulator-')) {
    Write-Host "== booting the Pleos emulator (cold boot, about 1-2 minutes)" -ForegroundColor Cyan
    $gpu = if ($SoftwareGpu) { 'swiftshader_indirect' } else { 'host' }
    $bootArgs = @('-avd', 'pleos_connect', '-no-boot-anim', '-no-snapshot', '-gpu', $gpu, '-accel', 'auto', '-memory', '4096')
    if (-not $Window) { $bootArgs += '-no-window' }
    Start-Process -FilePath $emu -ArgumentList $bootArgs -WindowStyle Hidden
}
Adb wait-for-device | Out-Null
for ($i = 0; $i -lt 180; $i++) {
    if (((Adb shell getprop sys.boot_completed 2>$null) | Out-String).Trim() -eq '1') { break }
    Start-Sleep -Seconds 2
}
Start-Sleep -Seconds 5
Write-Host "== installing $Apk" -ForegroundColor Cyan
Adb install -r -g $Apk | Out-Null
Adb forward tcp:18088 tcp:8088 | Out-Null
Adb logcat -c | Out-Null
if (-not (Ensure-Front)) { Write-Host "app not visible; aborting" -ForegroundColor Red; exit 3 }
# fixed genre for the video (Gleo setGenre action; --% passes the JSON extra untouched)
$genreArg = '{\"genre\":\"' + $Genre + '\"}'
& $adb shell am startservice -n $svc -a setGenre --es ACTION_PARAM "'$genreArg'" | Out-Null
Start-Sleep -Seconds 2

# One recorded segment: screenrecord running, then the WAV tap (REC badge = sync mark), then the action, then both stopped.
function Record-Segment([string]$name, [int]$limitSec, [scriptblock]$action) {
    $mp4 = "/sdcard/Download/ds_$name.mp4"
    $wav = "/sdcard/Android/data/$pkg/files/ds_$name.wav"
    Adb shell rm -f $mp4 | Out-Null
    Write-Host "== segment '$name': screenrecord (limit $limitSec s)" -ForegroundColor Cyan
    $rec = Start-Process -FilePath $adb -ArgumentList @('shell', 'screenrecord', '--size', '1920x1080', '--bit-rate', '16000000', '--time-limit', "$limitSec", $mp4) -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 3
    $timing["${name}_record"] = Now
    Adb shell am startservice -n $svc -a io.drivesymphony.action.RECORD --ez on true --es path $wav | Out-Null
    & $action
    Adb shell am startservice -n $svc -a io.drivesymphony.action.RECORD --ez on false --es path $wav | Out-Null   # off + publish to MediaStore
    $timing["${name}_record_off"] = Now
    Start-Sleep -Seconds 2
    Adb shell pkill -INT screenrecord | Out-Null      # SIGINT lets screenrecord finalise the mp4
    $rec.WaitForExit(30000) | Out-Null
    Start-Sleep -Seconds 2
    Adb pull $mp4 "$Out\ds_$name.mp4" | Out-Null
    Pull-Recording "ds_$name.wav" "$Out\ds_$name.wav"
    Get-Item "$Out\ds_$name.mp4", "$Out\ds_$name.wav" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ("   {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
}

# ---- segment 1: the demo story (106 s) + journey card
Record-Segment 'story' 125 {
    $timing['story_demo'] = Now
    Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on true --es scenario story | Out-Null
    $timing['story_play'] = Now
    Adb shell am startservice -n $svc -a io.drivesymphony.action.PLAY | Out-Null
    Start-Sleep -Seconds 114
}

# ---- segment 2: real signals from the Virtual Console (demo off), the PT cue sheet compressed to ~85 s
if (-not $SkipConsole) {
    Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on false | Out-Null
    Start-Sleep -Seconds 4
    Record-Segment 'console' 100 {
        $timing['console_cue'] = Now
        & python -u -I "$repo\scripts\virtual-console.py" --adb $adb cue 2>&1 | ForEach-Object { "$(Now) $_" } | Out-File -Encoding utf8 "$Out\cue.log"
        Start-Sleep -Seconds 5
    }
}

# ---- segment 3: remix mode on the bundled sample track (public-domain piano), driven by the same demo story
Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on false | Out-Null
Adb shell am startservice -n $svc -a io.drivesymphony.action.REMIX_DEMO --ez on true | Out-Null
Start-Sleep -Seconds 4
Record-Segment 'remix' 60 {
    $timing['remix_demo'] = Now
    Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on true --es scenario story | Out-Null
    Adb shell am startservice -n $svc -a io.drivesymphony.action.PLAY | Out-Null
    Start-Sleep -Seconds 46
}
Adb shell am startservice -n $svc -a io.drivesymphony.action.DEMO --ez on false | Out-Null
Adb shell am startservice -n $svc -a io.drivesymphony.action.REMIX_DEMO --ez on false | Out-Null

Adb logcat -d -s SymphonyService AudioTap AudioOutput PleosVehicleSource LocalTrackPlayer | Out-File -Encoding utf8 "$Out\logcat.txt"
$timing | ConvertTo-Json | Out-File -Encoding ascii "$Out\timing.json"
if (-not $KeepRunning) { Adb shell am force-stop $pkg | Out-Null; Adb emu kill | Out-Null }

if (-not $NoSync) {
    Write-Host "== sync, captions, cuts (scripts/video_sync.py)" -ForegroundColor Cyan
    & python -I "$repo\scripts\video_sync.py" $Out
}
Write-Host "Done: $Out" -ForegroundColor Green
