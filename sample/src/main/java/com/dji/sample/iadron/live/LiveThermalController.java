package com.dji.sample.iadron.live;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * IADron: control del envío en tiempo real.
 *  POST /iadron/api/v1/live/start?rtsp=...   (rtsp opcional: por defecto el de application-iadron.yml)
 *  POST /iadron/api/v1/live/stop
 *  GET  /iadron/api/v1/live/status
 */
@RestController
@RequestMapping("/iadron/api/v1/live")
public class LiveThermalController {

    private final LiveThermalService live;

    public LiveThermalController(LiveThermalService live) {
        this.live = live;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(required = false) String rtsp,
                                     @RequestParam(defaultValue = "7CTXM8S00B00YC") String dock,
                                     @RequestParam(defaultValue = "81-0-0") String payload) {
        return live.start(rtsp, dock, payload);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return live.stop();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return live.status();
    }
}