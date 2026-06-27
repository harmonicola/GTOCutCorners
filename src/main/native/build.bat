@echo off
echo ========================================
echo  GTOCutCorners Native Patcher Builder
echo ========================================
echo.

REM Find Visual Studio
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -property installationPath 2^>nul`) do set "VS_PATH=%%i"

if not defined VS_PATH (
    echo Visual Studio not found.
    echo Manual compile:
    echo   cl /LD /Fe:gtocutcorners_patch.dll patcher.c /I"%%JAVA_HOME%%\include" /I"%%JAVA_HOME%%\include\win32"
    pause
    exit /b 1
)

call "%VS_PATH%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1

echo Compiling patcher.c ...
cl /LD /Fe:gtocutcorners_patch.dll patcher.c /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /nologo /O2

if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED
    pause
    exit /b 1
)

REM Copy to JAR resources
if exist "..\..\resources\native\" (
    copy /Y gtocutcorners_patch.dll "..\..\resources\native\" >nul
    echo.
    echo ========================================
    echo   BUILD SUCCESS
    echo   DLL copied to src\main\resources\native\
    echo   Now run: gradlew jar
    echo ========================================
) else (
    echo.
    echo ========================================
    echo   BUILD SUCCESS - gtocutcorners_patch.dll
    echo   Copy to: src\main\resources\native\
    echo   Then run: gradlew jar
    echo ========================================
)
