# Run this ONCE yourself (it accepts the Google Android SDK and 42dot Pleos Connect SDK license agreements):
#   powershell -ExecutionPolicy Bypass -File C:\Users\user\dev\drive-symphony\scripts\accept-sdk-licenses.ps1
# Prerequisite (already done by the setup): cmdline-tools extracted to C:\Users\user\dev-tools\android-sdk\cmdline-tools\latest
#   and ~/.android/repositories.cfg registers the Pleos update site, so BOTH licenses are presented here.
$env:JAVA_HOME = 'C:\Users\user\dev-tools\jdk-extract\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$sdk = 'C:\Users\user\dev-tools\android-sdk'
$sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
# sdkmanager asks "Accept? (y/N)" once per unaccepted license; feeding 'y' lines answers them all.
1..30 | ForEach-Object { 'y' } | & $sdkmanager --sdk_root=$sdk --licenses
Write-Host "`nAccepted license hashes:" -ForegroundColor Green
Get-ChildItem "$sdk\licenses" | Select-Object Name, Length
