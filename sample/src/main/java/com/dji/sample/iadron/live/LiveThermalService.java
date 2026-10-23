package com.dji.sample.iadron.live;

import com.dji.sample.component.oss.model.OssConfiguration;
import com.dji.sample.component.oss.service.impl.OssServiceContext;
import com.dji.sample.control.model.enums.DroneAuthorityEnum;
import com.dji.sample.control.model.param.DronePayloadParam;
import com.dji.sample.control.service.IControlService;
import com.dji.sample.iadron.ia.DroneThermalImage;
import com.dji.sample.iadron.ia.IaForwarder;
import com.dji.sdk.cloudapi.control.IrMeteringAreaSetRequest;
import com.dji.sdk.cloudapi.control.IrMeteringModeSetRequest;
import com.dji.sdk.cloudapi.control.MeteringModeEnum;
import com.dji.sdk.cloudapi.control.api.AbstractControlService;
import com.dji.sdk.cloudapi.device.PayloadIndex;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.SDKManager;
import com.dji.sdk.config.version.GatewayManager;
import com.dji.sdk.mqtt.services.ServicesReplyData;
import com.dji.sdk.mqtt.services.TopicServicesResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IADron: detección en tiempo real (opción A).
 *
 *  vídeo térmico (MediaMTX, RTSP) --ffmpeg 1 fps--> fotograma 640x512
 *     + temperatura mín./máx. del OSD del mismo instante (ThermalOsdListener)
 *     -> temperatura APROXIMADA por píxel: t = tMin + (gris - gMin) / (gMax - gMin) * (tMax - tMin)
 *     -> formato "TI" del equipo de IA -> base64 -> IA (mismo JSON que las fotos, con approximate=true)
 *
 *  Cada fotograma enviado se guarda también en MinIO (iadron/live/<fecha>/...):
 *    - <hora>.png  imagen en escala de grises, para verla
 *    - <hora>.ti   el binario "TI" exacto que recibió la IA
 *  El servidor solo prepara y envía las imágenes: la detección la hace la IA.
 *
 * Requisitos: directo térmico activo en MediaMTX, paleta en blanco y negro ("white hot"),
 * ffmpeg instalado. La temperatura por píxel es aproximada: la exacta llega con los R-JPEG al aterrizar.
 */
@Slf4j
@Service
public class LiveThermalService {

    @Value("${iadron.live.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${iadron.live.rtsp-url:rtsp://localhost:8554/live/1581F6Q8D247300G9889-81-0-0}")
    private String defaultRtspUrl;

    @Value("${iadron.live.fps:1}")
    private double fps;

    @Value("${iadron.live.width:640}")
    private int width;

    @Value("${iadron.live.height:512}")
    private int height;

    /** Retraso del vídeo respecto a la telemetría. */
    @Value("${iadron.live.video-delay-ms:1500}")
    private long videoDelayMs;

    /** Guardar cada fotograma enviado en MinIO */
    @Value("${iadron.live.save-minio:true}")
    private boolean saveMinio;

    /** Carpeta local donde guardar copia de cada fotograma enviado */
    @Value("${iadron.live.save-dir:}")
    private String saveDir;

    /** Cuántos fotogramas guardar como máximo */
    @Value("${iadron.live.save-keep:300}")
    private int saveKeep;

    private final IaForwarder forwarder;
    private final ThermalOsdListener osd;
    private final IControlService controlService;
    private final AbstractControlService sdkControl;
    private final OssServiceContext oss;
    private final java.util.Deque<Map<String, Object>> captures = new java.util.ArrayDeque<>();

    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong framesRead = new AtomicLong();
    private final AtomicLong framesSent = new AtomicLong();
    private final AtomicLong framesSkipped = new AtomicLong();

    private volatile Thread reader;
    private volatile Process ffmpeg;
    private volatile String rtspUrl;
    private volatile String lastError;
    private volatile String meteringSetup;
    private volatile Map<String, Object> lastFrame;

    public LiveThermalService(IaForwarder forwarder, ThermalOsdListener osd,
                              IControlService controlService, AbstractControlService sdkControl,
                              OssServiceContext oss) {
        this.forwarder = forwarder;
        this.osd = osd;
        this.controlService = controlService;
        this.sdkControl = sdkControl;
        this.oss = oss;
    }

    @PreDestroy
    void shutdown() {
        stop();
        sender.shutdownNow();
    }

    // ---------------------------------------------------------------- control

