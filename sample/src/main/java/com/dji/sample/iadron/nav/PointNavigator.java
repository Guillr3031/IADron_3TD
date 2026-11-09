package com.dji.sample.iadron.nav;

/**
 * IADron - PRUEBA: ir a un punto (x, y, z, yaw), como flyTo() del Mini 4 Pro.
 *
 *  Recibe la telemetría y devuelve la orden de joystick.
 *
 * Marco ORIGEN:
 *   X/Y: (0,0)
 *   Z:   altura sobre el punto de DESPEGUE . Yaw: rumbo absoluto en grados.
 *
 * Posición: X/Y integrando la velocidad de la telemetría (odometría); Z = altura absoluta - altura absoluta del despegue.
 * Órdenes DRC (confirmado con la web oficial de DJI): x = adelante(+), y = derecha(+), h = arriba(+) en m/s;
 *   w = giro horario(+) en °/s.
 */
public class PointNavigator {

    // ---------------------------------------------------------------- ajustes (para afinar en pruebas)

    /** En qué marco llegan speed_x/speed_y de la telemetría DRC. HAY QUE COMPROBARLO en la prueba 1. */
    public enum SpeedFrame { NED, BODY }
    public static SpeedFrame SPEED_FRAME = SpeedFrame.NED;

    // Velocidades de prueba
    // DrcSessionService recorta además a iadron.drc.max-* (1,0 m/s / 0,5 m/s / 30 °/s).
    static final double MAX_SPEED = 0.3;        // m/s horizontal
    static final double MIN_SPEED = 0.05;       // m/s: Velocidad minima
    static final double SLOW_RADIUS = 0.8;      // m: cuando empieza a frenar
    static final double KP_Z = 0.6;
    static final double MAX_VZ = 0.3;           // m/s
    static final double Z_DEADBAND = 0.03;      // m
    static final double YAW_KP = 0.5;           // (°/s)/°
    static final double MAX_YAW_RATE = 20;      // °/s
    static final double MIN_YAW_RATE = 3;       // °/s
    static final double ARRIVE_YAW_RATE = 2;    // °/s: el giro tiene que haber parado
    static final double SMOOTH = 0.4;           // suavizado de órdenes (0 = nada)

    static final double ARRIVE_H = 0.15;        // m
    static final double ARRIVE_Z = 0.10;        // m
    static final double ARRIVE_SPEED = 0.10;    // m/s
    static final double ARRIVE_YAW = 5;         // °
    static final long SETTLE_MS = 1000;         // Quieto dentro de tolerancia
    static final long TIMEOUT_MS = 180000;      // Va por trayecto para quedarse colgado
    static final double MAX_RADIUS = 50;        // m: Radio de distancia aceptado
    static final double WRONG_DIR_DEG = 60;     // º Si el dron se tuerce mucho
    static final long WRONG_DIR_MS = 1500;      //Se para si detecta mal los ejes
    static final double WRONG_DIR_MIN_SPEED = 0.05;   // m/s: por debajo no se evalúa
    /** Orden de avance > NO_MOVE_CMD y velocidad medida < NO_MOVE_SPEED durante NO_MOVE_MS -> parar
     *  (velocidad no fiable, p. ej. modo ATTI, o dron bloqueado por la evitación de DJI). */ //sujeto a pruebas
    static final double NO_MOVE_CMD = 0.10;
    static final double NO_MOVE_SPEED = 0.03;
    static final long NO_MOVE_MS = 2000;
    static final long TELEMETRY_STALE_MS = 600; // sin telemetría nueva abortamos operacion

    // ---------------------------------------------------------------- estado

    public enum State { SIN_ORIGEN, PARADO, YENDO, GIRANDO, LLEGADO, ABORTADO }

    private boolean originSet;
    private double originHeading;
    private double takeoffHeight;     // altura absoluta del punto de despegue
    private boolean zFromTakeoff;     // Si sale falsa no hay referencia de despegue, z relativa
    private double x, y, z;
    private double vxO, vyO;          // velocidad en el marco origen
    private double heading;
    private double yawRate;           // °/s medida
    private long lastSampleMs;

    private Double tx, ty, tz, tyaw;  // destino
    private long startMs, nearSinceMs;
    private long wrongDirSinceMs;
    private long noMoveSinceMs;
    private long legMaxMs;
    private double lastCx, lastCy, lastCh, lastCw;
    private State state = State.SIN_ORIGEN;
    private String message = "Fija el origen";

