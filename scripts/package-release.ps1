# Builds the contest submission APK and runs every pre-submission check.
#   powershell -ExecutionPolicy Bypass -File scripts\package-release.ps1 -Team "<팀명>"
# Output: apk\<Team>_DriveSymphony_release.apk (+ .sha256). Needs the Android SDK (local.properties) and JDK 17 on PATH
# or in $env:JAVA_HOME; Gradle wrapper is used.
param(
    [Parameter(Mandatory = $true)][string]$Team,
    [switch]$SkipTests
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Step($name) { Write-Host "`n== $name" -ForegroundColor Cyan }

Step "Blind-review check: commit authors"
$authors = (& git log --format='%an <%ae>' --all | Sort-Object -Unique)
if (@($authors).Count -ne 1 -or $authors -ne 'Drive Symphony <dev@drivesymphony.local>') {
    Write-Error "commit authors must be exactly 'Drive Symphony <dev@drivesymphony.local>' (found: $($authors -join '; '))"
}
Write-Host "ok: $authors"

Step "Consistency check (schema / manifest / permissions / tests / e-mail scan)"
& python -I scripts\check_consistency.py
if ($LASTEXITCODE -ne 0) { Write-Error "check_consistency.py failed" }

if (-not $SkipTests) {
    Step "Engine unit tests"
    & .\gradlew.bat :engine:test
    if ($LASTEXITCODE -ne 0) { Write-Error "engine tests failed" }
}

Step "Release APK"
& .\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) { Write-Error "assembleRelease failed" }
$built = Get-ChildItem "app\build\outputs\apk\release\*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $built) { Write-Error "no APK under app\build\outputs\apk\release" }

$safeTeam = ($Team -replace '[\\/:*?"<>| ]', '_')
New-Item -ItemType Directory -Force "apk" | Out-Null
$target = "apk\${safeTeam}_DriveSymphony_release.apk"
Copy-Item $built.FullName $target -Force
$hash = (Get-FileHash $target -Algorithm SHA256).Hash.ToLower()
"$hash  $(Split-Path -Leaf $target)" | Out-File "$target.sha256" -Encoding ascii
Write-Host "wrote $target ($([math]::Round($built.Length / 1MB, 2)) MB) sha256 $hash"

Step "Signature"
$apksigner = Get-ChildItem "$env:ANDROID_HOME\build-tools\*\apksigner.bat" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $apksigner -and (Test-Path "local.properties")) {
    $sdk = (Get-Content local.properties | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', '' -replace '\\:', ':' -replace '\\\\', '\'
    if ($sdk) { $apksigner = Get-ChildItem "$sdk\build-tools\*\apksigner.bat" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 }
}
if ($apksigner) {
    & $apksigner.FullName verify --print-certs $target
    if ($LASTEXITCODE -ne 0) { Write-Error "apksigner verify failed" }
} else {
    Write-Host "apksigner not found; skipped signature verification (the release build is signed with the debug key per app/build.gradle.kts)"
}

Step "Done"
Write-Host "Submit $target. Fill the ⟦ ⟧ placeholders in the proposal and store/listing.md before uploading."