    public synchronized Map<String, Object> start(String url, String dock, String payload) {
        if (running.get()) {
            return status();
        }
        rtspUrl = (url == null || url.isBlank()) ? defaultRtspUrl : url;
        lastError = null;
        framesRead.set(0);
        framesSent.set(0);
        framesSkipped.set(0);
        meteringSetup = setupMetering(dock, payload);
        running.set(true);
        reader = new Thread(this::readLoop, "iadron-live-reader");
        reader.setDaemon(true);
        reader.start();
        log.info("[IADRON-LIVE] Tiempo real iniciado: {} a {} fps", rtspUrl, fps);
        return status();
    }

    public synchronized Map<String, Object> stop() {
        running.set(false);
        Process p = ffmpeg;
        if (p != null) {
            p.destroyForcibly();
        }
        log.info("[IADRON-LIVE] Tiempo real detenido");
        return status();
    }

    /**
     * Activa la medición de temperatura por área en toda la imagen, para que el OSD normal
     * traiga el mín./máx. de la escena (sin necesidad de DRC). Si falla, se sigue: en DRC
     * también llegan las temperaturas globales.
     */
    private String setupMetering(String dock, String payload) {
        try {
            DronePayloadParam p = new DronePayloadParam();
            p.setPayloadIndex(payload);
            HttpResultResponse auth = controlService.seizeAuthority(dock, DroneAuthorityEnum.PAYLOAD, p);
            if (auth.getCode() != HttpResultResponse.CODE_SUCCESS) {
                return "sin control de cámara: " + auth.getMessage();
            }
            GatewayManager gw = SDKManager.getDeviceSDK(dock);
            PayloadIndex idx = new PayloadIndex(payload);
            String r1 = reply(sdkControl.irMeteringModeSet(gw,
                    new IrMeteringModeSetRequest().setPayloadIndex(idx).setMode(MeteringModeEnum.AREA)));
            String r2 = reply(sdkControl.irMeteringAreaSet(gw, new IrMeteringAreaSetRequest()
                    .setPayloadIndex(idx).setX(0f).setY(0f).setWidth(1f).setHeight(1f)));
            return (r1 == null && r2 == null) ? "medición por área en toda la imagen: OK"
                    : "medición por área: " + (r1 != null ? r1 : r2);
        } catch (Exception e) {
            return "medición por área no configurada: " + e.getMessage();
        }
    }

    private static String reply(TopicServicesResponse<ServicesReplyData> r) {
        if (r == null || r.getData() == null) {
            return "sin respuesta del Dock";
        }
        return r.getData().getResult().isSuccess() ? null : String.valueOf(r.getData().getResult());
    }

    // ---------------------------------------------------------------- vídeo

    private void readLoop() {
        int frameBytes = width * height * 3;
        while (running.get()) {
            try {
                ffmpeg = new ProcessBuilder(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error",
                        "-rtsp_transport", "tcp", "-fflags", "nobuffer", "-flags", "low_delay",
                        "-i", rtspUrl,
                        "-vf", "fps=" + fps + ",scale=" + width + ":" + height,
                        "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                try (InputStream in = ffmpeg.getInputStream()) {
                    DataInputStream din = new DataInputStream(in);
                    byte[] rgb = new byte[frameBytes];
                    while (running.get()) {
                        din.readFully(rgb);
                        long frameAt = System.currentTimeMillis();
                        framesRead.incrementAndGet();
                        onFrame(rgb.clone(), frameAt);
                    }
                }
            } catch (EOFException e) {
                lastError = "el vídeo se cortó o no existe (¿directo térmico activo en " + rtspUrl + "?)";
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                Process p = ffmpeg;
                if (p != null) {
                    p.destroyForcibly();
                }
            }
            if (running.get()) {
                log.warn("[IADRON-LIVE] {} -> reintento en 3 s", lastError);
                sleep(3000);
            }
        }
    }

    private void onFrame(byte[] rgb, long frameAt) {
        if (!sending.compareAndSet(false, true)) {
            framesSkipped.incrementAndGet();   // la IA aún no ha contestado al anterior: se descarta
            return;
        }
        sender.submit(() -> {
            try {
                process(rgb, frameAt);
            } catch (Exception e) {
                lastError = "envío: " + e.getMessage();
                log.warn("[IADRON-LIVE] Error enviando fotograma: {}", e.getMessage());
            } finally {
                sending.set(false);
            }
        });
    }

