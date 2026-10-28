@echo off
REM JMine KTX Bridge Build Script
REM Compiles the Java KTX bridge and creates the JAR file

setlocal

REM Find Java compiler - need full JDK with javac, not custom JRE
set JAVA_HOME=%JMINE_JAVA_HOME%
if not defined JAVA_HOME (
    REM Try to find GraalVM JDK extracted for build
    if exist "..\graalvm-build\bin\javac.exe" (
        set "JAVA_HOME=..\graalvm-build"
    ) else if exist "..\graalvm-cache\graalvm-jdk\bin\javac.exe" (
        set "JAVA_HOME=..\graalvm-cache\graalvm-jdk"
    ) else (
        echo Error: Need full JDK with javac to compile the KTX bridge.
        echo Run: python script.py (extracts GraalVM) then retry.
        echo Or set JMINE_JAVA_HOME to a JDK path.
        exit /b 1
    )
)

set JAVAC=%JAVA_HOME%\bin\javac.exe
set JAR=%JAVA_HOME%\bin\jar.exe

if not exist "%JAVAC%" (
    echo Error: javac not found at %JAVAC%
    exit /b 1
)

echo ============================================
echo JMine KTX Bridge Build
echo ============================================
echo.

REM Create output directory
set OUT_DIR=build
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"

REM Compile all Java files
echo Compiling Java sources...
set SRC_DIR=src\main\java
set CLASS_FILES=DDSHeader.java DDSCompressor.java ResourcePackScanner.java TextureCache.java TextureAgent.java

"%JAVAC%" --release 25 -d "%OUT_DIR%" %SRC_DIR%\com\jmine\texture\*.java %SRC_DIR%\com\jmine\perf\*.java

if errorlevel 1 (
    echo Error: Compilation failed
    exit /b 1
)

echo Compilation successful.

REM Create JAR with manifest
echo Creating JAR...
"%JAR%" cfm jmine-ktx-bridge.jar META-INF\MANIFEST.MF -C "%OUT_DIR%" com

if errorlevel 1 (
    echo Error: JAR creation failed
    exit /b 1
)

echo.
echo ============================================
echo Build complete: jmine-ktx-bridge.jar
echo ============================================
echo.
echo To use:
echo   -javaagent:jmine-ktx-bridge.jar
echo.
echo The bridge will automatically scan resource packs and compress textures via KTX.
