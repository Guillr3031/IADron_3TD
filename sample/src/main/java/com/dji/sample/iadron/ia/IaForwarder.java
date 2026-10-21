package com.dji.sample.iadron.ia;

import com.dji.sample.component.oss.model.OssConfiguration;
import com.dji.sample.component.oss.service.impl.OssServiceContext;
import com.dji.sdk.cloudapi.media.FileUploadCallbackFile;
import com.dji.sdk.cloudapi.media.UploadCallbackFileMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IADron: reenvía a la IA las imágenes que el Dock sube a MinIO.
 *
 * Flujo: file_upload_callback -> onFileUploaded() -> (hilo aparte) descarga de MinIO
 *        -> payload THERMAL_IMAGE: R-JPEG -> temperaturas (DJI Thermal SDK) + actitud (XMP)
 *           -> binario "TI" del equipo de IA -> base64
 *        -> payload RJPEG: base64 del fichero tal cual
 *        -> POST JSON a la IA (campo imageBase64).
 */
@Slf4j
@Service
public class IaForwarder {

    private final IaForwardProperties props;
    private final OssServiceContext oss;
    private final ThermalDecoder thermalDecoder;
    private final ObjectMapper json = new ObjectMapper();

    private RestTemplate rest;
    private ExecutorService pool;

    public IaForwarder(IaForwardProperties props, OssServiceContext oss, ThermalDecoder thermalDecoder) {
        this.props = props;
        this.oss = oss;
        this.thermalDecoder = thermalDecoder;
    }

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.rest = new RestTemplate(factory);
        this.pool = Executors.newFixedThreadPool(Math.max(1, props.getThreads()));
        log.info("[IADRON-IA] Reenvío {} -> {} (modo {}, solo térmicas: {})",
                props.isEnabled() ? "ACTIVADO" : "desactivado", props.getUrl(),
                props.getSendMode(), props.isOnlyThermal());
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /** Últimos eventos (para la página de prueba /iadron-test.html). */
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();

    public List<Map<String, Object>> recentEvents() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private void record(String file, String status, Object detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", Instant.now().toString());
        e.put("file", file);
        e.put("status", status);
        e.put("detail", detail);
        synchronized (recent) {
            recent.addFirst(e);
            while (recent.size() > 30) {
                recent.removeLast();
            }
        }
    }

    /** Llamar desde fileUploadCallback. No bloquea: el envío va en otro hilo. */
    public void onFileUploaded(String gatewaySn, FileUploadCallbackFile file) {
        if (file == null) {
            return;
        }
        record(file.getName(), "subida a MinIO", file.getObjectKey());
        if (!props.isEnabled()) {
            record(file.getName(), "no enviada", "iadron.ia.enabled = false");
            return;
        }
        if (props.isOnlyThermal() && !isThermal(file.getName())) {
            log.debug("[IADRON-IA] {} no es térmica, no se envía", file.getName());
            record(file.getName(), "no enviada", "no es térmica (sufijos " + props.getThermalSuffix() + ")");
            return;
        }
        pool.submit(() -> {
            try {
                forwardFromStorage(gatewaySn, file);
            } catch (Exception e) {
                log.error("[IADRON-IA] Error reenviando {}: {}", file.getName(), e.toString());
                record(file.getName(), "ERROR", e.toString());
            }
        });
    }

    private void forwardFromStorage(String gatewaySn, FileUploadCallbackFile file) throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("gatewaySn", gatewaySn);
        extra.put("objectKey", file.getObjectKey());
        extra.put("metadata", metadataToMap(file.getMetadata()));

        if (props.getSendMode() == IaForwardProperties.SendMode.URL) {
            String url = oss.getObjectUrl(OssConfiguration.bucket, file.getObjectKey()).toString();
            IaSendResult r = send(newImageId(), file.getName(), null, url, extra);
            log.info("[IADRON-IA] {} enviada por URL -> HTTP {} en {} ms", file.getName(), r.httpStatus, r.totalMs);
            return;
        }

