@echo off
cd /d "%~dp0"
echo ======================================================
echo   VR Sync Vigia - Instalador de Servico
echo ======================================================
echo.

:: Ler nome da loja do .env para usar no nome da tarefa
for /f "tokens=2 delims==" %%a in ('findstr /i "ENCARTE_LOJA" .env') do set LOJA_ID=%%a

if "%LOJA_ID%"=="" set LOJA_ID=loja

:: Remover tarefa agendada antiga (se existir)
schtasks /delete /tn "VR-Sync-Vigia-%LOJA_ID%" /f >nul 2>&1
echo Tarefa agendada antiga removida (se existia).
echo.

:: Criar tarefa que inicia o vigia no logon
echo Criando tarefa para iniciar vigia automaticamente...
set VIGIA_DIR=%~dp0
schtasks /create /tn "VR-Sync-Vigia-%LOJA_ID%" /tr "\"%VIGIA_DIR%executar-vigia.bat\"" /sc onlogon /rl highest /f
if %errorlevel% equ 0 (
    echo Tarefa criada! O vigia inicia automaticamente ao ligar o PC.
) else (
    echo AVISO: Falha ao criar tarefa. Crie manualmente no Agendador de Tarefas.
)

echo.
echo Para iniciar agora:  node vigia.js
echo O vigia consulta o servidor a cada 15 min.
echo Sync automatico a cada 6h ou sob demanda pelo site.
echo.
pause
