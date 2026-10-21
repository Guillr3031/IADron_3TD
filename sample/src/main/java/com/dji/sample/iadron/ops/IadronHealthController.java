package com.dji.sample.iadron.ops;

import com.dji.sample.component.oss.service.impl.OssServiceContext;
import com.dji.sample.iadron.ia.IaForwardProperties;
import com.dji.sample.manage.model.common.NtpServerProperties;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sample.manage.service.IDeviceService;
import com.dji.sdk.cloudapi.device.DockModeCodeEnum;
import com.dji.sdk.cloudapi.device.OsdDock;
import com.dji.sdk.cloudapi.storage.CredentialsToken;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IADron: comprobación antes de volar.
 *  GET  /iadron/api/v1/health               -> lista de comprobaciones OK / WARN / FAIL
 *  POST /iadron/api/v1/health/reconnect-dock -> expulsa al Dock de EMQX para que reenvíe su topología
 */
@RestController
@RequestMapping("/iadron/api/v1/health")
public class IadronHealthController {

    private final DataSource dataSource;
    private final IDeviceRedisService redis;
    private final IDeviceService deviceService;
    private final OssServiceContext oss;
    private final IaForwardProperties iaProps;
    private final EmqxApiClient emqx;
    private final DockAutoReconnect autoReconnect;

    @Value("${mqtt.BASIC.username:}")
    private String backendMqttUser;

    @Value("${iadron.emqx.drone:1581F6Q8D247300G9889}")
    private String droneSn;

