# IADron · Backend Cloud API para Matrice 3TD + Dock 2 (sin GNSS)

Rama `iadron/3td-sin-gnss` sobre el demo oficial `dji-sdk/DJI-Cloud-API-Demo` (Cloud API 1.10, Java 11, Spring Boot 2.7).

**Supuesto de partida:** dentro de la nave no hay señal GNSS (con el Mini 4 Pro no se detectó ninguna).
El repo queda preparado para:

1. Ver en todo momento **si el dron tiene o no GNSS** y por qué.
2. Recibir la **pose** del 3TD (altura, actitud, velocidades, gimbal) y reenviarla al servidor central.
3. Reenviar cada **foto térmica/visible** subida a MinIO al servidor central, junto con la pose.
4. **Pilotar desde el servidor sin GNSS** con joysticks virtuales por DRC (equivalente al Virtual Stick del MSDK).
5. **Registrar cada vuelo** en JSON Lines para analizar la deriva después.

> ⚠️ El demo de DJI está sin mantenimiento desde abril de 2025 y no es apto para producción.
> Este montaje es para la red local de pruebas. EMQX acepta clientes anónimos: no lo expongas a internet.

---

## 1. Qué depende de GNSS y qué no

| Función (Cloud API) | ¿Necesita GNSS? | Notas |
|---|---|---|
| Telemetría OSD (altura, actitud, velocidades, gimbal) | No | lat/lon vendrán a 0 o sin sentido |
| Fotos, vídeo en directo, cambio de lente térmica | No | |
| Medición de temperatura (IR metering) | No | |
| **DRC: joysticks virtuales** (`drone_control`) | **No** | Requiere Dock como gateway y dron **ya en el aire** |
| `takeoff-to-point`, `fly-to-point`, `camera look-at` | **Sí** | Usan lat/lon |
| Rutas (waylines / flight-tasks) | **Sí** | |
| Return to Home | Sí (punto home por GNSS) | **No usar en interior** |

La gran incógnita es **cómo despegar desde el Dock sin GNSS**: en Cloud API el despegue va siempre a través de `takeoff-to-point` o de una ruta, y ambos usan coordenadas. La Prueba 3 está pensada para averiguarlo.

---

## 2. Qué se ha cambiado

### Ficheros nuevos (`sample/src/main/java/com/dji/sample/iadron/`)

| Fichero | Para qué |
|---|---|
| `config/IadronProperties.java` | Configuración `iadron.*` (servidor central, límites DRC, criterio GNSS) |
| `model/DronePose.java` | Pose normalizada (OSD Dock, OSD RC, OSD DRC 10 Hz) |
| `model/PositioningStatus.java` | Satélites, fix RTK y si lat/lon son utilizables |
| `model/CaptureEvent.java` | Foto subida a MinIO + pose en ese momento |
| `model/StickCommandParam.java` | Comando de joystick (x, y, h, w, duración) |
| `service/PoseTracker.java` | Entrada única de telemetría: estado GNSS, última pose, log de vuelo, reenvío |
| `service/IadronEventPublisher.java` + `impl/HttpIadronEventPublisher.java` | Envío asíncrono por HTTP al servidor central |
| `service/CaptureEvents.java` | Convierte los avisos de subida del Dock y de Pilot 2 en `CaptureEvent` |
| `service/DrcSessionService.java` | Sesión DRC desde el servidor: heartbeat, joysticks a 10 Hz, hombre muerto, parada de emergencia |
| `service/DrcLinkMonitor.java` | Estado del enlace DRC (acks de heartbeat/joystick, retardo) |
| `service/GnssGuard.java` | Avisa o bloquea `takeoff-to-point` / `fly-to-point` si no hay GNSS |
| `controller/IadronController.java` | API REST `/iadron/api/v1/...` |

### Ficheros del demo modificados (cambios mínimos, marcados con `// IADron`)

| Fichero | Cambio |
|---|---|
| `manage/service/impl/SDKDeviceService.java` | `osdDockDrone` y `osdRcDrone` pasan la OSD a `PoseTracker` |
| `control/service/impl/SDKControlService.java` | Implementa los 6 mensajes `drc/up` (`osd_info_push`, `heart_beat`, `drone_control`, `drone_emergency_stop`, `hsi_info_push`, `delay_info_push`) que el SDK dejaba sin implementar |
| `control/service/impl/DrcServiceImpl.java` | **Corrige un hueco del demo:** nunca se suscribía a `thing/product/{dockSn}/drc/up`, así que el backend no recibía la OSD de alta frecuencia. Ahora se suscribe al entrar en DRC y se desuscribe al salir |
| `media/service/impl/MediaServiceImpl.java` | Reenvía cada foto subida (Dock por MQTT y Pilot 2 por HTTP) |
| `control/controller/DockController.java` | `fly-to-point` y `takeoff-to-point` pasan por `GnssGuard` |

