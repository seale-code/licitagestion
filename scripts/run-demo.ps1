param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location $projectDirectory
try {
    & mvn -B package
    if ($LASTEXITCODE -ne 0) { throw 'La compilación o las pruebas fallaron.' }
    & java -jar target/licitagestion-2.0.0.jar --spring.profiles.active=demo --server.port=$Port
} finally { Pop-Location }

