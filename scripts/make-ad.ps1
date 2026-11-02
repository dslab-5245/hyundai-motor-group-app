# Renders the ad-grade videos for one capture run (output of scripts/make-demo-video.ps1):
#   ad_16x9.mp4 (cinematic 16:9, ~78 s), short_9x16.mp4 (vertical, ~49 s), demo_full_16x9.mp4 (narrated full demo, ~3.9 min)
#   + narration.wav, events.json, plan.json, contact_sheet.png, event_frames_*.png, render.log
# Pipeline (video/ad, Remotion): stage run files -> extract events from logcat/timing/cue -> Korean voice-over (edge-tts)
#   -> fetch b-roll + fonts (first time only) -> build the timelines -> render 3 compositions -> loudnorm -16 LUFS / -1.5 dBTP
#   -> contact sheet + event frames. Re-runnable unattended on any run folder; every run-specific number is read from the run.
#   powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\make-ad.ps1 -Run C:\Users\user\dev\drive-symphony\build\video\<ts> [-Out <dir>] [-Only Ad16x9,Short9x16] [-SkipNarration] [-Concurrency 6]
param(
    [Parameter(Mandatory = $true)][string]$Run,
    [string]$Out = "",
    [string[]]$Only = @("Ad16x9", "Short9x16", "DemoFull16x9"),
    [switch]$SkipNarration,
    [switch]$SkipRender,
    [string]$Voice = "",
    [int]$Concurrency = 0
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$proj = Join-Path $repo 'video\ad'
$pub = Join-Path $proj 'public'
$Run = (Resolve-Path $Run).Path
if (-not $Out) { $Out = Join-Path $repo ("build\ad\" + (Split-Path -Leaf $Run)) }
New-Item -ItemType Directory -Force $Out | Out-Null
$log = Join-Path $Out 'render.log'
function Step([string]$msg) { Write-Host "== $msg" -ForegroundColor Cyan; Add-Content -Encoding utf8 $log ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $msg) }
function Py([string[]]$a) { & python -I @a; if ($LASTEXITCODE -ne 0) { throw "python $($a[0]) failed ($LASTEXITCODE)" } }
$outputs = @{ Ad16x9 = 'ad_16x9.mp4'; Short9x16 = 'short_9x16.mp4'; DemoFull16x9 = 'demo_full_16x9.mp4' }

foreach ($f in 'story_clean.mp4', 'logcat.txt') { if (-not (Test-Path (Join-Path $Run $f))) { throw "run folder lacks $f" } }

# 0. project dependencies (first run only)
Push-Location $proj
try {
    if (-not (Test-Path (Join-Path $proj 'node_modules\remotion'))) { Step 'npm install'; & npm install --no-audit --no-fund | Tee-Object -Append $log | Out-Null }
    if (-not (Test-Path (Join-Path $pub 'fonts\Pretendard-Bold.otf'))) {
        Step 'fetching Pretendard (OFL)'
        $tmp = Join-Path $env:TEMP ("pretendard-" + [guid]::NewGuid().ToString('n')); New-Item -ItemType Directory -Force $tmp | Out-Null
        try {
            Invoke-WebRequest -Uri 'https://github.com/orioncactus/pretendard/releases/download/v1.3.9/Pretendard-1.3.9.zip' -OutFile (Join-Path $tmp 'p.zip')
            Expand-Archive -Path (Join-Path $tmp 'p.zip') -DestinationPath (Join-Path $tmp 'x') -Force
            New-Item -ItemType Directory -Force (Join-Path $pub 'fonts') | Out-Null
            foreach ($w in 'Regular', 'Medium', 'SemiBold', 'Bold', 'ExtraBold') { Copy-Item (Join-Path $tmp "x\public\static\Pretendard-$w.otf") (Join-Path $pub 'fonts') -Force }
            Copy-Item (Join-Path $tmp 'x\LICENSE.txt') (Join-Path $pub 'fonts\LICENSE.txt') -Force
        } catch { Write-Host "   Pretendard download failed ($_); the render falls back to Noto Sans KR / Malgun Gothic" -ForegroundColor Yellow }
        finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
    }
    # 1. stage the run's media + grain texture
    Step "staging $Run"
    Py @("$proj\tools\stage.py", $Run, $pub)
    # 2. events (logcat -> clean-video seconds; badge from sync.json / detection / timing.json)
    Step 'extracting events'
    Py @("$proj\tools\events.py", $Run, "$pub\run\events.json")
    Copy-Item "$pub\run\events.json" (Join-Path $Out 'events.json') -Force
    # 3. narration
    if (-not $SkipNarration) {
        Step 'narration (edge-tts)'
        $na = @("$proj\tools\narration.py", "$pub\run"); if ($Voice) { $na += @('--voice', $Voice) }
        & python -I @na
        if ($LASTEXITCODE -eq 2) { Write-Host '   no TTS voice available: rendering without narration' -ForegroundColor Yellow }
        elseif ($LASTEXITCODE -ne 0) { throw 'narration failed' }
        if (Test-Path "$pub\run\narration.wav") { Copy-Item "$pub\run\narration.wav" (Join-Path $Out 'narration.wav') -Force }
    } elseif (-not (Test-Path "$pub\run\narration.json")) { '{"voice": null, "lines": []}' | Out-File -Encoding ascii "$pub\run\narration.json" }
    # 4. b-roll (downloads once; rejects restricted clips)
    Step 'b-roll'
    Py @("$proj\tools\broll.py", $pub)
    # 5. timelines
    Step 'building timelines'
    Py @("$proj\tools\plan.py", $pub, "$pub\run\plan.json")
    Copy-Item "$pub\run\plan.json" (Join-Path $Out 'plan.json') -Force
    # 6. render + loudness
    if (-not $SkipRender) {
        foreach ($comp in $Only) {
            $raw = Join-Path $Out ("raw_" + $outputs[$comp]); $final = Join-Path $Out $outputs[$comp]
            Step "rendering $comp"
            $ra = @('remotion', 'render', 'src/index.ts', $comp, $raw, '--codec', 'h264', '--crf', '18', '--audio-bitrate', '256k', '--pixel-format', 'yuv420p', '--log', 'warn', '--gl', 'angle')
            if ($Concurrency -gt 0) { $ra += @('--concurrency', "$Concurrency") }
            & npx @ra | Tee-Object -Append $log | Select-Object -Last 3
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $raw)) { throw "render of $comp failed (see $log)" }
            Step "loudness $comp"
            Py @("$proj\tools\post.py", 'finalize', $raw, $final)
            Remove-Item $raw -Force
        }
        if ($Only -contains 'Ad16x9') {
            Step 'contact sheet + event frames'
            Py @("$proj\tools\post.py", 'sheet', (Join-Path $Out 'ad_16x9.mp4'), (Join-Path $Out 'contact_sheet.png'), '12')
        }
        foreach ($comp in $Only) { Py @("$proj\tools\post.py", 'events', (Join-Path $Out $outputs[$comp]), (Join-Path $Out 'plan.json'), $comp, (Join-Path $Out ("event_frames_" + $comp + ".png"))) }
    }
} finally { Pop-Location }
Step "done: $Out"
Get-ChildItem $Out -Filter *.mp4 | ForEach-Object { Write-Host ("   {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
