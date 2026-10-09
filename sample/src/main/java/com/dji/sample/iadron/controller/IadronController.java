package com.dji.sample.iadron.controller;

import com.dji.sample.common.model.CustomClaim;
import com.dji.sample.iadron.model.DronePose;
import com.dji.sample.iadron.model.PositioningStatus;
import com.dji.sample.iadron.model.StickCommandParam;
import com.dji.sample.iadron.service.DrcLinkMonitor;
import com.dji.sample.iadron.service.DrcSessionService;
import com.dji.sample.iadron.service.PoseTracker;
import com.dji.sdk.common.HttpResultResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Map;
import java.util.Optional;

import static com.dji.sample.component.AuthInterceptor.TOKEN_CLAIM;

/**
 * API propia de IADron. Requiere el mismo token que el resto del backend (cabecera x-auth-token,
 * obtenido con POST /manage/api/v1/login).
 *
 * {sn} de pose/posicionamiento: SN del gateway (Dock o RC) o del dron.
 * {dockSn} de DRC: SN del Dock 2.
 */
@Slf4j
@RestController
@RequestMapping("/iadron/api/v1")
public class IadronController {

    @Autowired
    private PoseTracker poseTracker;

    @Autowired
    private DrcSessionService drcSessionService;

    @Autowired
    private DrcLinkMonitor linkMonitor;

    // ------------------------------------------------------------------ telemetría

    @GetMapping("/devices/{sn}/pose")
    public HttpResultResponse latestPose(@PathVariable String sn) {
        Optional<DronePose> pose = poseTracker.latestByGateway(sn).or(() -> poseTracker.latestByDrone(sn));
        return pose.isPresent()
                ? HttpResultResponse.success(pose.get())
                : HttpResultResponse.error("Sin telemetría de " + sn);
    }

    @GetMapping("/devices/{sn}/positioning")
    public HttpResultResponse positioning(@PathVariable String sn) {
        Optional<PositioningStatus> status = poseTracker.positioning(sn)
                .or(() -> poseTracker.latestByGateway(sn).map(DronePose::getPositioning));
        return status.isPresent()
                ? HttpResultResponse.success(status.get())
                : HttpResultResponse.error("Sin estado de posicionamiento de " + sn);
    }

    // ------------------------------------------------------------------ DRC (control relativo, sin GNSS)

    @PostMapping("/devices/{dockSn}/drc/enter")
    public HttpResultResponse drcEnter(@PathVariable String dockSn, HttpServletRequest request) {
        CustomClaim claim = (CustomClaim) request.getAttribute(TOKEN_CLAIM);
        drcSessionService.enter(claim.getWorkspaceId(), dockSn);
        return HttpResultResponse.success();
    }

    @PostMapping("/devices/{dockSn}/drc/exit")
    public HttpResultResponse drcExit(@PathVariable String dockSn) {
        drcSessionService.exit(dockSn);
        return HttpResultResponse.success();
    }

    @PostMapping("/devices/{dockSn}/drc/stick")
    public HttpResultResponse stick(@PathVariable String dockSn, @Valid @RequestBody StickCommandParam param) {
        drcSessionService.stick(dockSn, param);
        return HttpResultResponse.success();
    }

    @PostMapping("/devices/{dockSn}/drc/hold")
    public HttpResultResponse hold(@PathVariable String dockSn) {
        drcSessionService.hold(dockSn);
        return HttpResultResponse.success();
    }

    @PostMapping("/devices/{dockSn}/drc/emergency-stop")
    public HttpResultResponse emergencyStop(@PathVariable String dockSn) {
        drcSessionService.emergencyStop(dockSn);
        return HttpResultResponse.success();
    }

    @GetMapping("/devices/{dockSn}/drc/link")
    public HttpResultResponse link(@PathVariable String dockSn) {
        return HttpResultResponse.success(Map.of(
                "session_active", drcSessionService.isActive(dockSn),
                "link", linkMonitor.get(dockSn)));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class, RuntimeException.class})
    public HttpResultResponse handle(RuntimeException e) {
        log.warn("[IADRON] {}", e.getMessage());
        return HttpResultResponse.error(e.getMessage());
    }
}
