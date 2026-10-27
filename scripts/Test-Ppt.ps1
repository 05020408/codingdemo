param([Parameter(Mandatory=$true)][string]$SourcePath,[string]$ConverterPath)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Use-LocalEnvironment.ps1')
$source=(Resolve-Path -LiteralPath $SourcePath).Path
$backend=Join-Path $projectRoot 'backend'
& (Join-Path $backend 'mvnw.cmd') -B -ntp -f (Join-Path $backend 'pom.xml') '-DskipTests=true' test-compile dependency:build-classpath '-Dmdep.outputFile=target/probe-classpath.txt' '-Dmdep.includeScope=test'
if($LASTEXITCODE -ne 0){throw 'Could not compile PPT probes.'}
$classpath=(Join-Path $backend 'target/classes')+[IO.Path]::PathSeparator+(Join-Path $backend 'target/test-classes')+[IO.Path]::PathSeparator+(Get-Content -LiteralPath (Join-Path $backend 'target/probe-classpath.txt') -Raw).Trim()
$output=Join-Path $projectRoot ('.runtime/probes/'+[Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force -Path $output | Out-Null
& (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $classpath cn.review.PptProbe $source (Join-Path $output 'text.json')
if($LASTEXITCODE -ne 0){throw 'PPT text extraction failed.'}
if($ConverterPath){
    $converter=(Resolve-Path -LiteralPath $ConverterPath).Path
    if([IO.Path]::GetExtension($converter) -ne '.exe'){throw 'On Windows use soffice.exe to preserve JSON argument quotes.'}
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $classpath cn.review.PptConversionProbe $source $output $converter
    if($LASTEXITCODE -ne 0){throw 'PPT conversion or page validation failed.'}
}
Write-Host "Probe artifacts (not uploaded to Git): $output"
