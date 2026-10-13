param([switch]$Restart)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$apiKey = $env:RELAYFORGE_API_KEY
if ([string]::IsNullOrWhiteSpace($apiKey) -and (Test-Path -LiteralPath '.env')) {
    $keyLine = Get-Content -LiteralPath '.env' | Where-Object { $_.StartsWith('RELAYFORGE_API_KEY=') }
    if ($keyLine) { $apiKey = $keyLine.Substring('RELAYFORGE_API_KEY='.Length) }
}
if ([string]::IsNullOrWhiteSpace($apiKey)) { throw 'RELAYFORGE_API_KEY must be configured' }

function Read-EndpointSnapshot {
    $snapshot = docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "SELECT coalesce(json_agg(t ORDER BY id)::text, ''[]'') FROM (SELECT id,url,enabled FROM webhook_endpoints) t;"'
    if ($LASTEXITCODE -ne 0) { throw 'Could not read database snapshot' }
    return ($snapshot -join "`n").Trim()
}

$before = Read-EndpointSnapshot
if ($Restart) {
    docker compose restart postgres app
    if ($LASTEXITCODE -ne 0) { throw 'Compose restart failed' }
}
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/health' -TimeoutSec 2
        if ($health -eq 'OK') { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw 'Application did not become ready' }
$events = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/v1/events' -Headers @{ 'X-API-Key' = $apiKey }
if ($events.StatusCode -ne 200) { throw 'Authenticated API request failed' }
$unauthorized = $false
try {
    $null = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/v1/events'
} catch {
    $unauthorized = [int]$_.Exception.Response.StatusCode -eq 401
}
if (-not $unauthorized) { throw 'API accepted a request without its key' }
if ($before -ne (Read-EndpointSnapshot)) { throw 'Saved endpoint values changed' }
Write-Output 'Compose health, API access and saved endpoint values verified'
