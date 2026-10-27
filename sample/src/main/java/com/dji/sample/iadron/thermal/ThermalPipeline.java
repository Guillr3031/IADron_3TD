package com.dji.sample.iadron.thermal;

import com.dji.sample.iadron.config.IadronProperties;
import com.dji.sample.iadron.model.DronePose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * IADron: camino ÚNICO de cada foto térmica hacia la IA.
 *
 *   R-JPEG (_T.JPG, bytes tal cual)
 *     -> RJpegInfo        tamaño + actitud del XMP (si lo hay)
 *     -> ThermalDecoder   temperaturas reales con dji_irp (float32, ºC)
 *     -> DroneThermalImage.serialize()   binario "TI" (igual que su C#)
 *     -> "A|" + base64    -> IaSender
 *
 * Lo usan ThermalForwarder (fotos reales hechas en DRC) y SampleReplayService (dataset, sin volar).
 */
@Slf4j
@Component
public class ThermalPipeline {

    private final IadronProperties properties;
    private final ThermalDecoder decoder;
    private final IaSender sender;

    public ThermalPipeline(IadronProperties properties, ThermalDecoder decoder, IaSender sender) {
        this.properties = properties;
        this.decoder = decoder;
        this.sender = sender;
    }

    /**
     * @param name        nombre del fichero
     * @param rjpeg       bytes del R-JPEG sin abrir
     * @param pose        telemetría del dron en ese momento;
     * @param timestampMs momento de la foto (UTC)
     */
    public void process(String name, byte[] rjpeg, DronePose pose, long timestampMs) throws Exception {
        long t0 = System.currentTimeMillis();
        DroneThermalImage img = build(rjpeg, pose, timestampMs);
        String msg = encode(img, properties.getThermal().getMessagePrefix());
        long t1 = System.currentTimeMillis();
        sender.send(msg);
        long t2 = System.currentTimeMillis();
        log.info("[IADRON-IA] {} {}x{} min {} ºC max {} ºC -> {} chars | decodificar {} ms, enviar {} ms",
                name, img.width, img.height, String.format("%.1f", min(img.thermalData)),
                String.format("%.1f", max(img.thermalData)), msg.length(), t1 - t0, t2 - t1);
    }

    public DroneThermalImage build(byte[] rjpeg, DronePose pose, long timestampMs) throws Exception {
        RJpegInfo info = RJpegInfo.parse(rjpeg);
        DroneThermalImage img = new DroneThermalImage();
        img.width = info.width;
        img.height = info.height;
        img.thermalData = decoder.measure(rjpeg, info.width, info.height);
        img.timestamp = timestampMs;

        Float alt = info.relativeAltitude != null ? info.relativeAltitude : (pose == null ? null : pose.getHeight());
        img.posY = f(alt);

        if (info.hasXmp) {
            img.rotX = f(info.flightPitch);
            img.rotY = f(info.flightYaw);
            img.rotZ = f(info.flightRoll);
            img.gimbalX = f(info.gimbalPitch);
            img.gimbalY = f(info.gimbalYaw);
            img.gimbalZ = f(info.gimbalRoll);
        } else if (pose != null) {
            img.rotX = pose.getAttitudePitch() == null ? 0f : pose.getAttitudePitch().floatValue();
            img.rotY = f(pose.getAttitudeHead());
            img.rotZ = pose.getAttitudeRoll() == null ? 0f : pose.getAttitudeRoll().floatValue();
            img.gimbalX = f(pose.getGimbalPitch());
            img.gimbalY = f(pose.getGimbalYaw());
            img.gimbalZ = f(pose.getGimbalRoll());
        }
        return img;
    }

    /** "A|" + base64 del binario TI. */
    public static String encode(DroneThermalImage img, String prefix) {
        return prefix + "|" + Base64.getEncoder().encodeToString(img.serialize());
    }

    private static float f(Float v) {
        return v == null ? 0f : v;
    }

    private static float min(float[] a) {
        float m = Float.MAX_VALUE;
        for (float v : a) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static float max(float[] a) {
        float m = -Float.MAX_VALUE;
        for (float v : a) {
            m = Math.max(m, v);
        }
        return m;
    }
}