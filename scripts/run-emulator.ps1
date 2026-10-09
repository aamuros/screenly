Write-Host "Starting Android emulator..." -ForegroundColor Cyan
$emulatorPath = "$env:ANDROID_HOME\emulator\emulator.exe"
Start-Process -FilePath $emulatorPath -ArgumentList "-avd", "Medium_Phone_API_37.0", "-no-snapshot"

Write-Host "Waiting for emulator to boot..." -ForegroundColor Yellow
adb wait-for-device
do {
    Start-Sleep -Seconds 3
    $bootComplete = adb shell getprop sys.boot_completed 2>$null
} while ($bootComplete.Trim() -ne "1")

Write-Host "Emulator booted! Installing Screenly..." -ForegroundColor Green
adb install -r "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk"
adb shell am start -n com.screenly.app/.MainActivity
Write-Host "Screenly is running!" -ForegroundColor Green
Read-Host "Press Enter to exit"