### Infraestructura

- `iadron/docker-compose.yml`: MySQL 8, Redis, EMQX 4.4, MinIO (+ creación del bucket y usuario).
- `iadron/sql/02_iadron_seed.sql`: workspace "IADron Nave", bind code `IADRON01`.
- `sample/src/main/resources/application-iadron.yml`: perfil Spring con todo apuntando a lo anterior.
- `iadron/tools/servidor_central_mock.py`: servidor central de pega que imprime lo que recibe.

---

## 3. Puesta en marcha (PC Linux)

Requisitos: Docker + Docker Compose, JDK 11 (o superior compilando a 11), Maven 3.8+.

Guía paso a paso para Windows (con los problemas ya resueltos): `INSTALACION.md`.

```bash
cd iadron
cp .env.example .env          # SERVER_IP = IP del PC en la red del Dock/RC; DJI_APP_ID/KEY/LICENSE
docker compose up -d
docker compose logs minio-bucket minio-user minio-policy   # bucket, usuario y política creados

# Servidor central de prueba (otra terminal)
python3 tools/servidor_central_mock.py --port 8090

# Backend (desde la raíz del repo)
cd ..
set -a; source iadron/.env; set +a
mvn -q -DskipTests package
java -jar sample/target/sample-*.jar --spring.profiles.active=iadron
```