    /** Orden de joystick en el marco del dron. */
    public static class Command {
        public final double x, y, h, w;
        Command(double x, double y, double h, double w) {
            this.x = x; this.y = y; this.h = h; this.w = w;
        }
        static final Command ZERO = new Command(0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- origen y odometría

    /**
     * @param height        altura ABSOLUTA actual (OSD DRC "height")
     * @param takeoffHeight altura ABSOLUTA del despegue (OSD normal: height - elevation), o null si no se conoce
     */
    public synchronized void setOrigin(double headingDeg, double height, Double takeoffHeight, long now) {
        originHeading = norm180(headingDeg);
        heading = headingDeg;
        zFromTakeoff = takeoffHeight != null;
        this.takeoffHeight = zFromTakeoff ? takeoffHeight : height;
        x = 0; y = 0; vxO = 0; vyO = 0;
        z = height - this.takeoffHeight;
        lastSampleMs = now;
        originSet = true;
        cancelTarget(State.PARADO, String.format("Origen fijado: rumbo %.1f°, Z = %.2f m %s", originHeading, z,
                zFromTakeoff ? "sobre el despegue" : "(SIN referencia de despegue: Z relativa a este punto)"));
    }

    /** Una muestra NUEVA de telemetría DRC. speedX/speedY según SPEED_FRAME. */
    public synchronized void onTelemetry(double speedX, double speedY, double headingDeg, double height, long sampleMs) {
        double dt = Math.max(0, Math.min(0.3, (sampleMs - lastSampleMs) / 1000.0));
        if (dt > 0) {
            yawRate = 0.5 * yawRate + 0.5 * norm180(headingDeg - heading) / dt;
        }
        heading = headingDeg;
        if (!originSet) {
            lastSampleMs = sampleMs;
            return;
        }
        lastSampleMs = sampleMs;
        if (SPEED_FRAME == SpeedFrame.NED) {
            double h0 = Math.toRadians(originHeading);
            vxO = speedX * Math.cos(h0) + speedY * Math.sin(h0);
            vyO = -speedX * Math.sin(h0) + speedY * Math.cos(h0);
        } else {
            double d = Math.toRadians(headingDeg - originHeading);
            vxO = speedX * Math.cos(d) - speedY * Math.sin(d);
            vyO = speedX * Math.sin(d) + speedY * Math.cos(d);
        }
        x += vxO * dt;
        y += vyO * dt;
        z = height - takeoffHeight;
    }

    // ---------------------------------------------------------------- destino

    public synchronized void start(double targetX, double targetY, double targetZ, Double yawDeg, long now) {
        if (!originSet) {
            throw new IllegalStateException("Origen sin fijar: llama antes a /nav/origin");
        }
        if (Math.hypot(targetX, targetY) > MAX_RADIUS) {
            throw new IllegalArgumentException("Destino fuera del radio permitido (" + MAX_RADIUS + " m)");
        }
        tx = targetX; ty = targetY; tz = targetZ; tyaw = yawDeg == null ? null : norm180(yawDeg);
        startMs = now;
        nearSinceMs = 0;
        wrongDirSinceMs = 0;
        noMoveSinceMs = 0;
        // Tiempo máximo según la distancia: 3 veces lo que tardaría a velocidad de crucero + 20 s
        double legDist = Math.sqrt(Math.pow(targetX - x, 2) + Math.pow(targetY - y, 2) + Math.pow(targetZ - z, 2));
        legMaxMs = 20000 + (long) (3000 * legDist / Math.min(MAX_SPEED, MAX_VZ));
        lastCx = lastCy = lastCh = lastCw = 0;
        state = State.YENDO;
        message = String.format("Yendo a (%.2f, %.2f, %.2f) yaw %s", tx, ty, tz, tyaw == null ? "libre" : String.format("%.0f°", tyaw));
    }

    /** Parada por un motivo de seguridad externo (obstáculos, telemetría incompleta). */
    public synchronized void abortExternal(String reason) {
        if (isActive()) {
            cancelTarget(State.ABORTADO, reason);
        }
    }

    public synchronized void cancel(String reason) {
        cancelTarget(originSet ? State.PARADO : State.SIN_ORIGEN, reason);
    }

    public synchronized boolean isActive() {
        return state == State.YENDO || state == State.GIRANDO;
    }

    /** Llamar cada 100 ms mientras isActive(). Devuelve la orden en el marco del dron. */
    public synchronized Command step(long now) {
        if (!isActive()) {
            return Command.ZERO;
        }
        if (now - lastSampleMs > TELEMETRY_STALE_MS) {
            return abort("Sin telemetría DRC desde hace " + (now - lastSampleMs) + " ms");
        }
        if (now - startMs > Math.min(TIMEOUT_MS, legMaxMs)) {
            return abort(String.format("Tiempo máximo (%d s) a %.2f m del destino", Math.min(TIMEOUT_MS, legMaxMs) / 1000, Math.hypot(tx - x, ty - y)));
        }

        double dx = tx - x, dy = ty - y, dz = tz - z;
        double dH = Math.hypot(dx, dy);
        double speed = Math.hypot(vxO, vyO);
        double yawErr = tyaw == null ? 0 : norm180(tyaw - heading);
        boolean okH = dH < ARRIVE_H, okZ = Math.abs(dz) < ARRIVE_Z;

        // ---- Le mando avanzar y la telemetría no ve movimiento: velocidad no fiable (¿modo ATTI?) -> parar
        if (Math.hypot(lastCx, lastCy) > NO_MOVE_CMD && speed < NO_MOVE_SPEED) {
            if (noMoveSinceMs == 0) noMoveSinceMs = now;
            if (now - noMoveSinceMs > NO_MOVE_MS) {
                return abort("Ordeno avanzar y la telemetría no ve movimiento en 2 s: velocidad no fiable o el dron está bloqueado");
            }
        } else {
            noMoveSinceMs = 0;
        }

        // ---- ¿Va hacia donde se le manda? (compara la orden anterior con la velocidad medida, marco origen)
        double cmdSpeed = Math.hypot(lastCx, lastCy);
        if (cmdSpeed > WRONG_DIR_MIN_SPEED && speed > WRONG_DIR_MIN_SPEED) {
            double cos = (lastCx * vxO + lastCy * vyO) / (cmdSpeed * speed);
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
            if (angle > WRONG_DIR_DEG) {
                if (wrongDirSinceMs == 0) wrongDirSinceMs = now;
                if (now - wrongDirSinceMs > WRONG_DIR_MS) {
                    return abort(String.format("El dron se mueve a %.0f° de la dirección ordenada: revisa SPEED_FRAME", angle));
                }
            } else {
                wrongDirSinceMs = 0;
            }
        } else {
            wrongDirSinceMs = 0;
        }

        boolean okYaw = Math.abs(yawErr) < ARRIVE_YAW;
        boolean yawStill = Math.abs(yawRate) < ARRIVE_YAW_RATE;

        // ---- Llegada: dentro de tolerancia y quieto durante SETTLE_MS, con el yaw alineado
        if (okH && okZ && speed < ARRIVE_SPEED) {
            if (nearSinceMs == 0) nearSinceMs = now;
        } else {
            nearSinceMs = 0;
        }
        if (nearSinceMs != 0 && now - nearSinceMs > SETTLE_MS && okYaw && yawStill) {
            cancelTarget(State.LLEGADO, String.format("Llegado a (%.2f, %.2f, %.2f) error %.2f m, yaw %.0f°",
                    x, y, z, dH, heading));
            return Command.ZERO;
        }

        double cx = 0, cy = 0, ch = 0, cw = 0;

        if (okH && okZ) {
            // ---- En el punto: solo girar (como en el Mini, el yaw se corrige al llegar)
            state = State.GIRANDO;
            if (!okYaw) {
                cw = clamp(yawErr * YAW_KP, MAX_YAW_RATE);
                if (Math.abs(cw) < MIN_YAW_RATE) cw = Math.signum(yawErr) * MIN_YAW_RATE;
            }
        } else {
            state = State.YENDO;
            // ---- Avance horizontal en el marco origen: frena al acercarse
            if (!okH) {
                double mag = MAX_SPEED * Math.min(1.0, dH / SLOW_RADIUS);
                mag = Math.max(MIN_SPEED, mag);
                cx = dx / dH * mag;
                cy = dy / dH * mag;
            }
            // ---- Altura
            if (Math.abs(dz) > Z_DEADBAND) {
                ch = clamp(dz * KP_Z, MAX_VZ);
            }
        }

        // Suavizado
        cx = lastCx * SMOOTH + cx * (1 - SMOOTH);
        cy = lastCy * SMOOTH + cy * (1 - SMOOTH);
        ch = lastCh * SMOOTH + ch * (1 - SMOOTH);
        cw = lastCw * SMOOTH + cw * (1 - SMOOTH);
        lastCx = cx; lastCy = cy; lastCh = ch; lastCw = cw;

        // Marco origen -> marco del dron (mismo giro que en el Mini)
        double d = Math.toRadians(heading - originHeading);
        double bx = cx * Math.cos(d) + cy * Math.sin(d);
        double by = -cx * Math.sin(d) + cy * Math.cos(d);

        message = String.format("%s dH=%.2f dZ=%.2f yawErr=%.0f° v=%.2f", state, dH, dz, yawErr, speed);
        return new Command(bx, by, ch, cw);
    }

    // ---------------------------------------------------------------- consulta

    public synchronized java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("state", state.name());
        m.put("message", message);
        m.put("origin_set", originSet);
        m.put("origin_heading", round(originHeading));
        m.put("x", round(x));
        m.put("y", round(y));
        m.put("z", round(z));
        m.put("z_ref", zFromTakeoff ? "despegue" : "origen");
        m.put("heading", round(heading));
        m.put("yaw_rate", round(yawRate));
        m.put("vx_origin", round(vxO));
        m.put("vy_origin", round(vyO));
        m.put("speed_frame", SPEED_FRAME.name());
        if (tx != null) {
            m.put("target", java.util.Arrays.asList(round(tx), round(ty), round(tz), tyaw == null ? null : round(tyaw)));
        }
        return m;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized String getMessage() {
        return message;
    }

    // ---------------------------------------------------------------- internos

    private Command abort(String reason) {
        cancelTarget(State.ABORTADO, reason);
        return Command.ZERO;
    }

    private void cancelTarget(State newState, String msg) {
        tx = ty = tz = tyaw = null;
        state = newState;
        message = msg;
        lastCx = lastCy = lastCh = lastCw = 0;
    }

    static double norm180(double a) {
        return ((a % 360.0) + 540.0) % 360.0 - 180.0;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}