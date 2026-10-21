# Instalación del entorno IADron 3TD (Windows)

Guía paso a paso para dejar funcionando el backend Cloud API de IADron (Matrice 3TD + Dock 2) en un PC con Windows 10/11.
Recoge todos los pasos y los problemas reales que han ido apareciendo. Para el plan de pruebas con el dron, ver `README_IADRON.md`.

> Estado de esta guía: pasos 1–8 probados en Windows 10 (19045). Dock 2 conectado a la nube de terceros el 14/10/2026.

---

## 0. Qué se instala y para qué

| Pieza | Para qué | Dónde corre |
|---|---|---|
| Backend Spring Boot (`sample`) | Habla con el Dock / Pilot 2 por MQTT y expone la API REST | Java en Windows |
| MySQL 8 | Dispositivos, usuarios, workspaces, ficheros | Docker |
| Redis 6 | Estado en vivo (dispositivos online, OSD, DRC) | Docker |
| EMQX 4.4 | Broker MQTT (1883) y MQTT sobre WebSocket para DRC (8083) | Docker |
| MinIO | Almacén S3 donde el dron sube fotos y rutas | Docker |
| Servidor central de prueba | Recibe pose y capturas reenviadas por el backend | Python en Windows |
| Frontend `Cloud-API-Demo-Web` | Necesario para conectar Pilot 2 y vincular el Dock | Pendiente |

Puertos usados en el host: **3307** (MySQL), 6379 (Redis), 1883 / 8083 / 18083 (EMQX), 9000 / 9001 (MinIO), 6789 (backend), 8090 (servidor central de prueba).

---

## 1. Requisitos

- **Git** para Windows.
- **JDK 11 o superior** (17 o 21 también valen; el proyecto compila a Java 11).
- **IntelliJ IDEA** (trae Maven incluido; no hace falta instalar Maven aparte).
- **Docker Desktop** con **WSL 2** (ver paso 4).
- **Python 3** (sólo para el servidor central de prueba).
- Una app **Cloud API** en developer.dji.com (paso 3).

---

## 2. Obtener el código

El repo `Guillr3031/IADron_3TD` contiene el demo oficial de DJI (`dji-sdk/DJI-Cloud-API-Demo`, Cloud API 1.10) más la capa IADron.

```powershell
cd C:\Users\<usuario>\IdeaProjects
git clone https://github.com/Guillr3031/IADron_3TD.git
cd IADron_3TD
Test-Path iadron          # debe devolver True
```

> Si `Test-Path iadron` da `False`, la capa IADron no está aplicada. Se aplica con el parche:
> `git am --ignore-whitespace "$env:USERPROFILE\Downloads\iadron-3td-sin-gnss.patch"`
> (si falla: `git am --abort` y revisar el error).

Opcional, para poder comparar con el demo original de DJI:
```powershell
git remote add upstream https://github.com/dji-sdk/DJI-Cloud-API-Demo.git
```

---

## 3. Credenciales de DJI y fichero `.env`

1. developer.dji.com → *Apps* → *Create App* → tipo **Cloud API**. Anotar **App ID**, **App Key** y **App Basic License**.
2. Crear el `.env` (está en `.gitignore`: **nunca se sube a GitHub**):

```powershell
copy iadron\.env.example iadron\.env
ipconfig                  # anotar la "Dirección IPv4" del adaptador de la red del Dock/RC
notepad iadron\.env
```

Valores a rellenar:

| Variable | Valor |
|---|---|
| `SERVER_IP` | IPv4 del PC en la red donde estarán el Dock 2 / RC Plus (no `localhost`) |
| `MYSQL_PORT` | `3307` (ver problema 5.1) |
| `DJI_APP_ID` / `DJI_APP_KEY` / `DJI_APP_LICENSE` | Copiar y pegar desde la web de DJI (no teclear la License a mano) |
| `IADRON_CENTRAL_URL` | `http://localhost:8090` mientras se use el servidor de prueba |

Si las credenciales se filtran (capturas, commits), regenerarlas desde la propia página de la app en DJI (*EDIT*).

---

## 4. Docker Desktop y WSL 2

Docker Desktop en Windows necesita WSL 2.

1. PowerShell **como administrador**:
   ```powershell
   wsl --install --no-distribution
   ```
2. **Reiniciar el PC** (obligatorio).
3. Comprobar: `wsl --status` y abrir Docker Desktop hasta ver **Engine running**.

### Problemas encontrados

