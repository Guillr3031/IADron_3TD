package com.dji.sample.iadron.service;

import com.dji.sample.iadron.config.IadronProperties;
import com.dji.sample.iadron.model.CaptureEvent;
import com.dji.sample.iadron.model.DronePose;
import com.dji.sample.iadron.model.PositioningStatus;
import com.dji.sample.manage.model.dto.DeviceDTO;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sdk.cloudapi.control.OsdInfoPush;
import com.dji.sdk.cloudapi.device.DockDronePayload;
import com.dji.sdk.cloudapi.device.DronePositionState;
import com.dji.sdk.cloudapi.device.OsdDockDrone;
import com.dji.sdk.cloudapi.device.OsdRcDrone;
import com.dji.sdk.cloudapi.device.PositionFixedEnum;
import com.dji.sdk.cloudapi.device.RcDronePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Punto único donde entra toda la telemetría del dron (OSD del Dock, del RC y OSD de alta frecuencia DRC).
 *
 * - Calcula el estado GNSS (¿son fiables lat/lon?).
 * - Guarda la última pose por gateway y por dron (consultable por REST).
 * - Escribe un log JSON Lines por día en iadron.flight-log.directory para analizar deriva.
 * - Reenvía la pose al servidor central.
 */
@Slf4j
@Service
public class PoseTracker {

    @Autowired
    private IadronProperties properties;

    @Autowired
    private IadronEventPublisher publisher;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private ObjectMapper mapper;

    private final Map<String, DronePose> latestByGateway = new ConcurrentHashMap<>();
    private final Map<String, DronePose> latestByDrone = new ConcurrentHashMap<>();
    private final Map<String, PositioningStatus> positioningByDrone = new ConcurrentHashMap<>();
    /** Para registrar en log sólo los cambios de estado GNSS, no cada mensaje. */
    private final Map<String, Boolean> lastGnssUsable = new ConcurrentHashMap<>();

