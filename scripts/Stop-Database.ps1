$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$settings=Get-Content -LiteralPath (Join-Path $projectRoot '.runtime/local.json') -Raw | ConvertFrom-Json
$listener=Get-NetTCPConnection -LocalPort $settings.mysqlPort -State Listen -ErrorAction SilentlyContinue
if(-not $listener){Write-Host 'Project database is already stopped.';return}
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
$expectedConfig=Join-Path $settings.mysqlRuntimeRoot 'my.ini'
if($process.CommandLine -notlike "*$expectedConfig*"){throw 'Port belongs to another process; refusing to stop it.'}
$previousPassword=$env:MYSQL_PWD
try {
    $env:MYSQL_PWD=$settings.rootPassword
    & (Join-Path $settings.mysqlHome 'bin/mysqladmin.exe') --protocol=TCP -h 127.0.0.1 -P $settings.mysqlPort -u root shutdown
    if($LASTEXITCODE -ne 0){throw 'Project database shutdown failed.'}
} finally {$env:MYSQL_PWD=$previousPassword}
Write-Host 'Project database stopped; its files and existing Windows MySQL service were preserved.'
