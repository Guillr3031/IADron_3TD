package com.dji.sample.iadron.nav;

import com.dji.sample.iadron.model.DronePose;
import com.dji.sample.iadron.model.StickCommandParam;
import com.dji.sample.iadron.service.DrcSessionService;
import com.dji.sample.iadron.service.PoseTracker;
import com.dji.sdk.mqtt.drc.DrcDownPublish;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * IADron - PRUEBA: conecta PointNavigator con el dron.
 *
 * Cada 100 ms:
 *   1. lee la última telemetría DRC (10 Hz) de PoseTracker y, si es nueva, actualiza la odometría;
 *   2. si hay un destino activo, calcula la orden y la manda por DrcSessionService (joystick DRC).
 *
 * La odometría corre siempre desde que se fija el origen, aunque no haya destino, para no perder la posición
 * entre un punto y el siguiente.
 */
@Slf4j
@Service
public class GoToPointService {

    /** Cada orden dura 300 ms: si este servicio deja de renovarla, el hombre muerto de DrcSessionService para el dron. */
    private static final long COMMAND_DURATION_MS = 300;

    // ---- Obstáculos (hsi_info_push). Distancias en mm.
    /** Parar si hay algo a menos de esto en la dirección de avance (sector de ±45°). */
    private static final int STOP_AROUND_MM = 1000;
    /** No subir con algo encima a menos de esto. */
    private static final int STOP_UP_MM = 800;
    /** No bajar con el suelo/objeto debajo a menos de esto. */
    private static final int STOP_DOWN_MM = 500;
    /** Sin lectura de obstáculos más reciente que esto -> no moverse. */
    private static final long HSI_STALE_MS = 1500;

    @Autowired
    private PoseTracker poseTracker;

    @Autowired
    private DrcSessionService drcSessionService;

    @Autowired
    private HsiTracker hsiTracker;

    @Autowired
    private DrcDownPublish drcDownPublish;

