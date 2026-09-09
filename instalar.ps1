[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$TaskName = 'VR Sync - Encarte Inteligente'
$InstallDir = Join-Path $env:ProgramData 'VR Sync'
$RuntimeFiles = @(
    'package.json',
    'package-lock.json',
    'config.js',
    'sync.js',
    'verificar.js',
    'executar.ps1',
    '.env.example'
)

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Read-MaskedSecret([string]$Prompt) {
    # Entrada de segredo: mascara na tela com '*', aceita colar (Ctrl+V/clique)
    # e nao depende de Read-Host -AsSecureString (que bloqueia colagem em varios terminais).
    Write-Host $Prompt -NoNewline
    $buffer = ''
    while ($true) {
        $key = [System.Console]::ReadKey($true)
        if ($key.Key -eq [ConsoleKey]::Enter) {
            Write-Host ''
            break
        }
        if ($key.Key -eq [ConsoleKey]::Backspace) {
            if ($buffer.Length -gt 0) {
                $buffer = $buffer.Substring(0, $buffer.Length - 1)
                Write-Host "`b `b" -NoNewline
            }
            continue
        }
        $isPasteShortcut = $key.Key -eq [ConsoleKey]::V -and ($key.Modifiers -band [ConsoleModifiers]::Control)
        if ($isPasteShortcut) {
            $paste = ''
            try { $paste = Get-Clipboard -Raw -ErrorAction Stop } catch { $paste = '' }
            if ($paste) {
                $buffer += $paste
                Write-Host ('*' * $paste.Length) -NoNewline
            }
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($key.KeyChar)) {
            $buffer += $key.KeyChar
            Write-Host '*' -NoNewline
        }
    }
    return $buffer
}

function Read-Required([string]$Prompt, [string]$Default = '') {
    while ($true) {
        $suffix = if ($Default) { " [$Default]" } else { '' }
        $value = Read-Host "$Prompt$suffix"
        if ([string]::IsNullOrWhiteSpace($value)) { $value = $Default }
        if (-not [string]::IsNullOrWhiteSpace($value)) { return $value.Trim() }
        Write-Host 'Este valor e obrigatorio.' -ForegroundColor Yellow
    }
}

function Read-Integer([string]$Prompt, [int]$Default, [int]$Min, [int]$Max) {
    while ($true) {
        $text = Read-Host "$Prompt [$Default]"
        if ([string]::IsNullOrWhiteSpace($text)) { return $Default }
        $number = 0
        if ([int]::TryParse($text, [ref]$number) -and $number -ge $Min -and $number -le $Max) {
            return $number
        }
        Write-Host "Informe um numero entre $Min e $Max." -ForegroundColor Yellow
    }
}

function Get-NodeInfo {
    $node = Get-Command node.exe -ErrorAction SilentlyContinue
    $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $node -or -not $npm) { return $null }

    $version = (& $node.Source --version).TrimStart('v')
    $major = 0
    if (-not [int]::TryParse($version.Split('.')[0], [ref]$major)) { return $null }
    return [pscustomobject]@{ Node = $node.Source; Npm = $npm.Source; Version = $version; Major = $major }
}

function Protect-EnvironmentFile([string]$Path) {
    & icacls.exe $Path /inheritance:r /grant:r 'SYSTEM:(F)' '*S-1-5-32-544:(F)' "$($env:USERNAME):(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Nao foi possivel proteger o arquivo .env.' }
}

function Remove-TaskIfPresent {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue | Out-Null
    }
}