    private final ExecutorService logWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "iadron-flight-log");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------ entradas

    /** OSD del dron cuando el gateway es el Dock 2. */
    public void onDockDroneOsd(String droneSn, String gatewaySn, OsdDockDrone osd) {
        if (osd == null) {
            return;
        }
        PositioningStatus positioning = evaluate(droneSn, osd.getPositionState(), osd.getLatitude(), osd.getLongitude());
        DronePose.DronePoseBuilder b = DronePose.builder()
                .source(DronePose.Source.DOCK_OSD)
                .gatewaySn(gatewaySn)
                .droneSn(droneSn)
                .receivedAt(System.currentTimeMillis())
                .latitude(osd.getLatitude())
                .longitude(osd.getLongitude())
                .height(osd.getHeight())
                .elevation(osd.getElevation())
                .attitudeHead(osd.getAttitudeHead())
                .attitudePitch(osd.getAttitudePitch())
                .attitudeRoll(osd.getAttitudeRoll())
                .horizontalSpeed(osd.getHorizontalSpeed())
                .verticalSpeed(osd.getVerticalSpeed())
                .modeCode(osd.getModeCode() == null ? null : osd.getModeCode().name())
                .positioning(positioning);
        firstDockPayload(osd.getPayloads()).ifPresent(p -> b
                .gimbalPitch(p.getGimbalPitch())
                .gimbalRoll(p.getGimbalRoll())
                .gimbalYaw(p.getGimbalYaw())
                .payloadIndex(String.valueOf(p.getPayloadIndex()))
                .measureTargetDistance(p.getMeasureTargetDistance()));
        accept(b.build());
    }

    /** OSD del dron cuando el gateway es el mando (RC Plus con Pilot 2 en nube de terceros). */
    public void onRcDroneOsd(String droneSn, String gatewaySn, OsdRcDrone osd) {
        if (osd == null) {
            return;
        }
        PositioningStatus positioning = evaluate(droneSn, osd.getPositionState(), osd.getLatitude(), osd.getLongitude());
        DronePose.DronePoseBuilder b = DronePose.builder()
                .source(DronePose.Source.RC_OSD)
                .gatewaySn(gatewaySn)
                .droneSn(droneSn)
                .receivedAt(System.currentTimeMillis())
                .latitude(osd.getLatitude())
                .longitude(osd.getLongitude())
                .height(osd.getHeight())
                .elevation(osd.getElevation())
                .attitudeHead(osd.getAttitudeHead())
                .attitudePitch(osd.getAttitudePitch())
                .attitudeRoll(osd.getAttitudeRoll())
                .horizontalSpeed(osd.getHorizontalSpeed())
                .verticalSpeed(osd.getVerticalSpeed())
                .modeCode(osd.getModeCode() == null ? null : osd.getModeCode().name())
                .positioning(positioning);
        firstRcPayload(osd.getPayloads()).ifPresent(p -> b
                .gimbalPitch(p.getGimbalPitch())
                .gimbalRoll(p.getGimbalRoll())
                .gimbalYaw(p.getGimbalYaw())
                .payloadIndex(String.valueOf(p.getPayloadIndex()))
                .measureTargetDistance(p.getMeasureTargetDistance()));
        accept(b.build());
    }

    /** OSD de alta frecuencia en modo DRC (llega por thing/product/{dockSn}/drc/up). */
    public void onDrcOsd(String dockSn, OsdInfoPush osd) {
        if (osd == null) {
            return;
        }
        String droneSn = deviceRedisService.getDeviceOnline(dockSn)
                .map(DeviceDTO::getChildDeviceSn)
                .orElse(null);
        DronePose pose = DronePose.builder()
                .source(DronePose.Source.DRC_OSD)
                .gatewaySn(dockSn)
                .droneSn(droneSn)
                .receivedAt(System.currentTimeMillis())
                .latitude(osd.getLatitude())
                .longitude(osd.getLongitude())
                .height(osd.getHeight())
                .attitudeHead(osd.getAttitudeHead())
                .speedX(osd.getSpeedX())
                .speedY(osd.getSpeedY())
                .speedZ(osd.getSpeedZ())
                .gimbalPitch(osd.getGimbalPitch())
                .gimbalRoll(osd.getGimbalRoll())
                .gimbalYaw(osd.getGimbalYaw())
                // La OSD DRC no trae position_state: se adjunta el último estado conocido del dron.
                .positioning(droneSn == null ? null : positioningByDrone.get(droneSn))
                .build();
        accept(pose);
    }

    /** Registra en el log de vuelo y reenvía una captura (foto subida a MinIO). */
    public void onCapture(CaptureEvent capture) {
        DronePose pose = capture.getGatewaySn() == null ? null : latestByGateway.get(capture.getGatewaySn());
        if (pose == null && capture.getDroneSn() != null) {
            pose = latestByDrone.get(capture.getDroneSn());
        }
        capture.setPoseAtUpload(pose);
        writeLog("capture", capture);
        publisher.publishCapture(capture);
    }

    /** Eventos DRC sueltos (acks, retardo de enlace, parada de emergencia) para el log de vuelo. */
    public void logDrcEvent(String dockSn, String method, Object data) {
        writeLog("drc_" + method, Map.of("dock_sn", String.valueOf(dockSn), "data", data == null ? "" : data));
    }

    // ------------------------------------------------------------------ consultas

    public Optional<DronePose> latestByGateway(String gatewaySn) {
        return Optional.ofNullable(latestByGateway.get(gatewaySn));
    }

    public Optional<DronePose> latestByDrone(String droneSn) {
        return Optional.ofNullable(latestByDrone.get(droneSn));
    }

    public Optional<PositioningStatus> positioning(String droneSn) {
        return Optional.ofNullable(positioningByDrone.get(droneSn));
    }

    // ------------------------------------------------------------------ internos

    private void accept(DronePose pose) {
        if (pose.getGatewaySn() == null) {
            pose.setGatewaySn(pose.getDroneSn());
        }
        if (pose.getGatewaySn() == null) {
            return;
        }
        latestByGateway.put(pose.getGatewaySn(), pose);
        if (pose.getDroneSn() != null) {
            latestByDrone.put(pose.getDroneSn(), pose);
        }
        writeLog("pose", pose);
        publisher.publishPose(pose);
    }

    private PositioningStatus evaluate(String droneSn, DronePositionState state, Float lat, Float lon) {
        if (droneSn == null) {
            droneSn = "desconocido";
        }
        PositioningStatus status;
        if (state == null) {
            status = PositioningStatus.unknown(droneSn, "La OSD no trae position_state");
        } else {
            int sats = Objects.requireNonNullElse(state.getGpsNumber(), 0);
            int rtk = Objects.requireNonNullElse(state.getRtkNumber(), 0);
            PositionFixedEnum fix = state.getIsFixed();
            boolean coordsZero = lat == null || lon == null || (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6);
            int min = properties.getGnss().getMinSatellites();

            String reason;
            boolean usable;
            if (coordsZero) {
                usable = false;
                reason = "lat/lon a 0 o ausentes";
            } else if (Math.max(sats, rtk) < min) {
                usable = false;
                reason = "Satélites insuficientes (gps=" + sats + ", rtk=" + rtk + ", mínimo=" + min + ")";
            } else if (fix != null && fix != PositionFixedEnum.SUCCESSFUL) {
                usable = false;
                reason = "Fix RTK no conseguido (" + fix + ")";
            } else {
                usable = true;
                reason = "OK";
            }
            status = PositioningStatus.builder()
                    .droneSn(droneSn)
                    .gpsNumber(sats)
                    .rtkNumber(rtk)
                    .fixState(fix == null ? "UNKNOWN" : fix.name())
                    .quality(state.getQuality())
                    .gnssUsable(usable)
                    .reason(reason)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
        positioningByDrone.put(droneSn, status);

        Boolean previous = lastGnssUsable.put(droneSn, status.isGnssUsable());
        if (previous == null || previous != status.isGnssUsable()) {
            log.warn("[IADRON] Dron {}: GNSS {} ({})", droneSn,
                    status.isGnssUsable() ? "UTILIZABLE" : "NO utilizable", status.getReason());
            writeLog("gnss_change", status);
        }
        return status;
    }

    private Optional<DockDronePayload> firstDockPayload(List<DockDronePayload> payloads) {
        return payloads == null ? Optional.empty() : payloads.stream().filter(Objects::nonNull).findFirst();
    }

    private Optional<RcDronePayload> firstRcPayload(List<RcDronePayload> payloads) {
        return payloads == null ? Optional.empty() : payloads.stream().filter(Objects::nonNull).findFirst();
    }

    private void writeLog(String type, Object payload) {
        IadronProperties.FlightLog cfg = properties.getFlightLog();
        if (!cfg.isEnabled()) {
            return;
        }
        long ts = System.currentTimeMillis();
        logWriter.execute(() -> {
            try {
                Path dir = Paths.get(cfg.getDirectory());
                Files.createDirectories(dir);
                Path file = dir.resolve("vuelo-" + LocalDate.now() + ".jsonl");
                String line = mapper.writeValueAsString(Map.of("ts", ts, "type", type, "data", payload)) + "\n";
                Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("[IADRON] No se pudo escribir el log de vuelo: {}", e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        logWriter.shutdown();
    }
}
