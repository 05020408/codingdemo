param([switch]$TestDatabase,[switch]$EnableAi)
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
$env:INDEX_ROOT = Join-Path $projectRoot $(if($TestDatabase){'.runtime/test-index'}else{'.runtime/index'})
if($EnableAi -and -not $TestDatabase){
    $keyPath=Join-Path (Split-Path $projectRoot -Parent) 'apikey.txt'
    if(-not $env:DEEPSEEK_API_KEY -and (Test-Path -LiteralPath $keyPath)){
        $keyMatch=[regex]::Match([IO.File]::ReadAllText($keyPath),'\bsk-[A-Za-z0-9_-]{12,}\b')
        if($keyMatch.Success){$env:DEEPSEEK_API_KEY=$keyMatch.Value}
    }
    if(-not $env:DEEPSEEK_API_KEY){throw '未找到 DeepSeek API key，请设置环境变量或在项目外 apikey.txt 中配置。'}
    $env:AI_ENABLED='true'
    if(-not $env:AI_DAILY_REQUEST_LIMIT){$env:AI_DAILY_REQUEST_LIMIT='10'}
}
$env:STORAGE_ROOT = Join-Path $projectRoot $(if($TestDatabase){'.runtime/test-data'}else{'.runtime/data'})
if(-not $env:CONVERTER_PATH) {
    $localConverter=Join-Path $projectRoot '.runtime/tools/libreoffice/program/soffice.exe'
    if(Test-Path -LiteralPath $localConverter){$env:CONVERTER_PATH=$localConverter}
}
