package com.dji.sample.iadron.ops;

import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * IADron: arregla solo el caso "el Dock está conectado a EMQX pero la web lo muestra desconectado".
 *
 * Pasa al reiniciar el servidor: Redis pierde la marca de "online" y el Dock no vuelve a mandar
 * su topología mientras siga conectado. Cada 30 s se comprueba; si EMQX dice "conectado" y Redis
 * dice "offline", se expulsa al Dock de EMQX y al reconectar manda update_topo.
 *
 * Seguridad: no se expulsa si el último OSD del dron en Redis indica que está en el aire.
 */
@Slf4j
@Component
public class DockAutoReconnect {

    private static final long COOLDOWN_MS = 120_000;

    private final EmqxApiClient emqx;
    private final IDeviceRedisService redis;
    private final Map<String, Long> lastKick = new ConcurrentHashMap<>();

    @Value("${iadron.emqx.auto-reconnect:true}")
    private boolean enabled;

    @Value("${iadron.emqx.docks:7CTXM8S00B00YC}")
    private String docks;

    @Value("${iadron.emqx.drone:1581F6Q8D247300G9889}")
    private String drone;

    public DockAutoReconnect(EmqxApiClient emqx, IDeviceRedisService redis) {
        this.emqx = emqx;
        this.redis = redis;
    }

    public List<String> dockList() {
        return Arrays.stream(docks.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    public void check() {
        if (!enabled) {
            return;
        }
        for (String dock : dockList()) {
            try {
                boolean inEmqx = emqx.isConnected(dock);
                boolean inRedis = Boolean.TRUE.equals(redis.checkDeviceOnline(dock));
                if (inEmqx && !inRedis) {
                    reconnect(dock, "conectado a EMQX pero offline en el servidor");
                }
            } catch (Exception e) {
                log.debug("[IADRON-EMQX] Comprobación de {} omitida: {}", dock, e.getMessage());
            }
        }
    }

    /** Expulsa al Dock para que reenvíe su topología. Devuelve el motivo si no lo hace. */
    public String reconnect(String dock, String reason) {
        if (droneAirborne()) {
            log.warn("[IADRON-EMQX] {} {}, pero el dron parece en vuelo: no se expulsa.", dock, reason);
            return "dron en vuelo: no se expulsa";
        }
        long now = System.currentTimeMillis();
        Long prev = lastKick.get(dock);
        if (prev != null && now - prev < COOLDOWN_MS) {
            return "expulsado hace menos de 2 min: espera";
        }
        lastKick.put(dock, now);
        log.warn("[IADRON-EMQX] {} {} -> se expulsa para que reenvíe su topología", dock, reason);
        return emqx.kick(dock) ? null : "EMQX no aceptó la expulsión";
    }

    private boolean droneAirborne() {
        Optional<OsdDockDrone> osd = redis.getDeviceOsd(drone, OsdDockDrone.class);
        return osd.map(o -> o.getElevation() != null && o.getElevation() > 0.5f).orElse(false);
    }
}