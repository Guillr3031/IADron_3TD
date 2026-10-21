package com.dji.sample.iadron.live;

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

    /** Retraso del vídeo respecto a la telemetría (se mide en la primera prueba). */
    @Value("${iadron.live.video-delay-ms:1500}")
    private long videoDelayMs;

    private final IaForwarder forwarder;
    private final ThermalOsdListener osd;
    private final IControlService controlService;
    private final AbstractControlService sdkControl;

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
                              IControlService controlService, AbstractControlService sdkControl) {
        this.forwarder = forwarder;
        this.osd = osd;
        this.controlService = controlService;
        this.sdkControl = sdkControl;
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
        if (s == null) {
            lastError = "no llega temperatura en la telemetría (¿dron encendido? ¿medición por área activa?)";
            return;
        }

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

        IaForwarder.IaSendResult r = forwarder.sendThermalImage(img, "live-" + capturedAt, extra);
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