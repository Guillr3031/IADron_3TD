package com.dji.sdk.mqtt.state;

import com.dji.sdk.common.Common;
import com.dji.sdk.common.SDKManager;
import com.dji.sdk.exception.CloudSDKErrorEnum;
import com.dji.sdk.exception.CloudSDKException;
import com.dji.sdk.mqtt.ChannelName;
import com.dji.sdk.mqtt.MqttGatewayPublish;
import com.dji.sdk.mqtt.MqttReply;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.dji.sdk.mqtt.TopicConst.*;

/**
 *
 * @author sean.zhou
 * @date 2021/11/17
 * @version 0.1
 */
@Configuration
public class StateRouter {

    private static final Logger log = LoggerFactory.getLogger(StateRouter.class);   // IADron

    @Resource
    private MqttGatewayPublish gatewayPublish;

    @Resource
    private org.springframework.beans.factory.BeanFactory beanFactory;   // IADron

    @Bean
    public IntegrationFlow stateDataRouterFlow() {
        return IntegrationFlows
                .from(ChannelName.INBOUND_STATE)
                // IADron: los state con claves que este SDK no conoce (firmware más nuevo que el demo)
                // no se procesan, pero SI se responden cuando el Dock lo pide (need_reply=1).
                .filter(Message.class, this::isKnownState)
                .transform(Message.class, source -> {
                    try {
                        TopicStateRequest response = Common.getObjectMapper().readValue((byte[]) source.getPayload(), new TypeReference<TopicStateRequest>() {});
                        String topic = String.valueOf(source.getHeaders().get(MqttHeaders.RECEIVED_TOPIC));
                        String from = topic.substring((THING_MODEL_PRE + PRODUCT).length(), topic.indexOf(STATE_SUF));
                        return response.setFrom(from)
                                .setData(Common.getObjectMapper().convertValue(response.getData(), getTypeReference(response.getGateway(), response.getData())));
                    } catch (IOException e) {
                        throw new CloudSDKException(e);
                    }
                }, null)
                .<TopicStateRequest, StateDataKeyEnum>route(response -> StateDataKeyEnum.find(response.getData().getClass()),
                        mapping -> Arrays.stream(StateDataKeyEnum.values()).forEach(key -> mapping.channelMapping(key, key.getChannelName())))
                .get();
    }


    @Bean
    public IntegrationFlow replySuccessState() {
        return IntegrationFlows
                .from(ChannelName.OUTBOUND_STATE)
                .handle(this::publish)
                .nullChannel();

    }

    private TopicStateResponse publish(TopicStateResponse request, MessageHeaders headers) {
        if (Objects.isNull(request) || Objects.isNull(request.getData())) {
            return null;
        }
        gatewayPublish.publishReply(request, headers);
        return request;
    }

    /**
     * IADron: devuelve false si el mensaje state solo trae claves que el SDK no soporta
     * (p. ej. "wireless_link_topo" del firmware 17.x). Si el Dock pide respuesta
     * (need_reply=1), se le contesta con éxito igualmente: si no, lo reenvía y puede
     * dar la conexión con la nube por fallida.
     */
    private boolean isKnownState(Message<?> source) {
        Map<String, Object> root;
        try {
            root = Common.getObjectMapper()
                    .readValue((byte[]) source.getPayload(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            return true; // el transform gestiona el error de parseo como antes
        }

        Object data = root.get("data");
        if (!(data instanceof Map)) {
            return true;
        }
        try {
            Class clazz = getTypeReference(String.valueOf(root.get("gateway")), data);
            // El SDK conoce la clave pero el demo no define su canal (p. ej. offline_map_enable):
            // se trata igual que una clave desconocida.
            String channel = StateDataKeyEnum.find(clazz).getChannelName();
            if (!beanFactory.containsBean(channel)) {
                log.debug("State sin canal en el demo ({}), se ignora: {}", channel, ((Map<?, ?>) data).keySet());
                if ("1".equals(String.valueOf(root.get("need_reply"))) || Boolean.TRUE.equals(root.get("need_reply"))) {
                    replySuccess(root, source.getHeaders());
                }
                return false;
            }
            return true;
        } catch (CloudSDKException e) {
            log.debug("State no soportado por el SDK, se ignora: {}", ((Map<?, ?>) data).keySet());
            if ("1".equals(String.valueOf(root.get("need_reply")))) {
                replySuccess(root, source.getHeaders());
            }
            return false;
        }
    }

    /** IADron: responde {"result":0} en thing/product/{sn}/state_reply con el mismo tid/bid. */
    private void replySuccess(Map<String, Object> root, MessageHeaders headers) {
        TopicStateResponse<MqttReply> reply = new TopicStateResponse<>();
        reply.setTid(String.valueOf(root.get("tid")));
        reply.setBid(String.valueOf(root.get("bid")));
        reply.setTimestamp(System.currentTimeMillis());
        reply.setData(MqttReply.success());
        gatewayPublish.publishReply(reply, headers);
        log.debug("state_reply enviado (tid={})", root.get("tid"));
    }

    private Class getTypeReference(String gatewaySn, Object data) {
        Set<String> keys = ((Map<String, Object>) data).keySet();
        switch (SDKManager.getDeviceSDK(gatewaySn).getType()) {
            case RC:
                return RcStateDataKeyEnum.find(keys).getClassType();
            case DOCK:
            case DOCK2:
                return DockStateDataKeyEnum.find(keys).getClassType();
            default:
                throw new CloudSDKException(CloudSDKErrorEnum.WRONG_DATA, "Unexpected value: " + SDKManager.getDeviceSDK(gatewaySn).getType());
        }
    }
}