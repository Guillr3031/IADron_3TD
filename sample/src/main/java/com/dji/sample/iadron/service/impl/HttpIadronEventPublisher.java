package com.dji.sample.iadron.service.impl;

import com.dji.sample.iadron.config.IadronProperties;
import com.dji.sample.iadron.model.CaptureEvent;
import com.dji.sample.iadron.model.DronePose;
import com.dji.sample.iadron.service.IadronEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Envía pose y capturas al servidor central por HTTP POST (JSON, snake_case como el resto del backend).
 * Fire-and-forget: un fallo del servidor central nunca afecta al flujo con el dron.
 */
@Slf4j
@Service
public class HttpIadronEventPublisher implements IadronEventPublisher {

    @Autowired
    private IadronProperties properties;

    @Autowired
    private ObjectMapper mapper;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** Último envío de pose por gateway, para limitar la frecuencia. */
    private final Map<String, Long> lastPoseSent = new ConcurrentHashMap<>();

    @Override
    public void publishPose(DronePose pose) {
        IadronProperties.Forward cfg = properties.getForward();
        if (!StringUtils.hasText(cfg.getCentralServerUrl())) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = pose.getGatewaySn() + "|" + pose.getSource();
        Long last = lastPoseSent.get(key);
        if (last != null && now - last < cfg.getPoseMinIntervalMs()) {
            return;
        }
        lastPoseSent.put(key, now);
        post(cfg.getCentralServerUrl() + cfg.getPosePath(), pose);
    }

    @Override
    public void publishCapture(CaptureEvent capture) {
        IadronProperties.Forward cfg = properties.getForward();
        if (!StringUtils.hasText(cfg.getCentralServerUrl())) {
            log.info("[IADRON] Captura recibida (sin servidor central configurado): {}", capture.getObjectKey());
            return;
        }
        post(cfg.getCentralServerUrl() + cfg.getCapturePath(), capture);
    }

    private void post(String url, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getForward().getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            log.warn("[IADRON] No se pudo enviar a {}: {}", url, err.getMessage());
                        } else if (resp.statusCode() >= 300) {
                            log.warn("[IADRON] {} respondió {}", url, resp.statusCode());
                        }
                    });
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("[IADRON] Error preparando envío a {}", url, e);
        }
    }
}
