package com.dji.sample.iadron.thermal;

import com.dji.sample.iadron.config.IadronProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** POST con el cuerpo en texto plano = "A|<base64>" para la IA */
@Component
@ConditionalOnProperty(prefix = "iadron.thermal", name = "transport", havingValue = "http", matchIfMissing = true)
public class HttpIaSender implements IaSender {

    private final IadronProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpIaSender(IadronProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(String message) throws Exception {
        IadronProperties.Thermal cfg = properties.getThermal();
        if (!StringUtils.hasText(cfg.getIaUrl())) {
            throw new IllegalStateException("URL IA no está configurada");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getIaUrl()))
                .timeout(Duration.ofMillis(cfg.getIaTimeoutMs()))
                .header("Content-Type", "text/plain; charset=us-ascii")
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.US_ASCII))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("La IA respondió " + res.statusCode() + ": " + res.body());
        }
    }
}