    private void process(byte[] rgb, long frameAt) throws Exception {
        // Telemetría del instante en que se captó la imagen (el vídeo llega con retraso).
        long capturedAt = frameAt - videoDelayMs;
        ThermalOsdListener.Sample s = osd.sampleAt(capturedAt);
        int n = width * height;
        int[] gray = new int[n];
        int gMin = 255, gMax = 0;
        long colour = 0;
        for (int i = 0, j = 0; i < n; i++, j += 3) {
            int r = rgb[j] & 0xFF, g = rgb[j + 1] & 0xFF, b = rgb[j + 2] & 0xFF;
            int y = (r * 299 + g * 587 + b * 114) / 1000;
            gray[i] = y;
            if (y < gMin) gMin = y;
            if (y > gMax) gMax = y;
            colour += Math.abs(r - g) + Math.abs(g - b);
        }
        boolean coloured = colour / n > 20;   // paleta de colores: la conversión gris->temperatura no vale

        if (s == null) {
            // Sin temperatura no se puede montar el TI para la IA, pero se guarda la imagen para poder verla.
            lastError = "no llega temperatura en la telemetría (¿dron encendido? ¿medición por área activa?): "
                    + "se guarda la imagen en MinIO pero NO se envía a la IA";
            String[] keys = saveToMinio(gray, null, capturedAt);
            rememberCapture(keys, capturedAt, null, null, "sin temperatura", 0);
            return;
        }

        float tMin = s.tMin.floatValue(), tMax = s.tMax.floatValue();
        float span = Math.max(1, gMax - gMin);
        float[] temps = new float[n];
        for (int i = 0; i < n; i++) {
            temps[i] = tMin + (gray[i] - gMin) / span * (tMax - tMin);
        }

        DroneThermalImage img = new DroneThermalImage();
        img.width = width;
        img.height = height;
        img.thermalData = temps;
        img.posX = 0f;
        img.posY = s.elevation != null ? s.elevation.floatValue() : 0f;
        img.posZ = 0f;
        img.rotX = f(s.pitch);
        img.rotY = f(s.head);
        img.rotZ = f(s.roll);
        img.gimbalX = f(s.gPitch);
        img.gimbalY = f(s.gYaw);
        img.gimbalZ = f(s.gRoll);
        img.timestamp = capturedAt;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", "live-video");
        extra.put("approximate", true);
        extra.put("tempMinC", tMin);
        extra.put("tempMaxC", tMax);
        extra.put("temperatureSource", s.tSource);
        extra.put("telemetryOffsetMs", s.at - capturedAt);
        extra.put("colourPalette", coloured);

        saveCopy(gray, capturedAt, tMin, tMax);
        String[] keys = saveToMinio(gray, img, capturedAt);
        if (keys != null) {
            extra.put("objectKey", keys[1]);   // la IA puede ver dónde está guardado
        }
        IaForwarder.IaSendResult r = forwarder.sendThermalImage(img, "live-" + capturedAt, extra);
        rememberCapture(keys, capturedAt, tMin, tMax, s.tSource, r.httpStatus);
        framesSent.incrementAndGet();

        Map<String, Object> lf = new LinkedHashMap<>();
        lf.put("at", Instant.ofEpochMilli(frameAt).toString());
        lf.put("tempMinC", tMin);
        lf.put("tempMaxC", tMax);
        lf.put("temperatureSource", s.tSource);
        lf.put("colourPalette", coloured);
        lf.put("httpStatus", r.httpStatus);
        lf.put("captureToIaMs", System.currentTimeMillis() - capturedAt);
        lf.put("postMs", r.postMs);
        lastFrame = lf;
        if (coloured) {
            lastError = "el vídeo tiene paleta de colores: pon la paleta en blanco y negro (white hot)";
        }
    }

