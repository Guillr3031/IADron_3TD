package com.dji.sample.iadron.thermal;

import com.dji.sample.component.oss.service.impl.OssServiceContext;
import com.dji.sample.iadron.config.IadronProperties;
import com.dji.sample.iadron.model.CaptureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * IADron: cada foto TÉRMICA que el Dock sube a MinIO durante el vuelo
 * se descarga y se manda a la IA como "A|<base64 TI>".
 * Corre en su hilo propio
 */
@Slf4j
@Service
public class ThermalForwarder {

    private final IadronProperties properties;
    private final OssServiceContext oss;
    private final ThermalPipeline pipeline;

    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), r -> {
        Thread t = new Thread(r, "iadron-thermal-ia");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());

    public ThermalForwarder(IadronProperties properties, OssServiceContext oss, ThermalPipeline pipeline) {
        this.properties = properties;
        this.oss = oss;
        this.pipeline = pipeline;
    }

    public void onCapture(CaptureEvent capture) {
        IadronProperties.Thermal cfg = properties.getThermal();
        if (!cfg.isEnabled() || !Boolean.TRUE.equals(capture.getThermal())
                || !StringUtils.hasText(capture.getObjectKey())) {
            return;
        }
        worker.execute(() -> {
            try (InputStream in = oss.getObject(capture.getBucket(), capture.getObjectKey())) {
                byte[] bytes = in.readAllBytes();
                if (bytes.length == 0) {
                    throw new IllegalStateException("objeto vacío o no encontrado en MinIO");
                }
                long ts = capture.getReceivedAt() > 0 ? capture.getReceivedAt() : System.currentTimeMillis();
                pipeline.process(capture.getFileName(), bytes, capture.getPoseAtUpload(), ts);
            } catch (Exception e) {
                log.warn("[IADRON-IA] No se pudo enviar {} a la IA: {}", capture.getObjectKey(), e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}