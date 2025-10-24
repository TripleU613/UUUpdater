@echo off
setlocal enabledelayedexpansion

REM Optional first argument: path to the Techeaz release APK.
set "APK_PATH=%~1"
if "%APK_PATH%"=="" set "APK_PATH=%USERPROFILE%\StudioProjects\UUUpdater\app\techeaz\release\com.tripleu.updater-techeaz-release.apk"

REM Optional second argument: path to the priv-app permissions XML.
set "PERMS_PATH=%~2"
if "%PERMS_PATH%"=="" set "PERMS_PATH=%USERPROFILE%\StudioProjects\UUUpdater\com.tripleu.updater-permissions.xml"

if not exist "%APK_PATH%" (
    echo [ERROR] APK not found at "%APK_PATH%".
    exit /b 1
)

if not exist "%PERMS_PATH%" (
    echo [ERROR] Permissions XML not found at "%PERMS_PATH%".
    exit /b 1
)

echo [INFO] Using APK: "%APK_PATH%"
echo [INFO] Using permissions XML: "%PERMS_PATH%"

echo [INFO] Checking ADB connection...
adb get-state 1>nul 2>nul
if errorlevel 1 (
    echo [ERROR] No device detected via ADB.
    exit /b 1
)

echo [INFO] Pushing files to /sdcard/ ...
adb push "%APK_PATH%" /sdcard/UUUpdater.apk 1>nul
if errorlevel 1 (
    echo [ERROR] Failed to push APK to device.
    exit /b 1
)

adb push "%PERMS_PATH%" /sdcard/com.tripleu.updater-permissions.xml 1>nul
if errorlevel 1 (
    echo [ERROR] Failed to push permissions XML to device.
    exit /b 1
)

echo [INFO] Applying privileged installer files (requires root)...
adb shell su -c "mount -o remount,rw / || mount -o rw,remount /"
if errorlevel 1 (
    echo [ERROR] Unable to remount system as read-write.
    exit /b 1
)

adb shell su -c "mkdir -p /system/etc/permissions /system/priv-app/UUUpdater"
adb shell su -c "cp /sdcard/com.tripleu.updater-permissions.xml /system/etc/permissions/com.tripleu.updater-permissions.xml"
adb shell su -c "cp /sdcard/UUUpdater.apk /system/priv-app/UUUpdater/UUUpdater.apk"
adb shell su -c "chmod 0644 /system/etc/permissions/com.tripleu.updater-permissions.xml /system/priv-app/UUUpdater/UUUpdater.apk"
adb shell su -c "chown root:root /system/etc/permissions/com.tripleu.updater-permissions.xml /system/priv-app/UUUpdater/UUUpdater.apk"
adb shell su -c "restorecon /system/etc/permissions/com.tripleu.updater-permissions.xml"
adb shell su -c "restorecon /system/priv-app/UUUpdater/UUUpdater.apk"

adb shell su -c "mount -o remount,ro / || mount -o ro,remount /"

echo [INFO] Rebooting device to apply changes...
adb reboot

echo [INFO] Done.
exit /b 0