    /**
     * Copia de control: PNG en escala de grises del fotograma tal como se envió.
     * Nombre: live-<hora>_<tmin>_<tmax>.png
     */
    private void saveCopy(int[] gray, long at, float tMin, float tMax) {
        if (saveDir == null || saveDir.isBlank()) {
            return;
        }
        try {
            java.io.File dir = new java.io.File(saveDir);
            dir.mkdirs();
            java.awt.image.BufferedImage bi =
                    new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            byte[] px = ((java.awt.image.DataBufferByte) bi.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < gray.length; i++) {
                px[i] = (byte) gray[i];
            }
            String name = String.format(java.util.Locale.ROOT, "live-%s_%.1fC_%.1fC.png",
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                            .format(java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault())),
                    tMin, tMax);
            javax.imageio.ImageIO.write(bi, "png", new java.io.File(dir, name));

            java.io.File[] files = dir.listFiles((d, n) -> n.startsWith("live-") && n.endsWith(".png"));
            if (files != null && files.length > saveKeep) {
                java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
                for (int i = 0; i < files.length - saveKeep; i++) {
                    files[i].delete();
                }
            }
        } catch (Exception e) {
            log.debug("[IADRON-LIVE] No se pudo guardar la copia: {}", e.getMessage());
        }
    }

    /** Sube a MinIO el PNG (para verlo) y el binario TI enviado. Devuelve {pngKey, tiKey}. */
    private String[] saveToMinio(int[] gray, DroneThermalImage img, long at) {
        if (!saveMinio) {
            return null;
        }
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault());
            String prefix = (OssConfiguration.objectDirPrefix == null || OssConfiguration.objectDirPrefix.isBlank()
                    ? "iadron" : OssConfiguration.objectDirPrefix) + "/live/"
                    + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(t) + "/"
                    + java.time.format.DateTimeFormatter.ofPattern("HHmmss-SSS").format(t);

            java.awt.image.BufferedImage bi =
                    new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            byte[] px = ((java.awt.image.DataBufferByte) bi.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < gray.length; i++) {
                px[i] = (byte) gray[i];
            }
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(bi, "png", png);

            oss.putObject(OssConfiguration.bucket, prefix + ".png", new java.io.ByteArrayInputStream(png.toByteArray()));
            if (img == null) {
                return new String[]{prefix + ".png", null};
            }
            oss.putObject(OssConfiguration.bucket, prefix + ".ti", new java.io.ByteArrayInputStream(img.serialize()));
            return new String[]{prefix + ".png", prefix + ".ti"};
        } catch (Exception e) {
            lastError = "no se pudo guardar en MinIO: " + e.getMessage();
            return null;
        }
    }

    private void rememberCapture(String[] keys, long at, Float tMin, Float tMax, String source, int http) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("at", Instant.ofEpochMilli(at).toString());
        c.put("pngKey", keys != null ? keys[0] : null);
        c.put("tiKey", keys != null ? keys[1] : null);
        c.put("tempMinC", tMin);
        c.put("tempMaxC", tMax);
        c.put("temperatureSource", source);
        c.put("sentToIa", http > 0);
        c.put("httpStatus", http);
        synchronized (captures) {
            captures.addFirst(c);
            while (captures.size() > 30) {
                captures.removeLast();
            }
        }
    }

    /** Últimas capturas con enlaces temporales de MinIO para verlas/descargarlas. */
    public java.util.List<Map<String, Object>> recentCaptures(int limit) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        synchronized (captures) {
            for (Map<String, Object> c : captures) {
                if (out.size() >= limit) {
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>(c);
                try {
                    if (c.get("pngKey") != null) {
                        m.put("pngUrl", oss.getObjectUrl(OssConfiguration.bucket, (String) c.get("pngKey")).toString());
                        if (c.get("tiKey") != null) {
                            m.put("tiUrl", oss.getObjectUrl(OssConfiguration.bucket, (String) c.get("tiKey")).toString());
                        }
                    }
                } catch (Exception ignored) {
                }
                out.add(m);
            }
        }
        return out;
    }

    private static float f(Double d) {
        return d == null ? 0f : d.floatValue();
    }

    // ---------------------------------------------------------------- estado

    public Map<String, Object> status() {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("running", running.get());
        st.put("rtspUrl", rtspUrl != null ? rtspUrl : defaultRtspUrl);
        st.put("fps", fps);
        st.put("framesRead", framesRead.get());
        st.put("framesSent", framesSent.get());
        st.put("framesSkipped", framesSkipped.get());
        st.put("videoDelayMs", videoDelayMs);
        st.put("telemetryConnected", osd.isConnected());
        long t = osd.lastTemperatureAt();
        st.put("lastTemperatureAgoMs", t == 0 ? null : System.currentTimeMillis() - t);
        st.put("metering", meteringSetup);
        st.put("lastFrame", lastFrame);
        st.put("saveMinio", saveMinio ? OssConfiguration.bucket + "/iadron/live/" : null);
        st.put("saveDir", saveDir == null || saveDir.isBlank() ? null : new java.io.File(saveDir).getAbsolutePath());
        st.put("lastError", lastError);
        return st;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}