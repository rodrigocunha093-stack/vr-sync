@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
title VR Sync - Instalador

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0instalar.ps1"
set "CODIGO=%ERRORLEVEL%"

echo.
if "%CODIGO%"=="0" (
    echo Instalacao concluida com sucesso.
) else (
    echo Instalacao nao concluida. Codigo de erro: %CODIGO%
)
echo.
pause
exit /b %CODIGO%
