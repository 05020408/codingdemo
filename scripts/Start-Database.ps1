param([switch]$Bootstrap)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimeRoot = Join-Path $projectRoot '.runtime'
$settings = Get-Content -LiteralPath (Join-Path $runtimeRoot 'local.json') -Raw | ConvertFrom-Json
$mysqlRoot = $settings.mysqlRuntimeRoot
$mysqlExe = Join-Path $settings.mysqlHome 'bin/mysql.exe'
$serverExe = Join-Path $settings.mysqlHome 'bin/mysqld.exe'
$existing = Get-NetTCPConnection -LocalPort $settings.mysqlPort -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    $ownerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($existing[0].OwningProcess)"
    $expectedConfig = Join-Path $mysqlRoot 'my.ini'
    if ($ownerProcess.CommandLine -notlike "*$expectedConfig*") { throw 'Database port belongs to another process; refusing to reuse it.' }
} else {
    $process = Start-Process -FilePath $serverExe -ArgumentList @("`"--defaults-file=$(Join-Path $mysqlRoot 'my.ini')`"", '--console') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $mysqlRoot 'console.log') -RedirectStandardError (Join-Path $mysqlRoot 'error.log')
    $process.Id | Set-Content -LiteralPath (Join-Path $mysqlRoot 'server.pid')
    $ready = $false
    for ($i=0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        if ($process.HasExited) { throw "Local database exited. Inspect $mysqlRoot/error.log." }
        if (Get-NetTCPConnection -LocalPort $settings.mysqlPort -State Listen -ErrorAction SilentlyContinue) { $ready=$true; break }
    }
    if (-not $ready) { throw 'Local database did not become ready.' }
}
if ($Bootstrap) {
    $sql = "CREATE DATABASE IF NOT EXISTS review_workspace CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS review_workspace_test CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'review_app'@'127.0.0.1' IDENTIFIED BY '$($settings.dbPassword)'; CREATE USER IF NOT EXISTS 'review_app'@'localhost' IDENTIFIED BY '$($settings.dbPassword)'; GRANT ALL PRIVILEGES ON review_workspace.* TO 'review_app'@'127.0.0.1'; GRANT ALL PRIVILEGES ON review_workspace_test.* TO 'review_app'@'127.0.0.1'; GRANT ALL PRIVILEGES ON review_workspace.* TO 'review_app'@'localhost'; GRANT ALL PRIVILEGES ON review_workspace_test.* TO 'review_app'@'localhost'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$($settings.rootPassword)';"
    $setupResult = $sql | & $mysqlExe --protocol=TCP -h 127.0.0.1 -P $settings.mysqlPort -u root --skip-password 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Database credential setup failed; no SQL containing passwords was printed.' }
    'complete' | Set-Content -LiteralPath (Join-Path $mysqlRoot 'bootstrapped')
}
Write-Host "Project MySQL is available at 127.0.0.1:$($settings.mysqlPort). Existing Windows MySQL service is unchanged."
