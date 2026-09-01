@echo off

setlocal
cd /d "%~dp0"

if exist "C:\Source\Apps\openjdk18" set "JAVA_HOME=C:\Source\Apps\openjdk18"

call gradlew.bat assembleRelease --warning-mode all
if errorlevel 1 goto :err

adb install "app\build\outputs\apk\release\app-release.apk"
exit 0


:err
echo.
echo *** BUILD FAILED - see output above ***
exit /b 1
