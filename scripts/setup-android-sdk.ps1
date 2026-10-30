# Installs everything needed to build the app and run the Pleos Connect emulator headless.
# Needs the licenses accepted first (scripts/accept-sdk-licenses.ps1). No admin rights required.
#   powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\setup-android-sdk.ps1
$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'C:\Users\user\dev-tools\jdk-extract\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$sdk = 'C:\Users\user\dev-tools\android-sdk'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$avdmanager = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"

Write-Host "== 1/3 SDK packages (platform 35 for compileSdk, build-tools 35.0.0, platform-tools/adb, emulator)" -ForegroundColor Cyan
& $sdkmanager --sdk_root=$sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator"

Write-Host "== 2/3 Pleos Connect v2.1.0 system image (API 34, x86_64) from the registered update site" -ForegroundColor Cyan
& $sdkmanager --sdk_root=$sdk "system-images;android-34;pleos_car_emulator;x86_64"

Write-Host "== 3/3 AVD 'pleos_connect' (Automotive form factor, cold boot, 4 GB RAM)" -ForegroundColor Cyan
$avdHome = "$env:USERPROFILE\.android\avd"
if (-not (Test-Path "$avdHome\pleos_connect.avd")) {
    'no' | & $avdmanager create avd --name pleos_connect --package "system-images;android-34;pleos_car_emulator;x86_64" --device "automotive_1024p_landscape" --force
}
$cfg = "$avdHome\pleos_connect.avd\config.ini"
if (Test-Path $cfg) {
    $ini = Get-Content $cfg | Where-Object { $_ -notmatch '^(hw\.ramSize|hw\.lcd\.width|hw\.lcd\.height|hw\.lcd\.density|disk\.dataPartition\.size|fastboot\.forceColdBoot|hw\.keyboard|hw\.gpu\.enabled|hw\.gpu\.mode)=' }
    $ini += @('hw.ramSize=4096', 'hw.lcd.width=2560', 'hw.lcd.height=1440', 'hw.lcd.density=240', 'disk.dataPartition.size=6G', 'fastboot.forceColdBoot=yes', 'hw.keyboard=yes', 'hw.gpu.enabled=yes', 'hw.gpu.mode=swiftshader_indirect')
    Set-Content -Path $cfg -Value $ini -Encoding ascii
    Write-Host "config.ini updated:"; Get-Content $cfg | Select-String 'ramSize|lcd|ColdBoot|gpu'
}
Write-Host "`nInstalled:" -ForegroundColor Green
& $sdkmanager --sdk_root=$sdk --list_installed
