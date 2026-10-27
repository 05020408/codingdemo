param([switch]$TestDatabase)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$settings = Get-Content -LiteralPath (Join-Path $projectRoot '.runtime/local.json') -Raw | ConvertFrom-Json
$env:JAVA_HOME = $settings.javaHome
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
$database = if ($TestDatabase) { 'review_workspace_test' } else { 'review_workspace' }
$env:DB_URL = "jdbc:mysql://127.0.0.1:$($settings.mysqlPort)/${database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&sslMode=DISABLED&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = $settings.dbUsername
$env:DB_PASSWORD = $settings.dbPassword
if ($TestDatabase) { $env:TEST_DB_URL = $env:DB_URL }
$env:SERVER_PORT = [string]$settings.backendPort
$env:AI_ENABLED = 'false'
$env:STORAGE_ROOT = Join-Path $projectRoot $(if($TestDatabase){'.runtime/test-data'}else{'.runtime/data'})
if(-not $env:CONVERTER_PATH) {
    $localConverter=Join-Path $projectRoot '.runtime/tools/libreoffice/program/soffice.exe'
    if(Test-Path -LiteralPath $localConverter){$env:CONVERTER_PATH=$localConverter}
}