- **Docker Desktop: "WSL needs updating"** → `wsl --update` como administrador.
- **`Wsl/WSL_E_WSL_OPTIONAL_COMPONENT_REQUIRED`** aunque `wsl --version` funcione: el paquete WSL está, pero falta el componente de Windows. Activarlo y reiniciar:
  ```powershell
  dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
  dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
  ```
- **Error de virtualización** (`0x80370102` o similar): activar VT-x / AMD-V (SVM) en la BIOS.
- **PC con restricciones de empresa**: `dism` puede necesitar al administrador de sistemas.

---

## 5. Levantar la infraestructura (MySQL, Redis, EMQX, MinIO)

```powershell
cd iadron
docker compose up -d
docker compose ps
docker compose logs minio-bucket minio-user minio-policy
```

Resultado esperado:
- `iadron_mysql`, `iadron_redis`, `iadron_emqx`, `iadron_minio` → **Up**.
- `minio-bucket`, `minio-user`, `minio-policy` se ejecutan una vez y terminan (crean el bucket `cloud-bucket`, el usuario `iadron` y le dan permisos `readwrite`).
- La primera vez tarda unos minutos (descarga de imágenes).

La base de datos se inicializa sola la **primera vez** con `sql/cloud_sample.sql` (esquema de DJI), `iadron/sql/02_iadron_seed.sql` (workspace "IADron Nave", bind code `IADRON01`) y `iadron/sql/03_iadron_workspace_id.sql` (renombra el workspace a `iadron-nave`, ver 8.3).
En `docker-compose.yml`, el servicio de MySQL tiene que montar los tres:
```yaml
      - ../sql/cloud_sample.sql:/docker-entrypoint-initdb.d/01_cloud_sample.sql:ro
      - ./sql/02_iadron_seed.sql:/docker-entrypoint-initdb.d/02_iadron_seed.sql:ro
      - ./sql/03_iadron_workspace_id.sql:/docker-entrypoint-initdb.d/03_iadron_workspace_id.sql:ro
```
Para reiniciarla desde cero: `docker compose down -v` (borra también las fotos de MinIO).

Comprobaciones en el navegador:
- EMQX: `http://localhost:18083` → `admin` / `public`.
- MinIO (consola): `http://localhost:9001` → `minioadmin` / `minioadmin`. Puede verse muy recortada: no importa, el backend y el dron usan la API del puerto 9000.

### Problemas encontrados

#### 5.1 Puerto 3306 ocupado
`ports are not available: exposing port TCP 0.0.0.0:3306 ... Solo se permite un uso de cada dirección de socket`
→ Ya hay un MySQL instalado en Windows. Por eso el MySQL de Docker se publica en el **3307** (`MYSQL_PORT` en `.env`; el perfil `application-iadron.yml` usa el mismo valor). El MySQL local de Windows no se toca.
Si otro puerto choca (6379, 1883, 9000…), avisar: se cambia igual.

#### 5.2 `pull access denied for minio/minio` / `minio/mc`
MinIO retiró sus imágenes de Docker Hub en septiembre de 2026 (y la copia de quay.io también falla). `docker login` no lo arregla.
→ El compose usa `cgr.dev/chainguard/minio:latest` y `cgr.dev/chainguard/minio-client:latest`. Esas imágenes no tienen shell, por eso la inicialización está dividida en tres servicios de un solo comando (`minio-bucket`, `minio-user`, `minio-policy`) que se reintentan solos hasta que MinIO está listo.

#### 5.3 Si una imagen falla, Compose cancela todas
Las demás salen como *Interrupted*: buscar la línea con ✘, que es la que tiene el error real.

---

## 6. Compilar el backend

El repo **no trae Maven Wrapper** (`mvnw`). Dos opciones:

**A. Desde IntelliJ (más sencillo)**
1. Abrir la carpeta `IADron_3TD` (detecta el `pom.xml`; si no: clic derecho → *Add as Maven Project*).
2. *File → Project Structure → Project*: SDK = JDK 11+.
3. Pestaña **Maven** → activar el rayo (*Skip Tests*) → *cloud-api-sample → Lifecycle → package*.

**B. Desde PowerShell con el Maven de IntelliJ**
```powershell
$mvn = Get-ChildItem "C:\Program Files\JetBrains", "$env:LOCALAPPDATA\Programs", "$env:LOCALAPPDATA\JetBrains" -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue | Where-Object { $_.FullName -like "*maven3\bin*" } | Select-Object -First 1 -ExpandProperty FullName
& $mvn -DskipTests package
```
Para tener `.\mvnw.cmd` en adelante: `& $mvn -N wrapper:wrapper` y subir `mvnw`, `mvnw.cmd` y `.mvn/`.

