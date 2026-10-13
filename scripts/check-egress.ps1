$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$receiverName = 'relayforge-egress-check-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$appContainer = docker compose ps -q app
$networks = docker inspect $appContainer --format '{{json .NetworkSettings.Networks}}' | ConvertFrom-Json
$backend = $networks.PSObject.Properties.Name | Select-Object -First 1
if (-not $backend) { throw 'Application network was not found' }

function Read-BlockedPackets {
    $rules = docker compose exec -T proxy iptables -L OUTPUT -n -v -x
    if ($LASTEXITCODE -ne 0) { throw 'Could not read proxy rules' }
    $rule = $rules | Where-Object { $_ -match 'REJECT.*172\.16\.0\.0/12' }
    return [long](($rule.Trim() -split '\s+')[0])
}

try {
    docker run -d --rm --name $receiverName --network $backend nginx:1.28-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start controlled receiver' }
    $receiverIp = docker inspect $receiverName --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'
    $request = 'exec 3<>/dev/tcp/proxy/3128; printf "GET http://' + $receiverIp + '/ HTTP/1.1\r\nHost: ' + $receiverIp + '\r\nConnection: close\r\n\r\n" >&3; head -n 1 <&3'
    $status = docker compose exec -T app timeout 5 bash -c $request
    if ($LASTEXITCODE -ne 0 -or $status -notmatch '^HTTP/1\.[01] 403') { throw 'Proxy did not reject private receiver' }
    $before = Read-BlockedPackets
    docker compose exec -T proxy timeout 5 bash -c "exec 3<>/dev/tcp/$receiverIp/80" 2>$null
    if ($LASTEXITCODE -eq 0) { throw 'Proxy connected directly to private receiver' }
    if ((Read-BlockedPackets) -le $before) { throw 'Kernel destination rule did not reject the connection' }
    $receiverLog = docker logs $receiverName 2>&1
    if ($receiverLog -match '"(GET|POST|CONNECT) ') { throw 'Controlled receiver received an HTTP request' }
    Write-Output 'Proxy and kernel rules blocked the private receiver without an HTTP request'
} finally {
    docker stop $receiverName | Out-Null
}
