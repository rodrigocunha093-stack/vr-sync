$ErrorActionPreference = 'Stop'

$InstallDir = $PSScriptRoot
$LogsDir = Join-Path $InstallDir 'logs'
$SyncScript = Join-Path $InstallDir 'sync.js'

New-Item -ItemType Directory -Path $LogsDir -Force | Out-Null

$NodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
if (-not $NodeCommand) {
    $fallback = Join-Path $env:ProgramFiles 'nodejs\node.exe'
    if (Test-Path -LiteralPath $fallback) {
        $NodeExe = $fallback
    } else {
        throw 'Node.js nao foi encontrado. Reinstale o VR Sync.'
    }
} else {
    $NodeExe = $NodeCommand.Source
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $LogsDir "sync-$stamp.log"

Push-Location $InstallDir
try {
    & $NodeExe $SyncScript --all *>&1 | Tee-Object -FilePath $logFile
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
