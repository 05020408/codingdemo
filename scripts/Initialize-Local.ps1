param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MySqlHome,
    [int]$MySqlPort = 13306
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimeRoot = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
$settingsPath = Join-Path $runtimeRoot 'local.json'
if (Test-Path -LiteralPath $settingsPath) {
    $saved = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json
    if ($saved.mysqlRuntimeRoot -and (Test-Path -LiteralPath (Join-Path $saved.mysqlRuntimeRoot 'bootstrapped'))) {
        Write-Host 'Local configuration already exists. Use Start-Database.ps1; existing credentials were preserved.'
        return
    }
    $JavaHome = $saved.javaHome
    $MySqlHome = $saved.mysqlHome
    $MySqlPort = $saved.mysqlPort
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Pass -JavaHome with a JDK 17 directory.'
}
$javaVersion = (& (Join-Path $JavaHome 'bin/java.exe') -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "17\.') { throw 'This project requires JDK 17.' }
if (-not $MySqlHome) {
    $mysqlCommand = Get-Command mysql.exe -ErrorAction Stop
    $MySqlHome = Split-Path (Split-Path $mysqlCommand.Source -Parent) -Parent
}
if (-not (Test-Path -LiteralPath (Join-Path $MySqlHome 'bin/mysqld.exe'))) { throw 'Pass -MySqlHome with a MySQL installation directory.' }
if (Get-NetTCPConnection -LocalPort $MySqlPort -State Listen -ErrorAction SilentlyContinue) { throw 'The requested database port is occupied; choose another port.' }
$appPassword = if ($saved) { $saved.dbPassword } else { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)) }
$rootPassword = if ($saved) { $saved.rootPassword } else { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)) }
$workspaceId = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0,12)
$mysqlRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) "ReviewWorkspace/$workspaceId/mysql"
$settings = [ordered]@{ javaHome=$JavaHome; mysqlHome=$MySqlHome; mysqlPort=$MySqlPort; mysqlRuntimeRoot=$mysqlRoot; dbUsername='review_app'; dbPassword=$appPassword; rootPassword=$rootPassword; backendPort=18080 }
$settings | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding utf8
New-Item -ItemType Directory -Force -Path $mysqlRoot | Out-Null
$dataPath = Join-Path $mysqlRoot 'data'
$initLog = Join-Path $mysqlRoot 'initialize.log'
if (-not (Test-Path -LiteralPath (Join-Path $dataPath 'auto.cnf'))) {
    & (Join-Path $MySqlHome 'bin/mysqld.exe') --no-defaults --initialize-insecure "--basedir=$MySqlHome" "--datadir=$dataPath" "--log-error=$initLog"
    if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed. Inspect the project database log.' }
}
$mySqlConfig = @"
[mysqld]
basedir=$($MySqlHome.Replace('\','/'))
datadir=$($dataPath.Replace('\','/'))
port=$MySqlPort
bind-address=127.0.0.1
mysqlx=0
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
default-time-zone=+00:00
"@
[IO.File]::WriteAllText((Join-Path $mysqlRoot 'my.ini'), $mySqlConfig, [Text.UTF8Encoding]::new($false))
& (Join-Path $PSScriptRoot 'Start-Database.ps1') -Bootstrap
Write-Host 'Local database initialized. Credentials stay in ignored .runtime/local.json.'
