@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION

REM ScoreZone self-bootstrapping Gradle launcher.
REM It uses the pinned version in gradle/wrapper/gradle-wrapper.properties.

SET "APP_HOME=%~dp0"
SET "PROPERTIES=%APP_HOME%gradle\wrapper\gradle-wrapper.properties"
SET "CACHE_DIR=%APP_HOME%.gradle-local"

IF NOT EXIST "%PROPERTIES%" (
  ECHO ERROR: Missing %PROPERTIES%
  EXIT /B 1
)

FOR /F "tokens=1,* delims==" %%A IN ('findstr /B "distributionUrl=" "%PROPERTIES%"') DO SET "DIST_URL=%%B"
SET "DIST_URL=%DIST_URL:\:=:%"
FOR %%A IN ("%DIST_URL%") DO SET "DIST_FILE=%%~nxA"
FOR /F "tokens=2 delims=-" %%A IN ("%DIST_FILE%") DO SET "DIST_VERSION=%%A"
SET "DIST_VERSION=%DIST_VERSION:-bin.zip=%"
SET "DIST_DIR=%CACHE_DIR%\gradle-%DIST_VERSION%"
SET "ZIP_FILE=%CACHE_DIR%\%DIST_FILE%"

IF EXIST "%DIST_DIR%\bin\gradle.bat" GOTO RUN

IF NOT EXIST "%CACHE_DIR%" MKDIR "%CACHE_DIR%"
IF NOT EXIST "%ZIP_FILE%" (
  ECHO Downloading pinned Gradle distribution: %DIST_URL%
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'"
  IF ERRORLEVEL 1 EXIT /B 1
)

IF EXIST "%DIST_DIR%.tmp" RMDIR /S /Q "%DIST_DIR%.tmp"
MKDIR "%DIST_DIR%.tmp"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP_FILE%' '%DIST_DIR%.tmp'"
IF ERRORLEVEL 1 EXIT /B 1
MOVE /Y "%DIST_DIR%.tmp\gradle-%DIST_VERSION%" "%DIST_DIR%" >NUL
RMDIR /S /Q "%DIST_DIR%.tmp"

:RUN
CALL "%DIST_DIR%\bin\gradle.bat" %*
SET EXIT_CODE=%ERRORLEVEL%
ENDLOCAL & EXIT /B %EXIT_CODE%
