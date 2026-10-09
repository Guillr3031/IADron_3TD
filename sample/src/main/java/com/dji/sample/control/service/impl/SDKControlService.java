package com.dji.sample.control.service.impl;

import com.dji.sample.component.websocket.model.BizCodeEnum;
import com.dji.sample.component.websocket.service.IWebSocketMessageService;
import com.dji.sample.control.model.dto.ResultNotifyDTO;
import com.dji.sample.iadron.service.DrcLinkMonitor;
import com.dji.sample.iadron.service.PoseTracker;
import com.dji.sample.manage.model.dto.DeviceDTO;
import com.dji.sample.manage.model.enums.UserTypeEnum;
import com.dji.sample.manage.service.IDeviceRedisService;
import com.dji.sdk.cloudapi.control.*;
import com.dji.sdk.cloudapi.control.api.AbstractControlService;
import com.dji.sdk.cloudapi.wayline.WaylineErrorCodeEnum;
import com.dji.sdk.mqtt.MqttReply;
import com.dji.sdk.mqtt.drc.DrcUpData;
import com.dji.sdk.mqtt.drc.TopicDrcRequest;
import com.dji.sdk.mqtt.events.TopicEventsRequest;
import com.dji.sdk.mqtt.events.TopicEventsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * @author sean
 * @version 1.7
 * @date 2023/7/4
 */
@Service
@Slf4j
public class SDKControlService extends AbstractControlService {

    @Autowired
    private IWebSocketMessageService webSocketMessageService;

    @Autowired
    private IDeviceRedisService deviceRedisService;

    @Autowired
    private ObjectMapper mapper;

    // IADron: telemetría DRC de alta frecuencia y estado del enlace.
    // (Sin dependencia de DrcSessionService para evitar ciclos de beans.)
    @Autowired
    private PoseTracker poseTracker;

    @Autowired
    private DrcLinkMonitor drcLinkMonitor;

    @Override
    public TopicEventsResponse<MqttReply> flyToPointProgress(TopicEventsRequest<FlyToPointProgress> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            log.error("The dock is offline.");
            return null;
        }

        FlyToPointProgress eventsReceiver = request.getData();
        webSocketMessageService.sendBatch(deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(),
                BizCodeEnum.FLY_TO_POINT_PROGRESS.getCode(),
                ResultNotifyDTO.builder().sn(dockSn)
                        .message(eventsReceiver.getResult().toString())
                        .result(eventsReceiver.getResult().getCode())
                        .build());
        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public TopicEventsResponse<MqttReply> takeoffToPointProgress(TopicEventsRequest<TakeoffToPointProgress> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            log.error("The dock is offline.");
            return null;
        }

        TakeoffToPointProgress eventsReceiver = request.getData();
        webSocketMessageService.sendBatch(deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(),
                BizCodeEnum.TAKE_OFF_TO_POINT_PROGRESS.getCode(),
                ResultNotifyDTO.builder().sn(dockSn)
                        .message(eventsReceiver.getResult().toString())
                        .result(eventsReceiver.getResult().getCode())
                        .build());

        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public TopicEventsResponse<MqttReply> drcStatusNotify(TopicEventsRequest<DrcStatusNotify> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            return null;
        }

