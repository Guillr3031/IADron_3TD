# Instalación del entorno IADron 3TD (Windows)

Guía paso a paso para dejar funcionando el backend Cloud API de IADron (Matrice 3TD + Dock 2) en un PC con Windows 10/11.
Recoge todos los pasos y los problemas reales que han ido apareciendo. Para el plan de pruebas con el dron, ver `README_IADRON.md`.

> Estado de esta guía: pasos 1–7 probados en Windows 10 (19045). Del paso 8 en adelante, pendiente de validar.

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
| Frontend `web/` (Cloud-API-Demo-Web) | Necesario para conectar Pilot 2 y vincular el Dock; panel web | Node.js en Windows (puerto 8080) |

Puertos usados en el host: **3307** (MySQL), 6379 (Redis), 1883 / 8083 / 18083 (EMQX), 9000 / 9001 (MinIO), 6789 (backend), 8080 (frontend web), 8090 (servidor central de prueba).

---

## 1. Requisitos

- **Git** para Windows.
- **JDK 11 o superior** (17 o 21 también valen; el proyecto compila a Java 11).
- **IntelliJ IDEA** (trae Maven incluido; no hace falta instalar Maven aparte).
- **Docker Desktop** con **WSL 2** (ver paso 4).
- **Python 3** (sólo para el servidor central de prueba).
- **Node.js 18+** (frontend web, paso 8).
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

Terminal 2 (raíz del repo). PowerShell no tiene `source`; esto carga el `.env` en la sesión:
```powershell
Get-Content iadron\.env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object { $k,$v = $_ -split '=',2; Set-Item "env:$($k.Trim())" $v.Trim() }
java -jar (Get-ChildItem sample\target\sample-*.jar | Select-Object -First 1).FullName --spring.profiles.active=iadron
```

Debe terminar con `Started CloudApiSampleApplication`.

### Problemas encontrados

#### 7.1 `Failed to bind properties under 'livestream.url.gb28181.local-port'`
El `application.yml` de DJI trae textos de ejemplo ("Please enter the local port.") en campos numéricos del vídeo en directo y el backend no arranca.
→ Corregido en `application-iadron.yml` con valores neutros (`livestream.url.*`). Como el yml va dentro del jar, **hay que recompilar** (`clean` + `package`) tras cambiarlo.

#### 7.2 IntelliJ: `Project JDK is not specified`
*File → Project Structure → Project → SDK* = JDK 11+ (o *Add SDK → Download JDK…* → Temurin 17). En la pestaña Maven, ejecutar `clean` y `package`, **no** `deploy`.

Comprobaciones:
- Swagger: `http://localhost:6789/swagger-ui/index.html`
- EMQX (`:18083` → *Clients*): aparece `iadron-backend`.
- Login por API:
  ```powershell
  Invoke-RestMethod -Method Post -Uri http://localhost:6789/manage/api/v1/login -ContentType 'application/json' -Body '{"username":"adminPC","password":"adminPC","flag":1}'
  ```
  Debe devolver un `access_token`. → **Prueba 0 superada**.

---

## 8. Frontend web (`web/`)

Es el demo web de DJI (`dji-sdk/Cloud-API-Demo-Web`, Vue 3 + Vite) ya copiado en la carpeta `web/` del repo.
**Hace falta** porque Pilot 2 (RC Plus) no habla directamente con el backend: abre una página de este frontend
que, mediante el JSBridge de Pilot 2, le pasa la configuración MQTT, el workspace y el token. También sirve como
panel web (dispositivos, medios, vídeo en directo).

Requisito: **Node.js 18 o superior** (`node --version`). Probado con Node 22.

```powershell
cd web
copy env\.env.example env\.env.local      # ignorado por git
notepad env\.env.local
```

Rellenar `VITE_SERVER_IP` (la misma `SERVER_IP` del backend) y `VITE_DJI_APP_ID` / `VITE_DJI_APP_KEY` / `VITE_DJI_APP_LICENSE` (las mismas del `iadron\.env`).

```powershell
npm install --legacy-peer-deps
npm run serve
```

Abre `http://localhost:8080` → login `adminPC` / `adminPC`.

### Cambios respecto al demo de DJI
- `src/api/http/config.ts` lee todo de `env/.env.local` (antes había que escribir las credenciales en el código).
- `.npmrc` apuntaba al mirror chino `registry.npmmirror.com`; ahora usa `registry.npmjs.org`.
- Se han regenerado `package-lock.json` (sin URLs de mirrors chinos) y se ha quitado `yarn.lock`.

### Problemas conocidos
- **El mapa no carga**: usa AMap (Gaode), que necesita una clave china. En interior no lo usamos; el resto de la web funciona.
- Si `npm install` falla por dependencias *peer*, usar siempre `--legacy-peer-deps`.
- Si `npm install` intenta descargar de `npmmirror.com` o `nlark.com`, queda un `yarn.lock` o un `package-lock.json` antiguo: borrarlos y repetir.

---

## 9. Firewall de Windows

El RC Plus y el Dock tienen que llegar al PC. PowerShell **como administrador**:

```powershell
New-NetFirewallRule -DisplayName "IADron backend" -Direction Inbound -Protocol TCP -LocalPort 1883,8083,9000,6789,8080 -Action Allow -Profile Private,Domain
```

Comprobar desde otro dispositivo de la misma red (por ejemplo el móvil): `http://SERVER_IP:8080` debe abrir la web.
Si la red Wi-Fi está marcada como **Pública** en Windows, la regla no aplica: cambiarla a *Privada* o añadir `Public` al `-Profile`.

---

## 10. Conectar Pilot 2 (RC Plus) — pendiente de validar

1. RC Plus y PC en la **misma red**.
2. Pilot 2 → *Servicios en la nube* → *Nube de terceros* → URL: `http://SERVER_IP:8080/pilot-login`
3. Login `pilot` / `pilot123` → la página registra Pilot 2 con el backend (MQTT, workspace `IADron Nave`).
4. En la consola del backend deben aparecer mensajes de dispositivo online; en EMQX (*Clients*) aparece el RC.

Para el Dock 2, la vinculación se hace desde el RC Plus en el modo de configuración del Dock, usando el bind code `IADRON01`.

---

## 11. Pendiente

- [ ] Validar el paso 10 con el RC Plus real.
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