    public IadronHealthController(DataSource dataSource, IDeviceRedisService redis, IDeviceService deviceService,
                                  OssServiceContext oss, IaForwardProperties iaProps, EmqxApiClient emqx,
                                  DockAutoReconnect autoReconnect) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.deviceService = deviceService;
        this.oss = oss;
        this.iaProps = iaProps;
        this.emqx = emqx;
        this.autoReconnect = autoReconnect;
    }

    @GetMapping
    public Map<String, Object> health(@RequestParam(defaultValue = "7CTXM8S00B00YC") String dock) {
        List<Map<String, Object>> checks = new ArrayList<>();

        // --- Infraestructura -------------------------------------------------------------
        check(checks, "MySQL", () -> {
            try (Connection c = dataSource.getConnection()) {
                return c.isValid(2) ? ok("conectado") : fail("no responde");
            }
        });
        check(checks, "Redis", () -> {
            redis.checkDeviceOnline("iadron-health");
            return ok("conectado");
        });

        // --- EMQX ------------------------------------------------------------------------
        List<JsonNode> clients = null;
        try {
            clients = emqx.listClients();
        } catch (Exception e) {
            checks.add(item("EMQX (API)", "WARN", "API no accesible en " + emqx.getApiUrl()
                    + " (" + e.getMessage() + "). Revisa puerto 8081 y usuario."));
        }
        if (clients != null) {
            List<String> backends = clients.stream()
                    .filter(c -> backendMqttUser.equals(c.path("username").asText())
                            || c.path("clientid").asText().startsWith("iadron-backend"))
                    .map(c -> c.path("clientid").asText())
                    .collect(Collectors.toList());
            if (backends.size() == 2 && backends.stream().allMatch(id -> id.startsWith("iadron-backend"))) {
                checks.add(item("Servidor en EMQX", "OK", "un único servidor conectado " + backends));
            } else if (backends.isEmpty()) {
                checks.add(item("Servidor en EMQX", "FAIL", "el servidor no está conectado a EMQX"));
            } else {
                checks.add(item("Servidor en EMQX", "FAIL", backends.size()
                        + " conexiones de servidor: ¿hay otro servidor viejo en marcha? " + backends));
            }
            boolean dockInEmqx = clients.stream().anyMatch(c -> dock.equals(c.path("clientid").asText()));
            checks.add(item("Dock en EMQX", dockInEmqx ? "OK" : "FAIL",
                    dockInEmqx ? "conectado" : "el Dock no está conectado a EMQX (red/IP del Dock)"));
        }

        // --- Dispositivos ----------------------------------------------------------------
        boolean dockOnline = Boolean.TRUE.equals(redis.checkDeviceOnline(dock));
        checks.add(item("Dock en el servidor", dockOnline ? "OK" : "FAIL",
                dockOnline ? "online" : "offline en el servidor: pulsa «Reconectar Dock»"));

        boolean droneOnline = Boolean.TRUE.equals(redis.checkDeviceOnline(droneSn));
        checks.add(item("Dron", droneOnline ? "OK" : "WARN",
                droneOnline ? "online (" + deviceService.getDeviceMode(droneSn) + ")"
                        : "apagado o sin datos (normal si está dentro del Dock apagado)"));

        check(checks, "Estado del Dock", () -> {
            DockModeCodeEnum mode = deviceService.getDockMode(dock);
            if (mode == null) {
                return fail("sin datos");
            }
            return DockModeCodeEnum.IDLE == mode ? ok("IDLE: listo para despegar")
                    : warn(mode.name() + ": el despegue exige IDLE (cierra el modo debug)");
        });

        check(checks, "Posición GNSS del Dock", () -> {
            Optional<OsdDock> osd = redis.getDeviceOsd(dock, OsdDock.class);
            if (osd.isEmpty() || osd.get().getLatitude() == null) {
                return fail("sin OSD del Dock");
            }
            OsdDock d = osd.get();
            boolean zero = Math.abs(d.getLatitude()) < 0.0001 && Math.abs(d.getLongitude()) < 0.0001;
            return zero ? fail("lat/lng = 0: sin GNSS, el despegue automático no funcionará")
                    : ok(String.format(Locale.ROOT, "%.6f, %.6f, %.1f m", d.getLatitude(), d.getLongitude(), d.getHeight()));
        });

        // --- Fotos e IA ------------------------------------------------------------------
        check(checks, "MinIO (credenciales STS)", () -> {
            CredentialsToken t = oss.getCredentials();
            return t != null && t.getAccessKeyId() != null ? ok("credenciales generadas")
                    : fail("no se generan credenciales: el Dock no podrá subir fotos");
        });

        check(checks, "Servidor de hora (NTP)", () -> {
            String h = NtpServerProperties.host;
            return h == null || h.isBlank() || h.startsWith("Please")
                    ? warn("ntp.server.host sin configurar: el Dock puede tener la hora mal")
                    : ok(h);
        });

        check(checks, "DJI Thermal SDK", () -> new File(iaProps.getIrpPath()).isFile()
                ? ok("encontrado") : warn("no se encuentra " + iaProps.getIrpPath()));

        check(checks, "IA", () -> {
            if (!iaProps.isEnabled()) {
                return warn("reenvío desactivado (iadron.ia.enabled=false)");
            }
            URI u = URI.create(iaProps.getUrl());
            int port = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(u.getHost(), port), 2000);
            }
            return ok("accesible: " + iaProps.getUrl());
        });

        boolean anyFail = checks.stream().anyMatch(c -> "FAIL".equals(c.get("status")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", !anyFail);
        out.put("serverTime", LocalDateTime.now().toString());
        out.put("checks", checks);
        return out;
    }

    @PostMapping("/reconnect-dock")
    public Map<String, Object> reconnectDock(@RequestParam(defaultValue = "7CTXM8S00B00YC") String dock) {
        String why = autoReconnect.reconnect(dock, "reconexión manual");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", why == null);
        out.put("detail", why == null ? "Dock expulsado: se reconectará en unos segundos" : why);
        return out;
    }

    // ---- utilidades -----------------------------------------------------------------------

    private interface Check {
        String[] run() throws Exception;
    }

    private static void check(List<Map<String, Object>> list, String name, Check c) {
        try {
            String[] r = c.run();
            list.add(item(name, r[0], r[1]));
        } catch (Exception e) {
            list.add(item(name, "FAIL", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static String[] ok(String d) { return new String[]{"OK", d}; }
    private static String[] warn(String d) { return new String[]{"WARN", d}; }
    private static String[] fail(String d) { return new String[]{"FAIL", d}; }

    private static Map<String, Object> item(String name, String status, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("detail", detail);
        return m;
    }
}