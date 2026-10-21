package com.dji.sample.iadron.ia;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * IADron: configuración del reenvío de imágenes a la IA de visión.
 * Se lee de application-iadron.yml (bloque iadron.ia).
 */
@Data
@Component
@ConfigurationProperties(prefix = "iadron.ia")
public class IaForwardProperties {

    /** Activa o desactiva el reenvío. */
    private boolean enabled = false;

    /** Endpoint HTTP de la IA que recibe el JSON. */
    private String url;

    /** BASE64: la imagen va dentro del JSON. URL: solo se envía una URL prefirmada de MinIO. */
    private SendMode sendMode = SendMode.BASE64;

    /** Solo reenviar los ficheros térmicos (R-JPEG, terminan en _T). */
    private boolean onlyThermal = true;

    /** Sufijos de fichero térmico, separados por comas (DJI_xxx_T.JPG; _R para las de prueba). */
    private String thermalSuffix = "_T,_R";

    /**
     * Qué se envía a la IA:
     *  THERMAL_IMAGE -> formato binario "TI" del equipo de IA (temperaturas + posición + actitud), en base64.
     *  RJPEG         -> el fichero R-JPEG tal cual, en base64.
     */
    private Payload payload = Payload.THERMAL_IMAGE;

    /** Ruta a dji_irp.exe del DJI Thermal SDK (convierte el R-JPEG en temperaturas). */
    private String irpPath = "C:/dji_thermal_sdk/utility/bin/windows/release_x64/dji_irp.exe";

    /** Tiempo máximo para dji_irp por imagen. */
    private int irpTimeoutMs = 10000;

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 15000;

    /** Hilos para enviar en paralelo sin bloquear al Dock. */
    private int threads = 2;

    public enum SendMode { BASE64, URL }

    public enum Payload { THERMAL_IMAGE, RJPEG }
}