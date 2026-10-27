param(
    [Parameter(Mandatory=$true)][string]$Database,
    [string]$DocumentsPath = './data/private-documents',
    [string]$Destination = './data/backups'
)
# Configure PGHOST, PGPORT, PGUSER and PGPASSWORD or a pgpass file before invoking.
# Do not place credentials in Database: use a database name, not a connection URI.
$ErrorActionPreference = 'Stop'
if ($Database -notmatch '^[a-zA-Z0-9_]+$') { throw 'Usa el nombre de la base, sin credenciales ni URI.' }
$sourceDirectory = (Resolve-Path -LiteralPath $DocumentsPath).Path
$backupDirectory = [System.IO.Path]::GetFullPath((Join-Path $Destination (Get-Date -Format 'yyyyMMdd-HHmmss')))
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
& pg_dump --format=custom --schema=licitagestion_private --file=(Join-Path $backupDirectory 'database.dump') --dbname=$Database
if ($LASTEXITCODE -ne 0) { throw 'pg_dump falló. No se considera un backup válido.' }
# Documents are immutable and not physically deleted by the application.
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($sourceDirectory, (Join-Path $backupDirectory 'documents.zip'))
Get-ChildItem -LiteralPath $backupDirectory -File | Get-FileHash -Algorithm SHA256 | Select-Object Path,Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backupDirectory 'checksums.json')
Write-Output "Backup generado en $backupDirectory. Verifica una restauración en un entorno separado."

