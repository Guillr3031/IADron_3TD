package com.dji.sample.iadron.service;

import com.dji.sample.iadron.config.IadronProperties;
import com.dji.sample.iadron.model.PositioningStatus;
import com.dji.sample.manage.model.dto.DeviceDTO;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sdk.common.HttpResultResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Protección para los comandos del Dock que dependen de latitud/longitud
 * (takeoff-to-point, fly-to-point). En interior no hay GNSS: o se avisa (WARN) o se bloquea (BLOCK).
 */
@Slf4j
@Service
public class GnssGuard {

    @Autowired
    private IadronProperties properties;

    @Autowired
    private PoseTracker poseTracker;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    /**
     * @param gatewaySn SN del Dock (o del dron si se pasa directamente)
     * @param action    nombre del comando, para el log
     * @return vacío si se puede continuar; una respuesta de error si el modo es BLOCK y no hay GNSS
     */
    public Optional<HttpResultResponse> check(String gatewaySn, String action) {
        String droneSn = deviceRedisService.getDeviceOnline(gatewaySn)
                .map(DeviceDTO::getChildDeviceSn)
                .filter(sn -> !sn.isBlank())
                .orElse(gatewaySn);

        PositioningStatus status = poseTracker.positioning(droneSn)
                .orElse(PositioningStatus.unknown(droneSn, "Sin telemetría del dron (¿apagado dentro del Dock?)"));

        if (status.isGnssUsable()) {
            return Optional.empty();
        }

        String msg = String.format("[IADRON] '%s' en %s depende de GNSS y el dron %s no lo tiene: %s",
                action, gatewaySn, droneSn, status.getReason());

        if (properties.getGnss().getGuardMode() == IadronProperties.GuardMode.BLOCK) {
            log.warn(msg + " -> BLOQUEADO");
            return Optional.of(HttpResultResponse.error(msg));
        }
        log.warn(msg + " -> se envía igualmente (modo WARN). Anota el código de error que devuelva el Dock.");
        return Optional.empty();
    }
}
