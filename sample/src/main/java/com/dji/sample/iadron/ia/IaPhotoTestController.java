package com.dji.sample.iadron.ia;

import com.dji.sample.control.model.enums.DroneAuthorityEnum;
import com.dji.sample.control.model.param.DronePayloadParam;
import com.dji.sample.control.model.param.TakeoffToPointParam;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sdk.cloudapi.control.CommanderFlightModeEnum;
import com.dji.sdk.cloudapi.control.CommanderModeLostActionEnum;
import com.dji.sample.control.service.IControlService;
import com.dji.sdk.cloudapi.control.CameraModeSwitchRequest;
import com.dji.sdk.cloudapi.control.CameraPhotoTakeRequest;
import com.dji.sdk.cloudapi.control.LensStorageSettingsEnum;
import com.dji.sdk.cloudapi.control.PhotoStorageSetRequest;
import com.dji.sdk.cloudapi.control.api.AbstractControlService;
import com.dji.sdk.cloudapi.device.CameraModeEnum;
import com.dji.sdk.cloudapi.device.ExitWaylineWhenRcLostEnum;
import com.dji.sdk.cloudapi.device.OsdDock;
import com.dji.sdk.cloudapi.device.RcLostActionEnum;
import com.dji.sdk.cloudapi.wayline.RthModeEnum;
import com.dji.sdk.cloudapi.device.PayloadIndex;
import com.dji.sdk.config.version.GatewayManager;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.SDKManager;
import com.dji.sdk.mqtt.services.ServicesReplyData;
import com.dji.sdk.mqtt.services.TopicServicesResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * IADron: botón de "foto térmica de prueba" (lo usa /iadron-test.html).
 *
 *  POST /iadron/api/v1/ia/takeoff -> despegue vertical sobre el Dock a la altura indicada
 *  POST /iadron/api/v1/ia/photo   -> control de cámara + guardar solo térmica + modo foto + disparo
 *  GET  /iadron/api/v1/ia/events  -> últimas fotos recibidas del Dock y su envío a la IA
 *
 * Manda las órdenes directamente al Dock, sin las comprobaciones previas del demo
 * (canPublish), para que sea el propio Dock quien diga si puede o no y por qué.
 */
@Slf4j
@RestController
@RequestMapping("/iadron/api/v1/ia")
public class IaPhotoTestController {

    private final IControlService controlService;
    private final AbstractControlService sdkControl;
    private final IaForwarder forwarder;
    private final IDeviceRedisService deviceRedisService;

    public IaPhotoTestController(IControlService controlService, AbstractControlService sdkControl,
                                 IaForwarder forwarder, IDeviceRedisService deviceRedisService) {
        this.controlService = controlService;
        this.sdkControl = sdkControl;
        this.forwarder = forwarder;
        this.deviceRedisService = deviceRedisService;
    }

    /**
     * Despegue vertical: el punto objetivo es la posición del propio Dock, a "height" metros por encima.
     * target_height en Cloud API es altura ELIPSOIDAL (absoluta), por eso se suma la del Dock (OSD).
     * security_takeoff_height, rth_altitude y commander_flight_height son relativas al Dock.
     */
    @PostMapping("/takeoff")
    public Map<String, Object> takeoff(@RequestParam(defaultValue = "7CTXM8S00B00YC") String dock,
                                       @RequestParam(defaultValue = "2") double height,
                                       @RequestParam(defaultValue = "2") double maxSpeed) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<OsdDock> osd = deviceRedisService.getDeviceOsd(dock, OsdDock.class);
        if (osd.isEmpty() || osd.get().getLatitude() == null || osd.get().getHeight() == null) {
            out.put("ok", false);
            out.put("error", "No hay OSD del Dock con posición (latitud/altura) en Redis. ¿Está online?");
            return out;
        }
        OsdDock d = osd.get();
        if (Math.abs(d.getLatitude()) < 0.0001 && Math.abs(d.getLongitude()) < 0.0001) {
            out.put("ok", false);
            out.put("error", "El Dock no tiene posición GNSS (0,0). takeoff_to_point necesita posición.");
            return out;
        }

