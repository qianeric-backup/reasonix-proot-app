@echo off
REM ============================================================
REM  Reasonix Proot —— 一键构建 APK
REM  前置要求:
REM    - JDK 17 (Temurin)
REM    - Android SDK (platforms;android-34, build-tools;34.0.0)
REM    - 修改下方两个路径指向你的安装位置
REM ============================================================
setlocal

set JAVA_HOME=C:\Users\qianeric\AppData\Local\Temp\resonix-apk\tools\jdk-17.0.20+8
set ANDROID_HOME=C:\Users\qianeric\AppData\Local\Temp\resonix-apk\tools\sdk
set PATH=%JAVA_HOME%\bin;%PATH%

call gradlew.bat assembleDebug

if exist app\build\outputs\apk\debug\app-debug.apk (
    copy /Y app\build\outputs\apk\debug\app-debug.apk ..\dist\reasonix-proot.apk >nul
    echo.
    echo [OK] APK 已生成: ..\dist\reasonix-proot.apk
) else (
    echo [FAIL] 构建失败，请检查上方日志
)
endlocal
