$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
foreach($listener in (Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue)) {
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if($process.CommandLine -like "*$projectRoot*frontend*node_modules*vite*"){throw 'Stop the project frontend dev server before the clean npm installation.'}
}
& (Join-Path $PSScriptRoot 'Start-Database.ps1')
. (Join-Path $PSScriptRoot 'Use-LocalEnvironment.ps1') -TestDatabase
& (Join-Path $projectRoot 'backend/mvnw.cmd') -B -ntp -f (Join-Path $projectRoot 'backend/pom.xml') verify
if($LASTEXITCODE -ne 0){throw 'Backend verification failed.'}
Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm.cmd ci --no-fund --no-audit
    if($LASTEXITCODE -ne 0){throw 'Frontend installation failed.'}
    & npm.cmd run build
    if($LASTEXITCODE -ne 0){throw 'Frontend build failed.'}
} finally {Pop-Location}