        byte[] bytes;
        try (InputStream in = oss.getObject(OssConfiguration.bucket, file.getObjectKey())) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0) {
            log.warn("[IADRON-IA] {} vacía o no encontrada en MinIO ({})", file.getName(), file.getObjectKey());
            return;
        }
        long tDownload = System.currentTimeMillis() - t0;
        IaSendResult r = send(newImageId(), file.getName(), bytes, null, extra);
        log.info("[IADRON-IA] {} ({} KB) -> HTTP {} | descarga {} ms, base64 {} ms, envío {} ms",
                file.getName(), bytes.length / 1024, r.httpStatus, tDownload, r.encodeMs, r.postMs);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("httpStatus", r.httpStatus);
        d.put("disparoHastaEnvioMs", r.captureToSendMs);
        d.put("descargaMs", tDownload);
        d.put("temperaturasMs", r.decodeMs);
        d.put("envioMs", r.postMs);
        d.put("respuestaIa", r.response);
        record(file.getName(), "enviada a la IA", d);
    }

    /**
     * PRUEBA: hace lo mismo que el flujo real (lee de MinIO -> base64 -> IA), pero con un objectKey
     * que tú eliges, sin dron. Devuelve también cuánto tardó la descarga de MinIO.
     */
    public Map<String, Object> testFromStorage(String objectKey) throws Exception {
        long t0 = System.currentTimeMillis();
        byte[] bytes;
        try (InputStream in = oss.getObject(OssConfiguration.bucket, objectKey)) {
            bytes = in.readAllBytes();
        }
        long downloadMs = System.currentTimeMillis() - t0;
        String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("objectKey", objectKey);
        extra.put("source", "test-minio");
        IaSendResult r = send(newImageId(), fileName, bytes, null, extra);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objectKey", objectKey);
        out.put("sizeBytes", bytes.length);
        out.put("payloadBytes", r.payloadBytes);
        out.put("httpStatus", r.httpStatus);
        out.put("minioDownloadMs", downloadMs);
        out.put("decodeMs", r.decodeMs);
        out.put("base64AndJsonMs", r.encodeMs);
        out.put("postMs", r.postMs);
        out.put("totalMs", downloadMs + r.totalMs);
        out.put("iaResponse", r.response);
        return out;
    }

    /**
     * Envía una imagen. Si bytes != null va en base64; si no, se envía imageUrl.
     * Público para poder probar sin dron (IaTestController).
     */
    public IaSendResult send(String imageId, String fileName, byte[] bytes, String imageUrl,
                             Map<String, Object> extra) throws Exception {
        IaSendResult result = new IaSendResult();
        long t0 = System.currentTimeMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imageId", imageId);
        body.put("fileName", fileName);
        body.put("sentAt", Instant.now().toString());
        if (extra != null) {
            body.putAll(extra);
        }
        if (bytes != null && props.getPayload() == IaForwardProperties.Payload.THERMAL_IMAGE) {
            // Formato del equipo de IA: R-JPEG -> temperaturas + actitud -> binario "TI" -> base64.
            long td = System.currentTimeMillis();
            DroneThermalImage img = thermalDecoder.build(bytes);
            byte[] bin = img.serialize();
            result.decodeMs = System.currentTimeMillis() - td;
            result.captureToSendMs = System.currentTimeMillis() - img.timestamp;
            log.info("[IADRON-IA] {} -> TI {}x{}, disparo hace {} ms (gimbal pitch {}º)",
                    fileName, img.width, img.height, result.captureToSendMs, img.gimbalX);
            body.put("format", "drone-thermal-image");
            body.put("width", img.width);
            body.put("height", img.height);
            body.put("sizeBytes", bin.length);
            body.put("sha256", sha256(bin));
            body.put("imageBase64", Base64.getEncoder().encodeToString(bin));
        } else if (bytes != null) {
            body.put("format", isThermal(fileName) ? "rjpeg" : "jpg");
            body.put("sizeBytes", bytes.length);
            body.put("sha256", sha256(bytes));                         // la IA puede verificar integridad
            body.put("imageBase64", Base64.getEncoder().encodeToString(bytes));
        } else {
            body.put("imageUrl", imageUrl);
        }
        return post(body, result, t0);
    }

    /**
     * IADron (tiempo real): envía una imagen TI ya construida (p. ej. de un fotograma del vídeo).
     * Mismo JSON que las fotos: format=drone-thermal-image, imageBase64 = binario "TI" en base64.
     */
    public IaSendResult sendThermalImage(DroneThermalImage img, String fileName,
                                         Map<String, Object> extra) throws Exception {
        IaSendResult result = new IaSendResult();
        long t0 = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imageId", newImageId());
        body.put("fileName", fileName);
        body.put("sentAt", Instant.now().toString());
        if (extra != null) {
            body.putAll(extra);
        }
        byte[] bin = img.serialize();
        body.put("format", "drone-thermal-image");
        body.put("width", img.width);
        body.put("height", img.height);
        body.put("sizeBytes", bin.length);
        body.put("sha256", sha256(bin));
        body.put("imageBase64", Base64.getEncoder().encodeToString(bin));
        result.captureToSendMs = System.currentTimeMillis() - img.timestamp;
        return post(body, result, t0);
    }

    /** Registra un evento propio en la tabla de la página de prueba. */
    public void recordEvent(String file, String status, Object detail) {
        record(file, status, detail);
    }

    private IaSendResult post(Map<String, Object> body, IaSendResult result, long t0) throws Exception {
        String payload = json.writeValueAsString(body);
        result.encodeMs = System.currentTimeMillis() - t0;
        result.payloadBytes = payload.length();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        long t1 = System.currentTimeMillis();
        ResponseEntity<String> resp = rest.postForEntity(
                Objects.requireNonNull(props.getUrl(), "iadron.ia.url no configurada"),
                new HttpEntity<>(payload, headers), String.class);
        result.postMs = System.currentTimeMillis() - t1;
        result.totalMs = System.currentTimeMillis() - t0;
        result.httpStatus = resp.getStatusCodeValue();
        result.response = resp.getBody();
        return result;
    }

    public boolean isThermal(String fileName) {
        if (fileName == null) {
            return false;
        }
        String base = fileName.toUpperCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        for (String suffix : props.getThermalSuffix().split(",")) {
            if (!suffix.isBlank() && base.endsWith(suffix.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> metadataToMap(UploadCallbackFileMetadata m) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (m == null) {
            return map;
        }
        map.put("createdTime", m.getCreatedTime() != null ? m.getCreatedTime().toString() : null);
        map.put("absoluteAltitude", m.getAbsoluteAltitude());
        map.put("relativeAltitude", m.getRelativeAltitude());
        map.put("gimbalYawDegree", m.getGimbalYawDegree());
        if (m.getShootPosition() != null) {
            map.put("lat", m.getShootPosition().getLat());
            map.put("lng", m.getShootPosition().getLng());
        }
        return map;
    }

    static String sha256(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String newImageId() {
        return UUID.randomUUID().toString();
    }

    public static class IaSendResult {
        public int httpStatus;
        public long captureToSendMs; // desde que el dron hizo la foto (EXIF) hasta ahora
        public long decodeMs;      // R-JPEG -> temperaturas (dji_irp)
        public long encodeMs;
        public long postMs;
        public long totalMs;
        public long payloadBytes;
        public String response;
    }
}