    private final Map<String, PointNavigator> navigators = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSampleAt = new ConcurrentHashMap<>();
    private final Map<String, String> lastLoggedState = new ConcurrentHashMap<>();

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "iadron-nav");
        t.setDaemon(true);
        return t;
    });

    public GoToPointService() {
        loop.scheduleAtFixedRate(this::tickAll, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** Fija el origen (0,0,0) en la posición y el rumbo actuales del dron. */
    public Map<String, Object> setOrigin(String dockSn) {
        DronePose p = freshDrcPose(dockSn);
        PointNavigator nav = navigators.computeIfAbsent(dockSn, k -> new PointNavigator());
        // Altura absoluta del despegue = height - elevation de la OSD normal (los dos valores del mismo mensaje)
        Double takeoff = poseTracker.latestDockOsd(dockSn)
                .filter(d -> d.getHeight() != null && d.getElevation() != null)
                .map(d -> (double) d.getHeight() - d.getElevation())
                .orElse(null);
        nav.setOrigin(p.getAttitudeHead(), p.getHeight(), takeoff, p.getReceivedAt());
        lastSampleAt.put(dockSn, p.getReceivedAt());
        log.info("[IADRON-NAV] {} {}", dockSn, nav.getMessage());
        return nav.status();
    }

    public Map<String, Object> goTo(String dockSn, double x, double y, double z, Double yaw) {
        if (!drcSessionService.isActive(dockSn)) {
            throw new IllegalStateException("No hay sesión DRC: llama antes a /drc/enter");
        }
        PointNavigator nav = navigators.get(dockSn);
        if (nav == null) {
            throw new IllegalStateException("Origen sin fijar: llama antes a /nav/origin");
        }
        nav.start(x, y, z, yaw, System.currentTimeMillis());
        log.info("[IADRON-NAV] {} {}", dockSn, nav.getMessage());
        return nav.status();
    }

    public Map<String, Object> stop(String dockSn) {
        PointNavigator nav = navigators.get(dockSn);
        if (nav != null) {
            nav.cancel("Parado por el usuario");
        }
        if (drcSessionService.isActive(dockSn)) {
            drcSessionService.hold(dockSn);
        }
        log.info("[IADRON-NAV] {} parado por el usuario", dockSn);
        return status(dockSn);
    }

    /**
     * Aterriza donde está (drc_emergency_landing): según DJI, si la detección de obstáculos está activa,
     * el aterrizaje se PAUSA si detecta algo debajo. drone_emergency_stop lo cancela.
     * Al terminar, el dron queda en el suelo (no en el Dock): hay que recogerlo a mano.
     */
    public Map<String, Object> land(String dockSn) {
        PointNavigator nav = navigators.get(dockSn);
        if (nav != null) {
            nav.cancel("Aterrizando");
        }
        if (drcSessionService.isActive(dockSn)) {
            drcSessionService.hold(dockSn);   // palancas a 0 (ráfaga de ~0,5 s)
        }
        // Se manda cuando ha terminado la ráfaga de ceros, para que ninguna orden de joystick llegue después
        loop.schedule(() -> {
            drcDownPublish.publish(dockSn, "drc_emergency_landing");
            poseTracker.logDrcEvent(dockSn, "landing_sent", null);
            log.warn("[IADRON-NAV] {} ATERRIZAJE enviado (drc_emergency_landing)", dockSn);
        }, 700, TimeUnit.MILLISECONDS);
        return Map.of("state", "ATERRIZANDO", "message", "Aterrizaje enviado: baja donde está (se pausa si ve un obstáculo debajo)");
    }

    public Map<String, Object> status(String dockSn) {
        PointNavigator nav = navigators.get(dockSn);
        return nav == null ? Map.of("state", "SIN_ORIGEN", "message", "Fija el origen") : nav.status();
    }

    // ---------------------------------------------------------------- bucle

    private void tickAll() {
        navigators.forEach((dockSn, nav) -> {
            try {
                tick(dockSn, nav);
            } catch (RuntimeException e) {
                nav.cancel("Error: " + e.getMessage());
                log.error("[IADRON-NAV] {} error en el bucle de navegación", dockSn, e);
            }
        });
    }

    private void tick(String dockSn, PointNavigator nav) {
        // 1. Odometría con cada muestra NUEVA de la OSD DRC
        poseTracker.latestByGateway(dockSn).ifPresent(p -> {
            if (p.getSource() != DronePose.Source.DRC_OSD) {
                return;   // la OSD normal (0,5 Hz) no trae speed_x/speed_y
            }
            Long last = lastSampleAt.get(dockSn);
            if (last != null && p.getReceivedAt() <= last) {
                return;   // misma muestra que en el tick anterior
            }
            lastSampleAt.put(dockSn, p.getReceivedAt());
            if (p.getSpeedX() == null || p.getSpeedY() == null || p.getAttitudeHead() == null || p.getHeight() == null) {
                // Un 0 inventado haría creer que el dron está quieto mientras se mueve: mejor parar.
                nav.abortExternal("Telemetría DRC incompleta (falta velocidad, rumbo o altura)");
                return;
            }
            nav.onTelemetry(p.getSpeedX(), p.getSpeedY(), p.getAttitudeHead(), p.getHeight(), p.getReceivedAt());
        });

        // 2. Control
        boolean wasActive = nav.isActive();
        if (wasActive) {
            if (!drcSessionService.isActive(dockSn)) {
                nav.cancel("Sesión DRC cerrada");
            } else {
                PointNavigator.Command c = nav.step(System.currentTimeMillis());
                if (nav.isActive()) {
                    String blocked = obstacleCheck(dockSn, c);
                    if (blocked != null) {
                        nav.abortExternal(blocked);
                    }
                }
                if (nav.isActive()) {
                    StickCommandParam cmd = new StickCommandParam();
                    cmd.setX((float) c.x);
                    cmd.setY((float) c.y);
                    cmd.setH((float) c.h);
                    cmd.setW((float) c.w);
                    cmd.setDurationMs(COMMAND_DURATION_MS);
                    drcSessionService.stick(dockSn, cmd);
                } else {
                    drcSessionService.hold(dockSn);   // llegado o abortado: estacionario
                }
            }
            // Registro en el log de vuelo (logs/iadron) para analizar la deriva después
            poseTracker.logDrcEvent(dockSn, "nav", nav.status());
        }

        // Cambios de estado al log normal
        String st = nav.getState().name();
        if (!st.equals(lastLoggedState.put(dockSn, st)) && !st.equals("YENDO") && !st.equals("GIRANDO")) {
            log.info("[IADRON-NAV] {} {}", dockSn, nav.getMessage());
        }
    }

    /**
     * ¿Es seguro mandar esta orden? Devuelve el motivo si NO lo es.
     * c está en el marco del dron: x adelante, y derecha, h arriba.
     */
    private String obstacleCheck(String dockSn, PointNavigator.Command c) {
        HsiTracker.Reading r = hsiTracker.get(dockSn);
        if (r == null || System.currentTimeMillis() - r.receivedAt > HSI_STALE_MS) {
            return "Sin datos de los sensores de obstáculos (hsi_info_push): no me muevo a ciegas";
        }
        com.dji.sdk.cloudapi.control.HsiInfoPush d = r.data;
        if (Boolean.FALSE.equals(d.getHorizontalWork()) || Boolean.FALSE.equals(d.getHorizontalEnable())) {
            return "La detección de obstáculos horizontal está desactivada o no funciona";
        }
        double horiz = Math.hypot(c.x, c.y);
        if (horiz > 0.01) {
            // Ángulo de avance en el marco del dron, como around_distance: 0 = frente, 90 = derecha (horario)
            double angle = Math.toDegrees(Math.atan2(c.y, c.x));
            int min = HsiTracker.minAround(d, angle, 45);
            if (min == HsiTracker.NO_DATA) {
                return String.format("Sin lecturas de obstáculos hacia %.0f° (around_distance vacío o inválido)", angle);
            }
            if (min < STOP_AROUND_MM) {
                return String.format("Obstáculo a %d mm hacia %.0f° (límite %d mm)", min, angle, STOP_AROUND_MM);
            }
        }
        if (c.h > 0.01 && d.getUpDistance() != null && d.getUpDistance() > 0 && d.getUpDistance() < STOP_UP_MM) {
            return "Obstáculo encima a " + d.getUpDistance() + " mm";
        }
        if (c.h < -0.01 && d.getDownDistance() != null && d.getDownDistance() > 0 && d.getDownDistance() < STOP_DOWN_MM) {
            return "Suelo u objeto debajo a " + d.getDownDistance() + " mm";
        }
        return null;
    }

    private DronePose freshDrcPose(String dockSn) {
        DronePose p = poseTracker.latestByGateway(dockSn)
                .filter(x -> x.getSource() == DronePose.Source.DRC_OSD)
                .orElseThrow(() -> new IllegalStateException(
                        "No llega telemetría DRC de " + dockSn + ": ¿estás en DRC con el dron en el aire?"));
        long age = System.currentTimeMillis() - p.getReceivedAt();
        if (age > 1000 || p.getAttitudeHead() == null || p.getHeight() == null) {
            throw new IllegalStateException("Telemetría DRC antigua o incompleta (" + age + " ms)");
        }
        return p;
    }

    @PreDestroy
    void shutdown() {
        loop.shutdownNow();
    }
}