$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if(-not (Test-Path node_modules)) {
        & npm.cmd ci --no-fund --no-audit
        if($LASTEXITCODE -ne 0){throw 'Frontend installation failed.'}
    }
    & npm.cmd run dev
} finally {Pop-Location}
