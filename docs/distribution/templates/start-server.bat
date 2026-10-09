@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found. Install 64-bit Java 25 or newer.
  pause
  exit /b 1
)

echo [MORROWGEAR] Java version:
java -version
echo.
echo [MORROWGEAR] Starting Minecraft 26.2 Fabric server...
java -Xms2G -Xmx6G -jar fabric-server-mc.26.2-loader.0.19.3-launcher.1.1.2.jar nogui

echo.
echo [MORROWGEAR] Server process ended. Review the messages above before closing.
pause

