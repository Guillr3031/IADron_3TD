# Fuerza a Dock / mando a reconectarse a EMQX para que se vuelvan a presentar al backend.
#
# Por qué: el backend solo se suscribe a la telemetría de un dispositivo cuando este se presenta
# (update_topo en sys/product/<SN>/status), y eso solo pasa al conectarse. Si el backend se
# reinicia con el Dock ya conectado, el backend nuevo no lo ve y la web lo muestra "Disconnected".
# Echar al Dock de EMQX hace que se reconecte solo a los pocos segundos y se presente de nuevo.
#
# Uso: con el backend YA arrancado (después de "Started CloudApiSampleApplication"):
#   .\iadron\reconectar_dispositivos.ps1
#
# Usa la línea de comandos de EMQX dentro del contenedor (emqx_ctl): no necesita usuario ni API.

param([string]$Contenedor = "iadron_emqx")

$lista = docker exec $Contenedor emqx_ctl clients list 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error "No se pudo consultar EMQX ($lista). ¿Está levantado Docker / el contenedor $Contenedor?"
    exit 1
}

# Cada línea: Client(<clientid>, username=<usuario>, peername=..., ...)
$dispositivos = foreach ($linea in $lista) {
    if ($linea -match '^Client\((?<id>[^,]+),\s*username=(?<user>[^,]*),\s*peername=(?<ip>[^,]*)') {
        [pscustomobject]@{ Id = $Matches.id; Usuario = $Matches.user; Ip = $Matches.ip }
    }
}

# Se dejan en paz el backend (JavaServer) y los clientes del navegador (WebSocket del panel).
$dispositivos = $dispositivos | Where-Object { $_.Usuario -ne "JavaServer" -and $_.Id -notlike "mqttjs_*" }

if (-not $dispositivos) {
    Write-Host "No hay dispositivos conectados a EMQX (¿Dock apagado o sin red?)."
    exit 0
}

foreach ($d in $dispositivos) {
    Write-Host "Reconectando $($d.Id) (usuario '$($d.Usuario)', $($d.Ip))..."
    docker exec $Contenedor emqx_ctl clients kick $d.Id | Out-Null
}

Write-Host "Hecho. En 10-30 s deberían reaparecer en EMQX -> Clients y salir conectados en la web."
Write-Host "Comprobar: docker exec iadron_redis redis-cli keys `"*`""