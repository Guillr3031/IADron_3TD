package com.dji.sample.iadron.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuración propia de IADron (prefijo {@code iadron} en application-iadron.yml).
 *
 * Pensada para operar el Matrice 3TD + Dock 2 en interior, SIN GNSS.
 */
@Data
@Component
@ConfigurationProperties(prefix = "iadron")
public class IadronProperties {

    /** Reenvío de eventos (pose, capturas) al servidor central. */
    private Forward forward = new Forward();

    /** Registro local de telemetría en JSON Lines para analizar deriva tras cada vuelo. */
    private FlightLog flightLog = new FlightLog();

    /** Criterio para decidir si el GNSS es utilizable y qué hacer si no lo es. */
    private Gnss gnss = new Gnss();

    /** Control relativo por DRC (equivalente al Virtual Stick del MSDK). */
    private Drc drc = new Drc();

    @Data
    public static class Forward {
        /** Si está vacío, no se reenvía nada (solo log local). Ej: http://192.168.1.50:8080 */
        private String centralServerUrl = "";
        /** Ruta donde el servidor central recibe la pose. */
        private String posePath = "/api/drone/pose";
        /** Ruta donde el servidor central recibe las capturas (fotos térmicas / visibles). */
        private String capturePath = "/api/drone/capture";
        /** Reenviar como mucho una pose cada N ms por dron (0 = todas). */
        private long poseMinIntervalMs = 100;
        private int timeoutMs = 2000;
    }

    @Data
    public static class FlightLog {
        private boolean enabled = true;
        private String directory = "logs/iadron";
    }

    @Data
    public static class Gnss {
        /** Satélites mínimos para considerar el GNSS utilizable. */
        private int minSatellites = 8;
        /**
         * Qué hacer con comandos que dependen de lat/lon (takeoff-to-point, fly-to-point)
         * cuando el GNSS no es utilizable:
         * WARN  = registrar aviso y enviar igualmente (útil para probar qué responde el Dock).
         * BLOCK = rechazar el comando antes de enviarlo.
         */
        private GuardMode guardMode = GuardMode.WARN;
    }

    public enum GuardMode { WARN, BLOCK }

    @Data
    public static class Drc {
        /** Frecuencia de envío de joysticks (el protocolo admite 2-10 Hz). */
        private int stickFrequencyHz = 10;
        /** Campo delay_time del protocolo (100-1000 ms). */
        private int delayTimeMs = 300;
        /** Periodo del heartbeat DRC. */
        private long heartbeatPeriodMs = 1000;
        /** Duración máxima de un comando de joystick: si el cliente no lo renueva, el dron se para. */
        private long maxCommandDurationMs = 1000;
        /** Límites conservadores para interior (el protocolo permite x,y ±17 m/s; h -4..5 m/s; w ±90 °/s). */
        private float maxHorizontalSpeed = 1.0f;
        private float maxVerticalSpeed = 0.5f;
        private float maxYawRate = 30f;
        /** clientId con el que el backend entra en modo DRC (sin navegador). */
        private String serverClientId = "iadron-server";
    }
}
