package com.dji.sample.iadron.nav;

import com.dji.sdk.common.HttpResultResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IADron - PRUEBA: ir a un punto por DRC.
 *
 *   POST /iadron/api/v1/devices/{dockSn}/nav/origin   fija (0,0,0) donde está el dron
 *   POST /iadron/api/v1/devices/{dockSn}/nav/goto     {"x":1,"y":0,"z":0,"yaw":90}  (yaw opcional)
 *   POST /iadron/api/v1/devices/{dockSn}/nav/stop     para y queda en estacionario
 *   POST /iadron/api/v1/devices/{dockSn}/nav/land     aterriza donde está (drc_emergency_landing)
 *   GET  /iadron/api/v1/devices/{dockSn}/nav/status   posición estimada y estado
 */
@Slf4j
@RestController
@RequestMapping("/iadron/api/v1/devices/{dockSn}/nav")
public class NavController {

    @Autowired
    private GoToPointService goToPointService;

    @Data
    public static class GoToParam {
        private double x;
        private double y;
        private double z;
        /** Rumbo final en grados; null = no girar. */
        private Double yaw;
    }

    @PostMapping("/origin")
    public HttpResultResponse origin(@PathVariable String dockSn) {
        return HttpResultResponse.success(goToPointService.setOrigin(dockSn));
    }

    @PostMapping("/goto")
    public HttpResultResponse goTo(@PathVariable String dockSn, @RequestBody GoToParam p) {
        return HttpResultResponse.success(goToPointService.goTo(dockSn, p.getX(), p.getY(), p.getZ(), p.getYaw()));
    }

    @PostMapping("/stop")
    public HttpResultResponse stop(@PathVariable String dockSn) {
        return HttpResultResponse.success(goToPointService.stop(dockSn));
    }

    @PostMapping("/land")
    public HttpResultResponse land(@PathVariable String dockSn) {
        return HttpResultResponse.success(goToPointService.land(dockSn));
    }

    @GetMapping("/status")
    public HttpResultResponse status(@PathVariable String dockSn) {
        return HttpResultResponse.success(goToPointService.status(dockSn));
    }

    @ExceptionHandler(RuntimeException.class)
    public HttpResultResponse handle(RuntimeException e) {
        log.warn("[IADRON-NAV] {}", e.getMessage());
        return HttpResultResponse.error(e.getMessage());
    }
}