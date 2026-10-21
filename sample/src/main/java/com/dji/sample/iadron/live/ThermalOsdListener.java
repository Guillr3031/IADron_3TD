package com.dji.sample.iadron.live;

import com.dji.sample.component.mqtt.config.MqttPropertyConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * IADron: escucha la telemetría del dron directamente de EMQX (cliente MQTT propio, solo lectura)
 * y guarda los últimos segundos de:
 *  - temperatura mín./máx. de la escena térmica:
 *      · OSD normal: cameras[].ir_metering_area.{min,max}_temperature_point.temperature
 *        (requiere medición por área activada; LiveThermalService la configura a pantalla completa)
 *      · DRC: drc_camera_osd_info_push -> ir_lense.thermal_global_temperature_{min,max}
 *  - actitud del dron (attitude_pitch/head/roll), gimbal (gimbal_pitch/yaw/roll) y altura (elevation).
 *
 * No toca el SDK del demo: es un cliente aparte ("iadron-thermal-osd") que solo se suscribe.
 */
@Slf4j
@Component
public class ThermalOsdListener {

    /** Una muestra de telemetría en un instante. */
    public static class Sample {
        public long at;
        public Double tMin, tMax;
        public String tSource;
        public Double pitch, head, roll;
        public Double gPitch, gYaw, gRoll;
        public Double elevation;

        Sample copy() {
            Sample s = new Sample();
            s.at = at; s.tMin = tMin; s.tMax = tMax; s.tSource = tSource;
            s.pitch = pitch; s.head = head; s.roll = roll;
            s.gPitch = gPitch; s.gYaw = gYaw; s.gRoll = gRoll; s.elevation = elevation;
            return s;
        }
    }

    private static final long KEEP_MS = 60_000;

    private final ObjectMapper json = new ObjectMapper();
    private final Deque<Sample> history = new ArrayDeque<>();
    private Sample last = new Sample();
    private volatile long lastTempAt;
    private MqttClient client;

    @Value("${mqtt.BASIC.username:}")
    private String username;

    @Value("${mqtt.BASIC.password:}")
    private String password;

    @Value("${iadron.emqx.drone:1581F6Q8D247300G9889}")
    private String droneSn;

    @Value("${iadron.emqx.docks:7CTXM8S00B00YC}")
    private String dockSn;

    @PostConstruct
    void start() {
        try {
            client = new MqttClient(MqttPropertyConfiguration.getBasicMqttAddress(),
                    "iadron-thermal-osd", new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setUserName(username);
            o.setPassword(password == null ? new char[0] : password.toCharArray());
            o.setAutomaticReconnect(true);
            o.setMaxReconnectDelay(5000);
            o.setCleanSession(true);
            o.setKeepAliveInterval(10);
            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    subscribe();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("[IADRON-LIVE] Telemetría: conexión con EMQX perdida, reintentando…");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handle(topic, message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            client.connect(o);
        } catch (Exception e) {
            log.error("[IADRON-LIVE] No se pudo conectar el lector de telemetría: {}", e.getMessage());
        }
    }

    private void subscribe() {
        try {
            client.subscribe(new String[]{
                    "thing/product/" + droneSn + "/osd",
                    "thing/product/" + dockSn.split(",")[0].trim() + "/drc/up"}, new int[]{0, 0});
            log.info("[IADRON-LIVE] Telemetría suscrita (osd del dron y drc/up del Dock)");
        } catch (MqttException e) {
            log.error("[IADRON-LIVE] Error al suscribir telemetría: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        try {
            if (client != null) {
                client.disconnectForcibly(1000);
                client.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void handle(String topic, byte[] payload) {
        try {
            JsonNode root = json.readTree(payload);
            JsonNode data = root.path("data");
            if (data.isMissingNode()) {
                return;
            }
            boolean tempUpdated;
            Sample snapshot;
            synchronized (history) {
                Sample s = last.copy();
                s.at = System.currentTimeMillis();
                tempUpdated = extract(data, s);
                last = s;
                history.addLast(s);
                while (!history.isEmpty() && s.at - history.peekFirst().at > KEEP_MS) {
                    history.removeFirst();
                }
                snapshot = s;
            }
            if (tempUpdated) {
                lastTempAt = snapshot.at;
            }
        } catch (Exception e) {
            log.debug("[IADRON-LIVE] Mensaje de telemetría ilegible en {}: {}", topic, e.getMessage());
        }
    }

    /** Busca en todo el JSON los campos que interesan. Devuelve true si había temperatura. */
    private boolean extract(JsonNode n, Sample s) {
        boolean temp = false;
        if (n.isObject()) {
            Iterator<String> it = n.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                JsonNode v = n.get(k);
                switch (k) {
                    case "thermal_global_temperature_max": s.tMax = v.asDouble(); s.tSource = "drc_global"; temp = true; break;
                    case "thermal_global_temperature_min": s.tMin = v.asDouble(); s.tSource = "drc_global"; temp = true; break;
                    case "max_temperature_point":
                        if (v.has("temperature")) { s.tMax = v.get("temperature").asDouble(); s.tSource = "ir_metering_area"; temp = true; }
                        break;
                    case "min_temperature_point":
                        if (v.has("temperature")) { s.tMin = v.get("temperature").asDouble(); s.tSource = "ir_metering_area"; temp = true; }
                        break;
                    case "attitude_pitch": s.pitch = v.asDouble(); break;
                    case "attitude_head": s.head = v.asDouble(); break;
                    case "attitude_roll": s.roll = v.asDouble(); break;
                    case "gimbal_pitch": s.gPitch = v.asDouble(); break;
                    case "gimbal_yaw": s.gYaw = v.asDouble(); break;
                    case "gimbal_roll": s.gRoll = v.asDouble(); break;
                    case "elevation": s.elevation = v.asDouble(); break;
                    default:
                        if (v.isContainerNode()) {
                            temp |= extract(v, s);
                        }
                }
            }
        } else if (n.isArray()) {
            for (JsonNode v : n) {
                temp |= extract(v, s);
            }
        }
        return temp;
    }

    /** Muestra más cercana al instante indicado (para casar un fotograma con su telemetría). */
    public Sample sampleAt(long time) {
        synchronized (history) {
            Sample best = null;
            long bestDiff = Long.MAX_VALUE;
            for (Sample s : history) {
                long d = Math.abs(s.at - time);
                if (d < bestDiff && s.tMax != null && s.tMin != null) {
                    best = s;
                    bestDiff = d;
                }
            }
            return best;
        }
    }

    public long lastTemperatureAt() {
        return lastTempAt;
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}