@echo off
cd /d "%~dp0"
echo ======================================================
echo   VR Sync v3.0 - Instalador
echo ======================================================
echo Pasta: %cd%
echo.

:: Verificar Node.js
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo ERRO: Node.js nao encontrado! Instale em https://nodejs.org
    pause
    exit /b 1
)

for /f "tokens=*" %%i in ('node -v') do set NODE_VER=%%i
echo Node.js: %NODE_VER%
echo.

:: Instalar dependencias
echo Instalando dependencias...
call npm install --omit=dev
if %errorlevel% neq 0 (
    echo ERRO: Falha ao instalar dependencias!
    pause
    exit /b 1
)
echo.

:: Verificar .env
if not exist .env (
    echo AVISO: Arquivo .env nao encontrado!
    echo Copie .env.example para .env e preencha com os dados da loja.
    echo.
    pause
    exit /b 1
)

:: Verificar conexao com o banco
echo Verificando conexao com o banco VR...
node verificar.js
if %errorlevel% neq 0 (
    echo.
    echo AVISO: Verifique as configuracoes no arquivo .env
    echo.
)

:: Criar pasta de logs
if not exist logs mkdir logs

echo.
echo ======================================================
echo   Instalacao concluida!
echo ======================================================
echo.
echo Para testar manualmente:  node sync.js
echo Para modo vigia:          node vigia.js
echo Logs em:                  %cd%\logs\
echo.
pause
