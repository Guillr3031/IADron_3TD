package com.dji.sample.iadron.ia;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IADron: endpoints de PRUEBA para el envío a la IA, sin necesidad de dron.
 *  - POST /iadron/api/v1/ia/test  -> subes un fichero (R-JPEG) y se envía a la IA configurada.
 *  - POST /iadron/api/v1/ia/mock  -> "IA falsa": recibe el JSON, decodifica y comprueba que llega intacto.
 */
@Slf4j
@RestController
@RequestMapping("/iadron/api/v1/ia")
public class IaTestController {

    private final IaForwarder forwarder;

    public IaTestController(IaForwarder forwarder) {
        this.forwarder = forwarder;
    }

    @PostMapping(value = "/test", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> test(@RequestParam("file") MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        IaForwarder.IaSendResult r = forwarder.send("test-" + System.currentTimeMillis(),
                file.getOriginalFilename(), bytes, null, Map.of("source", "test"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", file.getOriginalFilename());
        out.put("sizeBytes", bytes.length);
        out.put("payloadBytes", r.payloadBytes);
        out.put("httpStatus", r.httpStatus);
        out.put("decodeMs", r.decodeMs);
        out.put("base64AndJsonMs", r.encodeMs);
        out.put("postMs", r.postMs);
        out.put("totalMs", r.totalMs);
        out.put("iaResponse", r.response);
        return out;
    }

    /** Lee una imagen que ya está en MinIO (cloud-bucket) y la envía a la IA. */
    @PostMapping("/test-minio")
    public Map<String, Object> testMinio(@RequestParam("key") String objectKey) throws Exception {
        return forwarder.testFromStorage(objectKey);
    }

    @PostMapping("/mock")
    public Map<String, Object> mock(@RequestBody Map<String, Object> body) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        Object b64 = body.get("imageBase64");
        if (b64 == null) {
            out.put("ok", body.get("imageUrl") != null);
            out.put("mode", "url");
            out.put("imageUrl", body.get("imageUrl"));
            return out;
        }
        byte[] bytes = Base64.getDecoder().decode(b64.toString());
        String sha = IaForwarder.sha256(bytes);
        if ("drone-thermal-image".equals(body.get("format"))) {
            // Hace lo mismo que hará la IA: base64 -> bytes -> formato "TI".
            DroneThermalImage img = DroneThermalImage.deserialize(bytes);
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            double sum = 0;
            for (float t : img.thermalData) {
                min = Math.min(min, t);
                max = Math.max(max, t);
                sum += t;
            }
            out.put("ok", sha.equals(body.get("sha256")));
            out.put("receivedBytes", bytes.length);
            out.put("width", img.width);
            out.put("height", img.height);
            out.put("tempMinC", min);
            out.put("tempMaxC", max);
            out.put("tempAvgC", Math.round(sum / img.thermalData.length * 100) / 100.0);
            out.put("position", List.of(img.posX, img.posY, img.posZ));
            out.put("rotation", List.of(img.rotX, img.rotY, img.rotZ));
            out.put("gimbal", List.of(img.gimbalX, img.gimbalY, img.gimbalZ));
            out.put("timestamp", img.timestamp);
            log.info("[IADRON-IA mock] TI {}x{} temp {}..{} ºC", img.width, img.height, min, max);
            return out;
        }
        boolean isJpeg = bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
        boolean intact = sha.equals(body.get("sha256"));
        log.info("[IADRON-IA mock] {} -> {} bytes, JPEG: {}, íntegro: {}",
                body.get("fileName"), bytes.length, isJpeg, intact);
        out.put("ok", isJpeg && intact);
        out.put("receivedBytes", bytes.length);
        out.put("isJpeg", isJpeg);
        out.put("sha256Match", intact);
        return out;
    }
}