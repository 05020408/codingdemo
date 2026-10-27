$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Start-Database.ps1')
. (Join-Path $PSScriptRoot 'Use-LocalEnvironment.ps1')
& (Join-Path $projectRoot 'backend/mvnw.cmd') -f (Join-Path $projectRoot 'backend/pom.xml') spring-boot:run
exit $LASTEXITCODE
