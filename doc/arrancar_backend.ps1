# Arranca el backend IADron con el .env cargado.
# Uso (desde la raíz del repo):  .\iadron\arrancar_backend.ps1
#
# --mqtt.BASIC.host=127.0.0.1: el backend se conecta a EMQX por localhost. Por la IP de red
# (SERVER_IP) Docker Desktop en Windows a veces no responde y da "Tiempo de espera excedido".
# Limitación: mientras se arranque así, el mando NO puede conectarse por pilot-login (recibiría
# 127.0.0.1). El Dock 2 no se ve afectado: su dirección MQTT se configura a mano en el mando.

$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz

Get-Content iadron\.env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item "env:$($k.Trim())" $v.Trim()
}

$ipActual = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '172.*' -and $_.IPAddress -notlike '169.254.*' }).IPAddress
Write-Host "SERVER_IP del .env : $env:SERVER_IP"
Write-Host "IP(s) actuales     : $($ipActual -join ', ')"
if ($ipActual -notcontains $env:SERVER_IP) {
    Write-Warning "SERVER_IP no coincide con la IP actual del PC. Actualiza iadron\.env (y la configuración del Dock)."
}

$jar = (Get-ChildItem sample\target\sample-*.jar | Select-Object -First 1).FullName
java -jar $jar --spring.profiles.active=iadron --mqtt.BASIC.host=127.0.0.1