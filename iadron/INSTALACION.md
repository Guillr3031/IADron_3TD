# Instalación del entorno IADron 3TD (Windows)

Guía paso a paso para dejar funcionando el backend Cloud API de IADron (Matrice 3TD + Dock 2) en un PC con Windows 10/11.
Recoge todos los pasos y los problemas reales que han ido apareciendo. Para el plan de pruebas con el dron, ver `README_IADRON.md`.

> Estado de esta guía: pasos 1–4 probados en Windows 10 (19045); paso 5 con los arreglos de puertos y MinIO aplicados; pasos 6 en adelante, pendientes de confirmar.

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

La base de datos se inicializa sola la **primera vez** con `sql/cloud_sample.sql` (esquema de DJI) y `iadron/sql/02_iadron_seed.sql` (workspace "IADron Nave", bind code `IADRON01`).
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

Resultado esperado: **BUILD SUCCESS** y el jar en `sample\target\`.
Si sale `JAVA_HOME is not defined`: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"` (ruta del JDK instalado).

---

## 7. Arrancar el servidor central de prueba y el backend

Terminal 1 (raíz del repo):
```powershell
python iadron\tools\servidor_central_mock.py --port 8090
```

Terminal 2 (raíz del repo). PowerShell no tiene `source`; esto carga el `.env` en la sesión:
```powershell
Get-Content iadron\.env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object { $k,$v = $_ -split '=',2; Set-Item "env:$($k.Trim())" $v.Trim() }
java -jar (Get-ChildItem sample\target\sample-*.jar | Select-Object -First 1).FullName --spring.profiles.active=iadron
```

Comprobaciones:
- Swagger: `http://localhost:6789/swagger-ui/index.html`
- EMQX (`:18083` → *Clients*): aparece `iadron-backend`.
- Login por API:
  ```powershell
  Invoke-RestMethod -Method Post -Uri http://localhost:6789/manage/api/v1/login -ContentType 'application/json' -Body '{"username":"adminPC","password":"adminPC","flag":1}'
  ```
  Debe devolver un `access_token`. → **Prueba 0 superada**.

---

## 8. Pendiente

- [ ] Frontend `dji-sdk/Cloud-API-Demo-Web`: configurar URL del backend (`http://SERVER_IP:6789`) y credenciales DJI, y servirlo en la red local. Necesario para Pilot 2 y para vincular el Dock.
- [ ] Firewall de Windows: permitir entrada en 1883, 8083, 9000 y 6789 para que el Dock / RC Plus lleguen al PC.
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
| Workspace bind code | — | `IADRON01` |
