package com.dji.sample.iadron.service;

import com.dji.sample.control.model.param.DrcModeParam;
import com.dji.sample.control.service.IDrcService;
import com.dji.sample.iadron.config.IadronProperties;
import com.dji.sample.iadron.model.StickCommandParam;
import com.dji.sdk.cloudapi.control.ControlMethodEnum;
import com.dji.sdk.cloudapi.control.DroneControlRequest;
import com.dji.sdk.cloudapi.control.HeartBeatRequest;
import com.dji.sdk.mqtt.drc.DrcDownPublish;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Control relativo del dron desde el backend mediante DRC (Direct Remote Control) del Dock.
 * Es el equivalente al Virtual Stick que ya usas con el MSDK en el Mini 4 Pro, y NO necesita GNSS:
 * se mandan velocidades en el marco del dron, no coordenadas.
 *
 * Requisitos del propio protocolo/demo:
 *  - Sólo funciona con el Dock como gateway (el RC Plus está excluido del DRC en Cloud API).
 *  - El dron tiene que estar YA en el aire para entrar en DRC (DrcServiceImpl lo comprueba).
 *  - El backend debe enviar heartbeat periódico mientras dure la sesión.
 *
 * Seguridad: cada comando dura como mucho iadron.drc.max-command-duration-ms; si el cliente no lo renueva
 * el backend envía ceros (el dron queda en estacionario). Las velocidades se recortan a iadron.drc.max-*.
 */
@Slf4j
@Service
public class DrcSessionService {

    /** Ráfaga de comandos a cero al terminar un movimiento, para asegurar la parada. */
    private static final int ZERO_BURST = 5;

    @Autowired
    private IDrcService drcService;

    @Autowired
    private DrcDownPublish drcDownPublish;

    @Autowired
    private IadronProperties properties;

    @Autowired
    private DrcLinkMonitor linkMonitor;