        DrcStatusNotify eventsReceiver = request.getData();
        if (DrcStatusErrorEnum.SUCCESS != eventsReceiver.getResult()) {
            webSocketMessageService.sendBatch(
                    deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(), BizCodeEnum.DRC_STATUS_NOTIFY.getCode(),
                    ResultNotifyDTO.builder().sn(dockSn)
                            .message(eventsReceiver.getResult().getMessage())
                            .result(eventsReceiver.getResult().getCode()).build());
        }
        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }

    @Override
    public TopicEventsResponse<MqttReply> joystickInvalidNotify(TopicEventsRequest<JoystickInvalidNotify> request, MessageHeaders headers) {
        String dockSn  = request.getGateway();

        Optional<DeviceDTO> deviceOpt = deviceRedisService.getDeviceOnline(dockSn);
        if (deviceOpt.isEmpty()) {
            return null;
        }

        JoystickInvalidNotify eventsReceiver = request.getData();
        webSocketMessageService.sendBatch(
                deviceOpt.get().getWorkspaceId(), UserTypeEnum.WEB.getVal(), BizCodeEnum.JOYSTICK_INVALID_NOTIFY.getCode(),
                ResultNotifyDTO.builder().sn(dockSn)
                        .message(eventsReceiver.getReason().getMessage())
                        .result(eventsReceiver.getReason().getVal()).build());
        return new TopicEventsResponse<MqttReply>().setData(MqttReply.success());
    }

    // ============================================================== IADron: mensajes drc/up
    // El SDK lanza UnsupportedOperationException en todos ellos por defecto; en cuanto el backend se
    // suscribe a thing/product/{dockSn}/drc/up (ver DrcServiceImpl) hay que implementarlos todos.

    @Override
    public void osdInfoPush(TopicDrcRequest<OsdInfoPush> request, MessageHeaders headers) {
        String dockSn = dockSnFromTopic(headers);
        drcLinkMonitor.osdReceived(dockSn);
        poseTracker.onDrcOsd(dockSn, request.getData());
    }

    @Override
    public void heartBeatUp(TopicDrcRequest<HeartBeatRequest> request, MessageHeaders headers) {
        drcLinkMonitor.heartbeatAck(dockSnFromTopic(headers));
    }

    @Override
    public void droneControlUp(TopicDrcRequest<DrcUpData<DroneControlResponse>> request, MessageHeaders headers) {
        String dockSn = dockSnFromTopic(headers);
        DrcUpData<DroneControlResponse> data = request.getData();
        Long seq = data == null || data.getOutput() == null ? null : data.getOutput().getSeq();
        String result = data == null || data.getResult() == null ? "UNKNOWN" : String.valueOf(data.getResult());
        drcLinkMonitor.stickAck(dockSn, seq, result);
        if (data != null && data.getResult() != null && data.getResult() != WaylineErrorCodeEnum.SUCCESS) {
            log.warn("[IADRON] {} rechaza joystick seq={}: {}", dockSn, seq, result);
            poseTracker.logDrcEvent(dockSn, "drone_control_error", result);
        }
    }

    @Override
    public void droneEmergencyStopUp(TopicDrcRequest<DrcUpData> request, MessageHeaders headers) {
        String dockSn = dockSnFromTopic(headers);
        drcLinkMonitor.emergencyStopAck(dockSn);
        poseTracker.logDrcEvent(dockSn, "emergency_stop_ack", request.getData() == null ? null : String.valueOf(request.getData().getResult()));
    }

    @Override
    public void hsiInfoPush(TopicDrcRequest<HsiInfoPush> request, MessageHeaders headers) {
        // Distancias a obstáculos alrededor del dron (sensores de visión). Se guardan en el log de vuelo:
        // útiles en interior para ver cuánto margen deja la evitación de obstáculos.
        poseTracker.logDrcEvent(dockSnFromTopic(headers), "hsi_info_push", request.getData());
    }

    @Override
    public void delayInfoPush(TopicDrcRequest<DelayInfoPush> request, MessageHeaders headers) {
        DelayInfoPush data = request.getData();
        drcLinkMonitor.delay(dockSnFromTopic(headers), data == null ? null : data.getSdrCmdDelay());
    }

    /** thing/product/{dockSn}/drc/up -> dockSn */
    private static String dockSnFromTopic(MessageHeaders headers) {
        String topic = String.valueOf(headers.get(MqttHeaders.RECEIVED_TOPIC));
        String[] parts = topic.split("/");
        return parts.length >= 3 ? parts[2] : topic;
    }
}
