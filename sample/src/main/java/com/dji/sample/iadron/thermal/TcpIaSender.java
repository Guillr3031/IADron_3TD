package com.dji.sample.iadron.thermal;

import com.dji.sample.iadron.config.IadronProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Envío por TCP a la aplicación de IA. Nuestro servidor es el CLIENTE: se conecta a
 * iadron.thermal.tcp-host:tcp-port y mantiene la conexión abierta entre fotos.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "iadron.thermal", name = "transport", havingValue = "tcp")
public class TcpIaSender implements IaSender {

    private final IadronProperties properties;
    private Socket socket;
    private OutputStream out;

    public TcpIaSender(IadronProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void send(String message) throws Exception {
        byte[] frame = frame(message.getBytes(StandardCharsets.US_ASCII));
        try {
            write(frame);
        } catch (IOException first) {
            log.warn("[IADRON-IA] Conexión TCP perdida ({}), reconectando...", first.getMessage());
            close();
            write(frame);   // un reintento
        }
    }

    private void write(byte[] frame) throws IOException {
        if (socket == null || socket.isClosed()) {
            connect();
        }
        out.write(frame);
        out.flush();
    }

    private void connect() throws IOException {
        IadronProperties.Thermal cfg = properties.getThermal();
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.setSoTimeout(cfg.getIaTimeoutMs());
        s.connect(new InetSocketAddress(cfg.getTcpHost(), cfg.getTcpPort()), cfg.getIaTimeoutMs());
        socket = s;
        out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
        log.info("[IADRON-IA] Conectado por TCP a {}:{}", cfg.getTcpHost(), cfg.getTcpPort());
    }

    private byte[] frame(byte[] payload) {
        if ("LENGTH_PREFIX".equalsIgnoreCase(properties.getThermal().getTcpFraming())) {
            return ByteBuffer.allocate(4 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(payload.length).put(payload).array();
        }
        byte[] f = new byte[payload.length + 1];       // NEWLINE
        System.arraycopy(payload, 0, f, 0, payload.length);
        f[payload.length] = '\n';
        return f;
    }

    @PreDestroy
    public synchronized void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {

        } finally {
            socket = null;
            out = null;
        }
    }
}