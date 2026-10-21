# IADron - arranca el backend cargando iadron\.env
# Uso (desde la raíz del repo):  .\iadron\run.ps1

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Error "No existe $envFile"
    exit 1
}

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}

foreach ($v in 'DJI_APP_ID', 'DJI_APP_KEY', 'DJI_APP_LICENSE', 'SERVER_IP') {
    $val = [Environment]::GetEnvironmentVariable($v, 'Process')
    if ([string]::IsNullOrWhiteSpace($val) -or $val -like 'PEGA_AQUI*') {
        Write-Error "Falta $v en $envFile"
        exit 1
    }
}
Write-Host "Variables cargadas. SERVER_IP=$env:SERVER_IP  DJI_APP_ID=$env:DJI_APP_ID"

$jar = Join-Path $PSScriptRoot "..\sample\target\sample-1.10.0.jar"
java -jar $jar --spring.profiles.active=iadron