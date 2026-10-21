package com.dji.sample.iadron.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * IADron: cliente mínimo de la API REST de EMQX 4.4 (/api/v4).
 * Sirve para saber quién está conectado a EMQX y para expulsar (kick) al Dock,
 * de modo que al reconectar vuelva a mandar su topología (update_topo).
 *
 * Config (application-iadron.yml):
 *   iadron.emqx.api-url      http://localhost:8081/api/v4   (o http://localhost:18083/api/v4)
 *   iadron.emqx.api-user     admin
 *   iadron.emqx.api-password public
 */
@Slf4j
@Component
public class EmqxApiClient {

    private final ObjectMapper json = new ObjectMapper();
    private final RestTemplate rest;

    @Value("${iadron.emqx.api-url:http://localhost:8081/api/v4}")
    private String apiUrl;

    @Value("${iadron.emqx.api-user:admin}")
    private String apiUser;

    @Value("${iadron.emqx.api-password:public}")
    private String apiPassword;

    public EmqxApiClient() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(3000);
        this.rest = new RestTemplate(f);
    }

    public String getApiUrl() {
        return apiUrl;
    }

    private HttpEntity<Void> auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(apiUser, apiPassword);
        return new HttpEntity<>(h);
    }

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> r = rest.exchange(apiUrl + path, HttpMethod.GET, auth(), String.class);
        return json.readTree(r.getBody());
    }

    /** true/false si EMQX responde; lanza excepción si la API no es accesible. */
    public boolean isConnected(String clientId) throws Exception {
        JsonNode data = get("/clients/" + clientId).path("data");
        return data.isArray() && data.size() > 0 && data.get(0).path("connected").asBoolean(false);
    }

    /** Todos los clientes conectados (hasta 1000). */
    public List<JsonNode> listClients() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        get("/clients?_page=1&_limit=1000").path("data").forEach(out::add);
        return out;
    }

    /** Expulsa a un cliente. El Dock se reconecta solo en unos segundos. */
    public boolean kick(String clientId) {
        try {
            ResponseEntity<String> r = rest.exchange(apiUrl + "/clients/" + clientId,
                    HttpMethod.DELETE, auth(), String.class);
            boolean ok = json.readTree(r.getBody()).path("code").asInt(-1) == 0;
            log.warn("[IADRON-EMQX] Kick de {} -> {}", clientId, ok ? "OK" : r.getBody());
            return ok;
        } catch (Exception e) {
            log.error("[IADRON-EMQX] No se pudo expulsar a {}: {}", clientId, e.getMessage());
            return false;
        }
    }
}