Ejecutar siempre **desde la raíz del repo** (donde está `pom.xml`), no desde `iadron\`: si no, Maven dice *there is no POM in this directory*.

Resultado esperado: **BUILD SUCCESS** y el jar ejecutable en `sample\target\sample-1.10.0.jar` (unos 100 MB; si pesa muy poco, no es ejecutable).

### Problema encontrado: `no hay ningún atributo de manifiesto principal`
El demo de DJI importa Spring Boot como BOM y no como *parent*, así que el `spring-boot-maven-plugin` no empaquetaba el jar ejecutable.
→ Corregido en `sample/pom.xml` (ejecución `repackage` y `mainClass`). Si vuelve a salir, recompilar con el `pom.xml` actualizado.
Si sale `JAVA_HOME is not defined`: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` (ruta del JDK instalado).

---

## 7. Arrancar el servidor central de prueba y el backend

Terminal 1 (raíz del repo):
```powershell
python iadron\tools\servidor_central_mock.py --port 8090
```

Terminal 2 (raíz del repo):
```powershell
.\iadron\arrancar_backend.ps1
```
El script carga el `.env` (PowerShell no tiene `source`), avisa si `SERVER_IP` no coincide con la IP actual del PC y arranca el jar con `--mqtt.BASIC.host=127.0.0.1` (ver 7.3).
Si PowerShell bloquea scripts: `powershell -ExecutionPolicy Bypass -File .\iadron\arrancar_backend.ps1`.

Debe terminar con `Started CloudApiSampleApplication` **y sin** errores `MqttException` después.

### Problemas encontrados

#### 7.1 `Failed to bind properties under 'livestream.url.gb28181.local-port'`
El `application.yml` de DJI trae textos de ejemplo ("Please enter the local port.") en campos numéricos del vídeo en directo y el backend no arranca.
→ Corregido en `application-iadron.yml` con valores neutros (`livestream.url.*`). Como el yml va dentro del jar, **hay que recompilar** (`clean` + `package`) tras cambiarlo.

#### 7.2 IntelliJ: `Project JDK is not specified`
*File → Project Structure → Project → SDK* = JDK 11+ (o *Add SDK → Download JDK…* → Temurin 17). En la pestaña Maven, ejecutar `clean` y `package`, **no** `deploy`.

#### 7.3 `MqttException: Tiempo de espera excedido al esperar una respuesta del servidor`
El backend no conecta con EMQX. En el demo de DJI, `mqtt.BASIC.host` sirve para dos cosas: la conexión del propio backend y la dirección que se entrega a Pilot 2 en `pilot-login`. Por eso vale `SERVER_IP`, y Docker Desktop en Windows a veces no responde cuando el propio PC se conecta a su IP de red (desde fuera, el Dock sí llega).
→ Arrancar con `--mqtt.BASIC.host=127.0.0.1` (lo hace `arrancar_backend.ps1`).
Limitación: así arrancado, el mando **no** puede entrar por `pilot-login` (recibiría `127.0.0.1`). Pendiente separar ambas direcciones en el código.
Si aun así falla: `docker compose ps` (EMQX *Up*) y, si hace falta, *Quit Docker Desktop* → `wsl --shutdown` → abrir Docker → `docker compose up -d`.

#### 7.4 La IP del PC cambia
Al cambiar de red (cable de la empresa / Wi-Fi de invitados) o al día siguiente, la IPv4 puede cambiar. Hay que actualizar `SERVER_IP` en `iadron\.env`, `VITE_SERVER_IP` en `web\env\.env.local` **y la dirección MQTT del Dock** (paso 8), y reiniciar backend y web. Solución definitiva: IP fija/reservada para el PC o router propio.
Los dispositivos solo se ven si están en **la misma red** que el PC (la Wi-Fi de invitados y la red de cable de la empresa están separadas).

Comprobaciones:
- Swagger: `http://localhost:6789/swagger-ui/index.html`
- EMQX (`:18083` → *Clients*): aparecen dos clientes con usuario `JavaServer` (el backend; el Client ID es aleatorio).
- Login por API:
  ```powershell
  Invoke-RestMethod -Method Post -Uri http://localhost:6789/manage/api/v1/login -ContentType 'application/json' -Body '{"username":"adminPC","password":"adminPC","flag":1}'
  ```
  Debe devolver un `access_token`. → **Prueba 0 superada**.

