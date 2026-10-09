package com.dji.sample.iadron.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado del enlace DRC por Dock, alimentado por los mensajes drc/up.
 * No depende de ningún otro bean a propósito: así SDKControlService puede usarlo
 * sin crear dependencias circulares con DrcSessionService.
 */
@Component
public class DrcLinkMonitor {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkState {
        private long lastHeartbeatAckAt;
        private long lastOsdAt;
        private long lastStickAckSeq;
        private long lastStickAckAt;
        private String lastStickResult;
        private Integer commandDelayMs;
        private long emergencyStopAckAt;
    }

    private final Map<String, LinkState> states = new ConcurrentHashMap<>();

    public LinkState get(String dockSn) {
        return states.computeIfAbsent(dockSn, k -> new LinkState());
    }

    public void heartbeatAck(String dockSn) {
        get(dockSn).setLastHeartbeatAckAt(System.currentTimeMillis());
    }

    public void osdReceived(String dockSn) {
        get(dockSn).setLastOsdAt(System.currentTimeMillis());
    }

    public void stickAck(String dockSn, Long seq, String result) {
        LinkState s = get(dockSn);
        s.setLastStickAckSeq(seq == null ? -1 : seq);
        s.setLastStickAckAt(System.currentTimeMillis());
        s.setLastStickResult(result);
    }

    public void delay(String dockSn, Integer sdrCmdDelayMs) {
        get(dockSn).setCommandDelayMs(sdrCmdDelayMs);
    }

    public void emergencyStopAck(String dockSn) {
        get(dockSn).setEmergencyStopAckAt(System.currentTimeMillis());
    }

    public void clear(String dockSn) {
        states.remove(dockSn);
    }
}