        TakeoffToPointParam p = new TakeoffToPointParam();
        p.setTargetLatitude(d.getLatitude().doubleValue());
        p.setTargetLongitude(d.getLongitude().doubleValue());
        p.setTargetHeight(d.getHeight() + height);
        p.setSecurityTakeoffHeight(height);
        p.setRthAltitude(height);
        p.setCommanderFlightHeight((float) height);
        p.setCommanderFlightMode(CommanderFlightModeEnum.SETTING_HEIGHT);
        p.setRthMode(RthModeEnum.PRESET_HEIGHT);
        p.setRcLostAction(RcLostActionEnum.HOVER);
        p.setExitWaylineWhenRcLost(ExitWaylineWhenRcLostEnum.EXECUTE_RC_LOST_ACTION);
        p.setCommanderModeLostAction(CommanderModeLostActionEnum.EXECUTE_RC_LOST_ACTION);
        p.setMaxSpeed(maxSpeed);
        out.put("dockLat", d.getLatitude());
        out.put("dockLng", d.getLongitude());
        out.put("dockEllipsoidHeight", d.getHeight());
        out.put("targetHeight", p.getTargetHeight());
        out.put("relativeHeight", height);
        log.warn("[IADRON-DESPEGUE] a {} m sobre el Dock (target_height {} elipsoidal)",
                height, p.getTargetHeight());
        try {
            HttpResultResponse r = controlService.takeoffToPoint(dock, p);
            out.put("ok", r.getCode() == HttpResultResponse.CODE_SUCCESS);
            out.put("dockReply", r.getMessage());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    @PostMapping("/photo")
    public Map<String, Object> takeThermalPhoto(@RequestParam(defaultValue = "7CTXM8S00B00YC") String dock,
                                                @RequestParam(defaultValue = "81-0-0") String payload) {
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dock", dock);
        out.put("payload", payload);
        out.put("steps", steps);

        GatewayManager gw = SDKManager.getDeviceSDK(dock);
        PayloadIndex idx = new PayloadIndex(payload);

        // 1. Control de la cámara (también la marca como controlada en BD/Redis, ver ControlServiceImpl).
        boolean ok = step(steps, "control de cámara", () -> {
            DronePayloadParam p = new DronePayloadParam();
            p.setPayloadIndex(payload);
            HttpResultResponse r = controlService.seizeAuthority(dock, DroneAuthorityEnum.PAYLOAD, p);
            return r.getCode() == HttpResultResponse.CODE_SUCCESS ? null : r.getMessage();
        });

        // 2. Guardar solo la imagen térmica (_T). Si falla, se sigue: no es imprescindible.
        if (ok) {
            step(steps, "guardar solo térmica (ir)", () -> reply(sdkControl.photoStorageSet(gw,
                    new PhotoStorageSetRequest().setPayloadIndex(idx)
                            .setPhotoStorageSettings(List.of(LensStorageSettingsEnum.INFRARED)))));
        }

        // 3. Modo foto (si la cámara estaba en Timed Shot o vídeo, el disparo fallaría).
        if (ok) {
            step(steps, "modo foto", () -> reply(sdkControl.cameraModeSwitch(gw,
                    new CameraModeSwitchRequest().setPayloadIndex(idx).setCameraMode(CameraModeEnum.PHOTO))));
        }

        // 4. Disparo.
        if (ok) {
            ok = step(steps, "hacer foto", () -> reply(sdkControl.cameraPhotoTake(gw,
                    new CameraPhotoTakeRequest().setPayloadIndex(idx))));
        }

        out.put("ok", ok);
        out.put("next", ok
                ? "Foto hecha. Si el dron está en vuelo (real o simulado), en unos segundos debería aparecer en /events."
                : "El Dock rechazó la orden: mira el paso que falla.");
        out.put("photoAt", System.currentTimeMillis());
        return out;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return forwarder.recentEvents();
    }

    /** Ejecuta un paso. Supplier devuelve null si fue bien, o el texto del error. */
    private boolean step(List<Map<String, Object>> steps, String name, Supplier<String> action) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("step", name);
        long t0 = System.currentTimeMillis();
        String error;
        try {
            error = action.get();
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        s.put("ok", error == null);
        s.put("ms", System.currentTimeMillis() - t0);
        if (error != null) {
            s.put("error", error);
        }
        steps.add(s);
        log.info("[IADRON-FOTO] {} -> {}", name, error == null ? "OK" : error);
        return error == null;
    }

    private static String reply(TopicServicesResponse<ServicesReplyData> resp) {
        if (resp == null || resp.getData() == null) {
            return "sin respuesta del Dock";
        }
        return resp.getData().getResult().isSuccess() ? null : String.valueOf(resp.getData().getResult());
    }
}