@echo off
rem
rem SqlJam launcher for Windows. It runs the runnable jar of Windows next to this script
rem with Java 17 or later, without a console window.
rem
rem   sqljam.bat sync --task NAME   runs a sync task in this console and returns its exit code,
rem                                 e.g. for the Task Scheduler of Windows
rem
rem JVM options are read from sqljam.vmoptions next to this script (one option per line),
rem JAVA_OPTS adds more and wins, e.g. set JAVA_OPTS=-Xmx4g. JAVA_HOME selects the Java runtime.
rem
rem Paths are only expanded outside of parenthesized blocks, so that paths with parentheses,
rem e.g. C:\Program Files (x86)\Java, do not end a block.
rem
setlocal
set "DIR=%~dp0"
set "JAR=%DIR%sqljam-@project.version@-win.jar"
if not exist "%JAR%" goto noJar

rem java.exe reports the version, javaw.exe starts SqlJam without a console window
set "JAVA=java.exe"
set "JAVAW=javaw.exe"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
if defined JAVA_HOME set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if defined JAVA_HOME goto checkJavaHome
where /q java.exe || goto noJava
where /q javaw.exe || goto noJava
goto checkVersion

:checkJavaHome
if not exist "%JAVA%" goto noJava
if not exist "%JAVAW%" goto noJava

:checkVersion
rem The first line is e.g. java version "17.0.12" 2024-07-16 or openjdk version "1.8.0_402"
set "JAVA_VERSION="
for /f "tokens=3" %%v in ('^""%JAVA%" -version 2^>^&1^"') do if not defined JAVA_VERSION set "JAVA_VERSION=%%~v"
set "JAVA_MAJOR=0"
for /f "delims=.-+_ " %%m in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%m"
set /a JAVA_MAJOR=JAVA_MAJOR 2>nul
if %JAVA_MAJOR% LSS 17 goto oldJava

set "VMOPTIONS="
if not exist "%DIR%sqljam.vmoptions" goto start
for /f "usebackq eol=# tokens=* delims=" %%o in ("%DIR%sqljam.vmoptions") do call set "VMOPTIONS=%%VMOPTIONS%% %%o"

:start
if /i "%~1"=="sync" goto sync
start "SqlJam" "%JAVAW%" %VMOPTIONS% -Dsqljam.vmoptions.applied=true %JAVA_OPTS% -jar "%JAR%" %*
endlocal
exit /b 0

:sync
rem Sync tasks run in the console, their output and exit code are kept
"%JAVA%" %VMOPTIONS% -Dsqljam.vmoptions.applied=true %JAVA_OPTS% -jar "%JAR%" %*
set "CODE=%ERRORLEVEL%"
endlocal & exit /b %CODE%

:noJar
echo Runnable jar not found: "%JAR%"
pause
exit /b 1

:noJava
echo Java 17 or later is required, install it or set JAVA_HOME
if defined JAVA_HOME echo JAVA_HOME: "%JAVA_HOME%"
pause
exit /b 1

:oldJava
echo Java 17 or later is required, found version %JAVA_VERSION% of "%JAVA%"
echo Install Java 17 or later, or set JAVA_HOME to it
pause
exit /b 1
