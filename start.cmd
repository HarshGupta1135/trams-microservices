@echo off
REM Windows entry point. Double-click, or run `start.cmd` from CMD/PowerShell.
REM
REM The setup logic lives in scripts/start.sh because the same script has to work
REM on macOS and Linux too. This wrapper just finds a bash to run it with - Git
REM for Windows ships one (along with openssl, which the TLS step needs), and WSL
REM works as a fallback.
REM
REM   start.cmd            start everything
REM   start.cmd --verify   start, then run the smoke suite

setlocal

set SCRIPT=scripts/start.sh

REM 1. bash already on PATH (Git Bash shell, MSYS2, or similar)
where bash >nul 2>nul
if %ERRORLEVEL% equ 0 (
    bash "%SCRIPT%" %*
    goto :end
)

REM 2. Git for Windows in its usual locations
if exist "%ProgramFiles%\Git\bin\bash.exe" (
    "%ProgramFiles%\Git\bin\bash.exe" "%SCRIPT%" %*
    goto :end
)
if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" (
    "%ProgramFiles(x86)%\Git\bin\bash.exe" "%SCRIPT%" %*
    goto :end
)
if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" (
    "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" "%SCRIPT%" %*
    goto :end
)

REM 3. WSL
where wsl >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo Using WSL...
    wsl bash "%SCRIPT%" %*
    goto :end
)

echo.
echo   Could not find bash to run the setup script.
echo.
echo   Install Git for Windows - it provides both bash and openssl:
echo       https://git-scm.com/download/win
echo.
echo   Then either re-run start.cmd, or open "Git Bash" here and run:
echo       bash scripts/start.sh
echo.
echo   Alternatively, if you would rather not use bash at all, the manual
echo   equivalent is documented in README.md under "Running it manually".
echo.
exit /b 1

:end
endlocal