---

## 8. Conectar el Dock 2 a la nube (IADron)

El Dock no usa `pilot-login`: se configura desde el mando conectado al Dock por cable.

1. Backend arrancado y conectado a EMQX (en *Clients* salen los `JavaServer`). **Si el backend no está conectado, el Dock recibe "Invalid organization ID or device binding code" aunque los datos sean correctos.**
2. Mando (RC Pro Enterprise) al Dock con cable **USB-A ↔ USB-C** → en Pilot 2 se abre **On-site Debugging**.
3. Pulsar el nombre bajo la imagen del Dock → **Cloud Service Configuration** → *Cloud Service* = **Third-Party Cloud Service**.
4. Rellenar:

| Campo | Valor |
|---|---|
| MQTT gateway address | `tcp://SERVER_IP:1883` (p. ej. `tcp://10.12.54.211:1883`) |
| MQTT account / password | `mqtt_username` / `mqtt_password` de `manage_user` (EMQX admite anónimos, pero mejor estas) |
| Org ID | `iadron-nave` |
| Binding code | `IADRON01` |

5. *Continuar* → debe indicar que está conectado a la nube de terceros.
6. Desconectar el mando y **reiniciar el Dock**.

Comprobar: EMQX → *Clients* muestra un cliente con el **número de serie del Dock** como Client ID (p. ej. `7CTXM8S00B00YC`); web `:8080` → *Devices → Dock* lo muestra en *IADron Nave*; con el dron encendido en el Dock, el mock imprime `POSE DOCK_OSD gw=<SN del Dock>`.

Consultas útiles:
```powershell
docker exec -it iadron_mysql mysql -uroot -proot cloud_sample -e "select workspace_id, workspace_name, bind_code from manage_workspace;"
docker exec -it iadron_mysql mysql -uroot -proot cloud_sample -e "select username, mqtt_username, mqtt_password from manage_user;"
```

### Problemas encontrados

#### 8.1 Timeout al pulsar *Continuar*
- Puerto equivocado: **18083 es el panel web de EMQX**; los dispositivos van por el **1883**.
- El Dock no está en la misma red que el PC, o el firewall bloquea el 1883.

#### 8.2 `Invalid organization ID or device binding code`
- El backend no está conectado a EMQX (7.3): nadie responde al Dock.
- Org ID o código mal escritos (p. ej. `IADRON1` en vez de `IADRON01`). Se comparan exactos.

#### 8.3 El campo Org ID corta el `workspace_id`
El UUID del demo (`e3dea0f5-37f2-4d79-ae58-490af3228069`) no cabe en el campo de Pilot 2. Se renombró a `iadron-nave` en todas las tablas (`03_iadron_workspace_id.sql`). En una base de datos ya creada se aplica con:
```powershell
Get-Content iadron\sql\03_iadron_workspace_id.sql | docker exec -i iadron_mysql mysql -uroot -proot
docker exec iadron_redis redis-cli flushall
```
Después: reiniciar el backend y, en la web, cerrar sesión y volver a entrar (el token llevaba el workspace antiguo).

---

## 9. Pendiente

- [ ] Separar en el código la dirección MQTT del backend (`127.0.0.1`) de la que se entrega a los dispositivos (`SERVER_IP`), para poder usar `pilot-login` con el mando sin el parámetro de arranque.
- [ ] IP fija/reservada para el PC en la red de pruebas (si cambia, hay que reconfigurar el Dock).
- [ ] Prueba 1 (RC Plus + Pilot 2) y siguientes: ver `README_IADRON.md`.
- [ ] Migrar al PC servidor Linux cuando esté disponible (allí Docker no necesita WSL; `set -a; source iadron/.env; set +a` para cargar variables).

---

## Usuarios y contraseñas por defecto (sólo red local)

| Servicio | Usuario | Contraseña |
|---|---|---|
| Web / API backend | `adminPC` | `adminPC` |
| Pilot 2 | `pilot` | `pilot123` |
| EMQX dashboard | `admin` | `public` |
| MinIO root | `minioadmin` | `minioadmin` |
| MinIO app (backend) | `iadron` | `iadron-minio-123` |
| MySQL | `root` | `root` |
| Workspace (Org ID del Dock) | `iadron-nave` | — |
| Workspace bind code | — | `IADRON01` |
| MQTT backend | `JavaServer` | `123456` |