function Install-ScheduledTask {
    $runner = Join-Path $InstallDir 'executar.ps1'
    $powerShellExe = Join-Path $PSHOME 'powershell.exe'

    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Write-Host 'Tarefa agendada ja existente; sera recriada com a definicao correta.' -ForegroundColor Yellow
        Remove-TaskIfPresent
    }

    # Usar Register-ScheduledTask em lugar de schtasks.exe (mais confiavel)
    $action = New-ScheduledTaskAction -Execute $powerShellExe -Argument "-NoProfile -File `"$runner`""
    $trigger = New-ScheduledTaskTrigger -Daily -At '02:00'
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 8) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 15)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null

    # Tenta aplicar opcoes de resiliencia (reinicio em falha, comecar se perder o horario).
    # Se nao funcionar, a tarefa basica (diaria 02:00 como SYSTEM) ja esta valida.
    try {
        $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 8) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 15)
        Set-ScheduledTask -TaskName $TaskName -Settings $settings | Out-Null
    } catch {
        Write-Host 'Aviso: nao foi possivel aplicar as opcoes extras da tarefa (reinicio e disponibilidade).' -ForegroundColor Yellow
    }

    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (-not $task) {
        throw 'A tarefa agendada nao apareceu apos a criacao.'
    }

    $registeredAction = $task.Actions | Select-Object -First 1
    $registeredTrigger = $task.Triggers | Select-Object -First 1
    $principalUser = if ($task.Principal) { $task.Principal.UserId } else { '' }
    $principalOk = $principalUser -in @('SYSTEM', 'S-1-5-18', 'NT AUTHORITY\SYSTEM', 'NT AUTHORITY\S-1-5-18')
    $actionOk = $registeredAction -and $registeredAction.Arguments -like '*executar.ps1*'
    $triggerOk = $registeredTrigger -and $registeredTrigger.StartBoundary -match 'T02:00:00'

    if (-not ($principalOk -and $actionOk -and $triggerOk)) {
        Remove-TaskIfPresent
        throw 'A tarefa agendada foi criada, mas a validacao falhou. Ela foi removida automaticamente.'
    }
}

Clear-Host
Write-Host '================================================' -ForegroundColor Cyan
Write-Host '  VR Sync - Instalador do cliente' -ForegroundColor Cyan
Write-Host '================================================' -ForegroundColor Cyan
Write-Host ''

if (-not (Test-Administrator)) {
    Write-Host 'Solicitando permissao de Administrador...'
    $args = @('-NoProfile', '-File', ('"{0}"' -f $PSCommandPath))
    try {
        $process = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $args -Verb RunAs -Wait -PassThru
        exit $process.ExitCode
    } catch {
        Write-Host 'A elevacao foi cancelada. Execute instalar.bat como Administrador.' -ForegroundColor Red
        exit 1
    }
}

try {
    $nodeInfo = Get-NodeInfo
    if (-not $nodeInfo -or $nodeInfo.Major -lt 18) {
        Write-Host 'Node.js 18 ou superior e npm sao obrigatorios.' -ForegroundColor Yellow
        if ($nodeInfo) { Write-Host "Versao encontrada: $($nodeInfo.Version)" }
        Write-Host 'Instale a versao LTS em: https://nodejs.org/'
        Write-Host 'Ou use: winget install OpenJS.NodeJS.LTS'
        Write-Host 'Depois, execute instalar.bat novamente.'
        exit 2
    }
    Write-Host "Node.js $($nodeInfo.Version) encontrado."

    foreach ($file in $RuntimeFiles) {
        $source = Join-Path $PSScriptRoot $file
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Arquivo obrigatorio ausente no pacote: $file"
        }
    }

    $envFile = Join-Path $InstallDir '.env'
    $envExists = Test-Path -LiteralPath $envFile
    $installed = $false
    $taskExists = [bool](Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)

    if (Test-Path -LiteralPath $InstallDir) {
        if ($envExists) {
            $installed = $true
            if ($taskExists) {
                Write-Host "VR Sync ja esta instalado e configurado em '$InstallDir'." -ForegroundColor Yellow
                Write-Host 'Nada a fazer. Para reinstalar, remova a pasta e a tarefa antes (ver README).' -ForegroundColor Yellow
                exit 0
            }
            Write-Host 'Configuracao ja existente; recriando apenas a tarefa agendada...' -ForegroundColor Yellow
        } else {
            Write-Host "Instalacao incompleta detectada em '$InstallDir' (sem .env). Removendo para recomecar..." -ForegroundColor Yellow
            Remove-Item -LiteralPath $InstallDir -Recurse -Force
        }
    } elseif ($taskExists) {
        throw "A tarefa '$TaskName' ja existe sem pasta de instalacao. Remova-a com: schtasks /delete /tn `"$TaskName`" /f"
    }

    if (-not $installed) {
        New-Item -ItemType Directory -Path $InstallDir | Out-Null
        foreach ($file in $RuntimeFiles) {
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $InstallDir $file)
        }

        Push-Location $InstallDir
        try {
            Write-Host 'Instalando dependencias pelo registro oficial do npm...'
            & $nodeInfo.Npm ci --omit=dev --no-audit --no-fund --registry=https://registry.npmjs.org
            if ($LASTEXITCODE -ne 0) { throw 'Falha ao instalar dependencias com npm ci.' }
        } finally {
            Pop-Location
        }

        Write-Host ''
        Write-Host 'Informe os dados desta instalacao:' -ForegroundColor Cyan
        $pgHost = Read-Required 'Host do PostgreSQL' '127.0.0.1'
        $pgPort = Read-Integer 'Porta do PostgreSQL' 5433 1 65535
        $pgDatabase = Read-Required 'Nome do banco' 'reidaeconomia'
        $pgUser = Read-Required 'Usuario do PostgreSQL' 'postgres'
        $pgPassword = Read-MaskedSecret 'Senha do PostgreSQL (pode ficar vazia): '
        $vrLojaId = Read-Integer 'ID numerico desta loja no VR' 1 1 2147483647
        $encarteLoja = Read-Required 'Codigo unico desta loja no Encarte (ex.: loja-01)'
        $apiUrl = Read-Required 'URL HTTPS da API central' 'https://encarte-inteligente.vercel.app'
        $apiToken = Read-MaskedSecret 'Token da API central: '
        if ([string]::IsNullOrWhiteSpace($apiToken)) { throw 'O token da API e obrigatorio.' }

        $uri = $null
        if (-not [Uri]::TryCreate($apiUrl, [UriKind]::Absolute, [ref]$uri) -or
            ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.Host -in @('localhost', '127.0.0.1')))) {
            throw 'A URL da API deve ser uma URL HTTPS valida.'
        }
        $apiUrl = $apiUrl.TrimEnd('/')

        $candidate = Join-Path $InstallDir '.env.installing'
        $pgPasswordB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pgPassword))
        $apiTokenB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($apiToken))
        $lines = @(
            "PGHOST=$pgHost",
            "PGPORT=$pgPort",
            "PGDATABASE=$pgDatabase",
            "PGUSER=$pgUser",
            "PGPASSWORD_B64=$pgPasswordB64",
            "VR_LOJA_ID=$vrLojaId",
            "ENCARTE_LOJA=$encarteLoja",
            "ENCARTE_API_URL=$apiUrl",
            "ENCARTE_API_TOKEN_B64=$apiTokenB64"
        )
        [IO.File]::WriteAllLines($candidate, $lines, [Text.UTF8Encoding]::new($false))
        Protect-EnvironmentFile $candidate

        Write-Host ''
        Write-Host 'Validando banco, loja e API...'
        $previousEnvFile = $env:VR_SYNC_ENV_FILE
        $env:VR_SYNC_ENV_FILE = $candidate
        try {
            Push-Location $InstallDir
            try {
                & $nodeInfo.Node (Join-Path $InstallDir 'verificar.js')
                if ($LASTEXITCODE -ne 0) { throw 'A verificacao de banco ou API falhou.' }
            } finally {
                Pop-Location
            }
        } finally {
            $env:VR_SYNC_ENV_FILE = $previousEnvFile
        }

        Rename-Item -LiteralPath $candidate -NewName '.env'
        Protect-EnvironmentFile $envFile
    }

    Install-ScheduledTask

    Write-Host ''
    Write-Host 'Executando primeira sincronizacao...' -ForegroundColor Cyan
    Push-Location $InstallDir
    try {
        & $nodeInfo.Node (Join-Path $InstallDir 'sync.js')
        if ($LASTEXITCODE -eq 0) {
            Write-Host 'Primeira sincronizacao concluida com sucesso!' -ForegroundColor Green
        } else {
            Write-Host 'Aviso: primeira sincronizacao retornou codigo de erro, mas a instalacao continua valida.' -ForegroundColor Yellow
        }
    } catch {
        Write-Host "Aviso: erro na primeira sincronizacao: $($_.Exception.Message)" -ForegroundColor Yellow
    } finally {
        Pop-Location
    }

    Write-Host ''
    Write-Host '================================================' -ForegroundColor Green
    Write-Host '  Instalacao concluida com sucesso!' -ForegroundColor Green
    Write-Host '================================================' -ForegroundColor Green
    Write-Host "Pasta: $InstallDir"
    Write-Host "Tarefa: $TaskName (diariamente as 02:00)"
    Write-Host "Logs: $(Join-Path $InstallDir 'logs')"
    exit 0
} catch {
    Write-Host ''
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    $taskLeftBehind = [bool](Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)
    if ($taskLeftBehind) {
        Write-Host 'A tarefa agendada ficou registrada. Para remove-la, rode como Administrador:' -ForegroundColor Yellow
        Write-Host '  schtasks /delete /tn "VR Sync - Encarte Inteligente" /f' -ForegroundColor Yellow
    } else {
        Write-Host 'Nenhuma tarefa agendada ficou registrada.' -ForegroundColor Yellow
    }
    Write-Host "Se '$InstallDir' contem dados da instalacao atual, remova-a manualmente antes de reinstalar." -ForegroundColor Yellow
    exit 1
}
