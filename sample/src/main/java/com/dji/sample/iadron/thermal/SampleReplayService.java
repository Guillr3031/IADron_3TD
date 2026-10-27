package com.dji.sample.iadron.thermal;

import com.dji.sample.iadron.config.IadronProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * IADron - MODO PRUEBA: simula las fotos que llegarán por DRC con R-JPEG del dataset del
 * DJI Thermal SDK. Cada interval-ms coge la siguiente foto de la carpeta (en bucle) y la manda
 * a la IA por ThermalPipeline, el mismo camino que las fotos reales.
 *
 *   iadron.thermal.replay.enabled: true
 *   iadron.thermal.replay.dir: C:/iadron/dataset_m3td
 *   iadron.thermal.replay.interval-ms: 3000
 *
 * Las fotos del dataset no traen actitud: se envían con pose = 0 y timestamp = ahora.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "iadron.thermal.replay", name = "enabled", havingValue = "true")
public class SampleReplayService {

    private final IadronProperties properties;
    private final ThermalPipeline pipeline;
    private int next = 0;

    public SampleReplayService(IadronProperties properties, ThermalPipeline pipeline) {
        this.properties = properties;
        this.pipeline = pipeline;
        log.warn("[IADRON-IA] MODO PRUEBA activo: enviando fotos de {} cada {} ms",
                properties.getThermal().getReplay().getDir(), properties.getThermal().getReplay().getIntervalMs());
    }

    @Scheduled(fixedDelayString = "${iadron.thermal.replay.interval-ms:3000}", initialDelay = 5000)
    public void tick() {
        Path dir = Paths.get(properties.getThermal().getReplay().getDir());
        List<Path> fotos = listar(dir);
        if (fotos.isEmpty()) {
            log.warn("[IADRON-IA] No hay .jpg en {}", dir.toAbsolutePath());
            return;
        }
        Path f = fotos.get(next++ % fotos.size());
        try {
            pipeline.process(f.getFileName().toString(), Files.readAllBytes(f), null, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("[IADRON-IA] {}: {}", f.getFileName(), e.getMessage());
        }
    }

    private static List<Path> listar(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".jpg") || n.endsWith(".jpeg");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[IADRON-IA] No se puede leer {}: {}", dir.toAbsolutePath(), e.getMessage());
            return List.of();
        }
    }
}