**Frontend (necesario para Pilot 2 y para vincular el Dock):** Pilot 2 no habla directamente con este backend al
"conectarse a la nube de terceros": abre una página web que, mediante el JSBridge de Pilot 2, le pasa la
configuración MQTT, el workspace y el token. Esa página es el repo hermano
[`dji-sdk/Cloud-API-Demo-Web`](https://github.com/dji-sdk/Cloud-API-Demo-Web) (Vue). Hay que configurar en él la URL
del backend (`http://SERVER_IP:6789`) y el App ID/Key/License, y servirlo en la red local.
También está sin mantenimiento desde abril de 2025.

Comprobaciones rápidas:

- Swagger: `http://SERVER_IP:6789/swagger-ui/index.html`
- EMQX: `http://SERVER_IP:18083` (admin / public) → en *Clients* debe aparecer `iadron-backend`
- MinIO: `http://SERVER_IP:9001`

Login para la API:

```bash
TOKEN=$(curl -s -X POST http://localhost:6789/manage/api/v1/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"adminPC","password":"adminPC","flag":1}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["access_token"])')
```

---

## 4. Plan de pruebas sin GNSS

Antes de cada vuelo: zona despejada, una persona con el RC Plus lista para tomar el control, límites `iadron.drc.max-*` bajos.

### Prueba 0 · Infraestructura (sin dron)
- `docker compose ps` todo *running*; backend arranca sin errores; el mock recibe nada aún.
- ✅ Criterio: login devuelve token.

### Prueba 1 · Pilot 2 como gateway (RC Plus, vuelo manual)
1. En el RC Plus → Pilot 2 → servicios en la nube → nube de terceros → URL de la página de login *pilot* del frontend (`Cloud-API-Demo-Web`), usuario `pilot / pilot123`. *(Revisa los pasos exactos en la documentación de Cloud API: dependen de la versión de Pilot 2.)*
2. Enciende el 3TD en el suelo, en interior.
3. Mira el log del backend: debe salir `[IADRON] Dron XXXX: GNSS NO utilizable (...)`.
4. `curl -H "x-auth-token: $TOKEN" http://localhost:6789/iadron/api/v1/devices/<SN_DRON>/positioning`
5. Vuela a mano, haz varias fotos con la lente térmica y deja que Pilot 2 las suba.
- ✅ Criterio: el mock imprime poses `RC_OSD` y una `FOTO thermal=True` con su `object_key`.
- Anota: ¿qué `fix_state`, `gps_number` y `rtk_number` reporta en interior? ¿cómo se comporta la altura?

> Con el RC como gateway **no hay DRC** (Cloud API lo excluye): el servidor sólo observa.

### Prueba 2 · Dock 2 como gateway, dron dentro del Dock
1. Vincula el Dock al workspace (bind code `IADRON01`) desde el RC Plus en modo configuración del Dock.
2. Modo depuración remota y encendido del dron sin despegar:
   ```bash
   B=http://localhost:6789/control/api/v1/devices/<SN_DOCK>/jobs
   curl -X POST -H "x-auth-token: $TOKEN" $B/debug_mode_open
   curl -X POST -H "x-auth-token: $TOKEN" $B/drone_open
   ```
3. Consulta `/iadron/api/v1/devices/<SN_DOCK>/positioning` y `/pose`.
- ✅ Criterio: llegan poses `DOCK_OSD` con el estado GNSS del dron dentro del Dock.

### Prueba 3 · ¿Despega el Dock sin GNSS?
Con `iadron.gnss.guard-mode: WARN`, lanza un `takeoff-to-point` con coordenadas de la nave y altura de seguridad baja (desde el frontend o `POST /control/api/v1/devices/<SN_DOCK>/jobs/takeoff-to-point`).
- Resultado A: el Dock lo rechaza → **anota el código de error** (sale en el log y en el evento `takeoff_to_point_progress`). El despegue tendrá que ser manual (RC Plus) y la Prueba 4 se hará tomando luego el control desde la nube, si el Dock lo permite.
- Resultado B: despega → pasa a la Prueba 4 en cuanto esté en el aire.

### Prueba 4 · Control relativo desde el servidor (DRC)
Requiere dron **en el aire** con el Dock como gateway.
```bash
D=http://localhost:6789/iadron/api/v1/devices/<SN_DOCK>/drc
H="x-auth-token: $TOKEN"
curl -X POST -H "$H" $D/enter                       # entra en DRC, arranca heartbeat
curl -X POST -H "$H" -H 'Content-Type: application/json' $D/stick \
     -d '{"x":0.3,"y":0,"h":0,"w":0,"duration_ms":800}'   # 0,3 m/s adelante durante 0,8 s
curl -X POST -H "$H" $D/hold                        # estacionario
curl -H "$H" $D/link                                 # acks de heartbeat/joystick, retardo
curl -X POST -H "$H" $D/emergency-stop              # frenada de emergencia
curl -X POST -H "$H" $D/exit
```
- Si no reenvías `stick`, a los `duration_ms` (máx. 1 s) el backend manda ceros: el dron se queda quieto.
- ✅ Criterio: el dron responde a los ejes en el sentido esperado; el mock recibe poses `DRC_OSD` a ~10 Hz; `link.last_heartbeat_ack_at` se actualiza cada segundo.
- Anota la deriva: compárala con el log de vuelo.

---

## 5. API IADron (`/iadron/api/v1`, cabecera `x-auth-token`)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/devices/{sn}/pose` | Última pose (sn de Dock, RC o dron) |
| GET | `/devices/{sn}/positioning` | Estado GNSS y motivo |
| POST | `/devices/{dockSn}/drc/enter` | Entra en DRC como servidor (dron en el aire) |
| POST | `/devices/{dockSn}/drc/stick` | `{x, y, h, w, duration_ms}` en m/s y °/s, recortados a los límites |
| POST | `/devices/{dockSn}/drc/hold` | Para el movimiento |
| POST | `/devices/{dockSn}/drc/emergency-stop` | Parada de emergencia DRC |
| GET | `/devices/{dockSn}/drc/link` | Salud del enlace DRC |
| POST | `/devices/{dockSn}/drc/exit` | Sale de DRC |

Ejes del joystick: `x` adelante(+)/atrás(-), `y` derecha(+)/izquierda(-), `h` subir(+)/bajar(-), `w` giro de yaw. **Compruébalos en la primera prueba con valores pequeños**: con el Mini ya te salieron ejes invertidos.

## 6. Lo que recibe el servidor central

`POST {central}/api/drone/pose` (snake_case, campos nulos omitidos):
```json
{
  "source": "DRC_OSD", "gateway_sn": "DOCK_SN", "drone_sn": "DRONE_SN", "received_at": 1760000000000,
  "height": 2.1, "attitude_head": 87.5, "speed_x": 0.3, "speed_y": 0.0, "speed_z": 0.0,
  "gimbal_pitch": -30.0, "gimbal_yaw": 87.0,
  "positioning": { "gnss_usable": false, "gps_number": 0, "fix_state": "NOT_START",
                   "reason": "lat/lon a 0 o ausentes" }
}
```
`POST {central}/api/drone/capture`: `object_key`, `bucket`, `thermal`, `file_name`, metadatos de la foto y `pose_at_upload`.

## 7. Log de vuelo

`logs/iadron/vuelo-AAAA-MM-DD.jsonl`, una línea por evento (`pose`, `capture`, `gnss_change`, `drc_*`, `drc_hsi_info_push` con distancias a obstáculos).
Sirve para medir la deriva y el margen real de la evitación de obstáculos tras cada prueba.

## 8. Pendiente / limitaciones conocidas

- **No compilado en el entorno donde se preparó** (sin acceso a Maven Central). Primer paso: `mvn -DskipTests package` y corregir lo que salga.
- El despegue desde Dock sin GNSS es desconocido (Prueba 3).
- La pose sin GNSS sólo da altura, actitud y velocidades: la posición X/Y en la nave tendrá que venir de integración de velocidades + referencias visuales (ArUco/plano de Godot), igual que con el Mini.
- `measure_target_distance` se reenvía si el 3TD lo reporta; no está confirmado que lo haga.
- EMQX en modo anónimo; el JWT/ACL de DRC del demo no se valida en este montaje.