    @Autowired
    private PoseTracker poseTracker;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "iadron-drc");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static class Session {
        final String workspaceId;
        final AtomicLong stickSeq = new AtomicLong(0);
        final AtomicLong heartbeatSeq = new AtomicLong(0);
        volatile float x, y, h, w;
        volatile long activeUntil;
        volatile int zerosPending;
        ScheduledFuture<?> heartbeatTask;
        ScheduledFuture<?> stickTask;

        Session(String workspaceId) {
            this.workspaceId = workspaceId;
        }
    }

    public synchronized void enter(String workspaceId, String dockSn) {
        if (sessions.containsKey(dockSn)) {
            log.info("[IADRON] Sesión DRC ya activa en {}", dockSn);
            return;
        }
        drcService.deviceDrcEnter(workspaceId, drcParam(dockSn));

        Session s = new Session(workspaceId);
        IadronProperties.Drc cfg = properties.getDrc();
        s.heartbeatTask = scheduler.scheduleAtFixedRate(() -> sendHeartbeat(dockSn, s),
                0, cfg.getHeartbeatPeriodMs(), TimeUnit.MILLISECONDS);
        long period = 1000L / Math.max(2, Math.min(10, cfg.getStickFrequencyHz()));
        s.stickTask = scheduler.scheduleAtFixedRate(() -> stickTick(dockSn, s),
                period, period, TimeUnit.MILLISECONDS);
        sessions.put(dockSn, s);
        poseTracker.logDrcEvent(dockSn, "session_enter", null);
        log.info("[IADRON] Sesión DRC iniciada en {}", dockSn);
    }

    public synchronized void exit(String dockSn) {
        Session s = sessions.remove(dockSn);
        if (s == null) {
            throw new IllegalStateException("No hay sesión DRC del servidor en " + dockSn);
        }
        sendZeros(dockSn, s, ZERO_BURST);
        cancel(s);
        try {
            drcService.deviceDrcExit(s.workspaceId, drcParam(dockSn));
        } finally {
            linkMonitor.clear(dockSn);
            poseTracker.logDrcEvent(dockSn, "session_exit", null);
            log.info("[IADRON] Sesión DRC terminada en {}", dockSn);
        }
    }

    public void stick(String dockSn, StickCommandParam p) {
        Session s = requireSession(dockSn);
        IadronProperties.Drc cfg = properties.getDrc();
        s.x = clamp(p.getX(), cfg.getMaxHorizontalSpeed());
        s.y = clamp(p.getY(), cfg.getMaxHorizontalSpeed());
        s.h = clamp(p.getH(), cfg.getMaxVerticalSpeed());
        s.w = clamp(p.getW(), cfg.getMaxYawRate());
        s.activeUntil = System.currentTimeMillis() + Math.min(p.getDurationMs(), cfg.getMaxCommandDurationMs());
        s.zerosPending = ZERO_BURST;
    }

    /** Para el movimiento (estacionario). */
    public void hold(String dockSn) {
        Session s = requireSession(dockSn);
        s.activeUntil = 0;
        sendZeros(dockSn, s, ZERO_BURST);
    }

    /** Parada de emergencia DRC: el dron frena y queda en estacionario. Funciona aunque no haya sesión propia. */
    public void emergencyStop(String dockSn) {
        Session s = sessions.get(dockSn);
        if (s != null) {
            s.activeUntil = 0;
        }
        drcDownPublish.publish(dockSn, ControlMethodEnum.DRONE_EMERGENCY_STOP.getMethod());
        poseTracker.logDrcEvent(dockSn, "emergency_stop_sent", null);
        log.warn("[IADRON] PARADA DE EMERGENCIA enviada a {}", dockSn);
    }

    public boolean isActive(String dockSn) {
        return sessions.containsKey(dockSn);
    }

    // ------------------------------------------------------------------ internos

    private void stickTick(String dockSn, Session s) {
        try {
            if (System.currentTimeMillis() < s.activeUntil) {
                send(dockSn, s, s.x, s.y, s.h, s.w);
            } else if (s.zerosPending > 0) {
                s.zerosPending--;
                send(dockSn, s, 0, 0, 0, 0);
            }
        } catch (RuntimeException e) {
            log.error("[IADRON] Error enviando joystick a {}", dockSn, e);
        }
    }

    private void sendZeros(String dockSn, Session s, int times) {
        for (int i = 0; i < times; i++) {
            send(dockSn, s, 0, 0, 0, 0);
        }
    }

    private void send(String dockSn, Session s, float x, float y, float h, float w) {
        IadronProperties.Drc cfg = properties.getDrc();
        DroneControlRequest req = new DroneControlRequest()
                .setSeq(s.stickSeq.getAndIncrement())
                .setX(x).setY(y).setH(h).setW(w)
                .setFreq(Math.max(2, Math.min(10, cfg.getStickFrequencyHz())))
                .setDelayTime(Math.max(100, Math.min(1000, cfg.getDelayTimeMs())));
        drcDownPublish.publish(dockSn, ControlMethodEnum.DRONE_CONTROL.getMethod(), req, 1);
    }

    private void sendHeartbeat(String dockSn, Session s) {
        try {
            drcDownPublish.publish(dockSn, ControlMethodEnum.HEART_BEAT.getMethod(),
                    new HeartBeatRequest()
                            .setSeq(s.heartbeatSeq.getAndIncrement())
                            .setTimestamp(System.currentTimeMillis()),
                    1);
            long sinceAck = System.currentTimeMillis() - linkMonitor.get(dockSn).getLastHeartbeatAckAt();
            if (s.heartbeatSeq.get() > 5 && sinceAck > 5000) {
                log.warn("[IADRON] {} no contesta al heartbeat DRC desde hace {} ms", dockSn, sinceAck);
            }
        } catch (RuntimeException e) {
            log.error("[IADRON] Error enviando heartbeat a {}", dockSn, e);
        }
    }

    private Session requireSession(String dockSn) {
        Session s = sessions.get(dockSn);
        if (s == null) {
            throw new IllegalStateException("No hay sesión DRC del servidor en " + dockSn + ". Llama antes a /drc/enter.");
        }
        return s;
    }

    private DrcModeParam drcParam(String dockSn) {
        return DrcModeParam.builder()
                .clientId(properties.getDrc().getServerClientId())
                .dockSn(dockSn)
                .build();
    }

    private static float clamp(float v, float max) {
        return Math.max(-max, Math.min(max, v));
    }

    private static void cancel(Session s) {
        if (s.heartbeatTask != null) {
            s.heartbeatTask.cancel(false);
        }
        if (s.stickTask != null) {
            s.stickTask.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        sessions.forEach((sn, s) -> {
            try {
                sendZeros(sn, s, ZERO_BURST);
            } catch (RuntimeException ignored) {
                // apagando
            }
            cancel(s);
        });
        scheduler.shutdownNow();
    }
}
