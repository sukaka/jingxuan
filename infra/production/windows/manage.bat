@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%install.ps1"
set "EXIT_CODE=0"

if not exist "%PS_SCRIPT%" (
    echo [ERROR] Missing install.ps1 in "%SCRIPT_DIR%".
    set "EXIT_CODE=1"
    goto :maybe_pause
)

where powershell.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] powershell.exe was not found in PATH.
    set "EXIT_CODE=1"
    goto :maybe_pause
)

if "%~1"=="" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" menu
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
)
set "EXIT_CODE=%ERRORLEVEL%"

:maybe_pause
if defined WHALE_CRM_NO_PAUSE goto :done
setlocal EnableDelayedExpansion
set "LAUNCH_CMD=!CMDCMDLINE:"=!"
if /I not "!LAUNCH_CMD!"=="!LAUNCH_CMD:/c=!" (
    endlocal
    if not "%EXIT_CODE%"=="0" (
        echo.
        pause
    ) else if not "%~1"=="" (
        echo.
        pause
    )
) else (
    endlocal
)

:done
exit /b %EXIT_CODE%
