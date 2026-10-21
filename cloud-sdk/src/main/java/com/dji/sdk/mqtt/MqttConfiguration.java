package com.dji.sdk.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import javax.annotation.Resource;

/**
 * Client configuration for inbound messages.
 * @author sean.zhou
 * @date 2021/11/10
 * @version 0.1
 */
@Configuration
@IntegrationComponentScan
public class MqttConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MqttConfiguration.class);

    // IADron: IDs fijos. Con sesión persistente (cleanSession=false), EMQX reconoce al servidor
    // al reconectar y le entrega lo pendiente. Si se arranca un segundo servidor por error,
    // EMQX desconecta al primero en vez de tener dos contestando al Dock.
    private static final String INBOUND_CLIENT_ID = "iadron-backend-in";
    private static final String OUTBOUND_CLIENT_ID = "iadron-backend-out";

    @Value("${cloud-sdk.mqtt.inbound-topic: }")
    private String inboundTopic;

    @Resource
    private MqttPahoClientFactory mqttClientFactory;

    @Resource(name = ChannelName.INBOUND)
    private MessageChannel inboundChannel;

    /**
     * Clients of inbound message channels.
     * @return
     */
    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                INBOUND_CLIENT_ID, mqttClientFactory, inboundTopic.split(","));
        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        // use byte types uniformly
        converter.setPayloadAsBytes(true);
        adapter.setConverter(converter);
        adapter.setQos(1);
        adapter.setOutputChannel(inboundChannel);
        // IADron: si se pierde la conexión, reintenta cada 2 s (por defecto 10 s).
        adapter.setRecoveryInterval(2000);
        // IADron: espera máxima para conectar/suscribir antes de dar el intento por fallido.
        adapter.setCompletionTimeout(10000);
        return adapter;
    }

    /**
     * Clients of outbound message channels.
     * @return
     */
    @Bean
    @ServiceActivator(inputChannel = ChannelName.OUTBOUND)
    public MessageHandler mqttOutbound() {
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(
                OUTBOUND_CLIENT_ID, mqttClientFactory);
        DefaultPahoMessageConverter converter = new DefaultPahoMessageConverter();
        // use byte types uniformly
        converter.setPayloadAsBytes(true);

        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(0);
        messageHandler.setConverter(converter);
        // IADron: tiempo máximo de espera para cada publicación antes de dar error.
        messageHandler.setCompletionTimeout(10000);
        return messageHandler;
    }

    /**
     * IADron: deja constancia en el log de cada corte y reconexión con EMQX.
     */
    @EventListener
    public void onMqttConnectionFailed(MqttConnectionFailedEvent event) {
        log.error("[IADRON-MQTT] Conexión con EMQX perdida o fallida: {}",
                event.getCause() != null ? event.getCause().getMessage() : "sin detalle");
    }

    @EventListener
    public void onMqttSubscribed(MqttSubscribedEvent event) {
        log.info("[IADRON-MQTT] Conectado y suscrito: {}", event.getMessage());
    }

    /**
     * Define a default channel to handle messages that have no effect.
     * @return
     */
    @Bean
    @ServiceActivator(inputChannel = ChannelName.DEFAULT)
    public MessageHandler defaultInboundHandler() {
        return message -> {
            log.info("The default channel does not handle messages." +
                    "\nTopic: " + message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC) +
                    "\nPayload: " + message.getPayload() + "\n");
        };
    }
}