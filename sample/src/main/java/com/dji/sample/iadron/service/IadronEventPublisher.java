package com.dji.sample.iadron.service;

import com.dji.sample.iadron.model.CaptureEvent;
import com.dji.sample.iadron.model.DronePose;

/**
 * Salida de eventos hacia el servidor central de IADron.
 * Implementación por defecto: HTTP POST asíncrono ({@link com.dji.sample.iadron.service.impl.HttpIadronEventPublisher}).
 * Si más adelante el servidor central usa MQTT o WebSocket, basta con otra implementación.
 *
 * Las implementaciones NO deben bloquear: se llaman desde los hilos que procesan MQTT.
 */
public interface IadronEventPublisher {

    void publishPose(DronePose pose);

    void publishCapture(CaptureEvent capture);
}
