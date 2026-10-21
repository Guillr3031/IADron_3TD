package com.dji.sample.iadron.ia;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * IADron: convierte un R-JPEG de DJI en la matriz de temperaturas (ºC) y monta el
 * DroneThermalImage que espera el equipo de IA.
 *
 * Las temperaturas las calcula el DJI Thermal SDK (dji_irp.exe):
 *   dji_irp -s foto.jpg -a measure -o temp.raw --measurefmt float32
 * genera W*H floats little-endian, fila a fila: justo el bloque ThermalData del formato "TI".
 */
@Slf4j
@Component
public class ThermalDecoder {

    private final IaForwardProperties props;

    public ThermalDecoder(IaForwardProperties props) {
        this.props = props;
    }

    public DroneThermalImage build(byte[] rjpeg) throws Exception {
        DjiPhotoInfo info = DjiPhotoInfo.parse(rjpeg);

        DroneThermalImage img = new DroneThermalImage();
        img.width = info.width;
        img.height = info.height;
        img.thermalData = measure(rjpeg, info.width, info.height);

        // Posición: en interior no hay GPS. De momento solo la altura relativa (m) en Y (Godot: Y arriba).
        // TODO IADron: X y Z cuando exista el sistema de posicionamiento interior.
        img.posX = 0f;
        img.posY = info.relativeAltitude;
        img.posZ = 0f;

        // Ángulos tal como los da el dron, en grados. Godot: X = pitch, Y = yaw, Z = roll.
        img.rotX = info.flightPitch;
        img.rotY = info.flightYaw;
        img.rotZ = info.flightRoll;
        img.gimbalX = info.gimbalPitch;
        img.gimbalY = info.gimbalYaw;
        img.gimbalZ = info.gimbalRoll;

        img.timestamp = info.timestampMs != null ? info.timestampMs : System.currentTimeMillis();
        return img;
    }

    /** Ejecuta dji_irp y devuelve las temperaturas en ºC (W*H, fila a fila). */
    public float[] measure(byte[] rjpeg, int width, int height) throws Exception {
        File exe = new File(props.getIrpPath());
        if (!exe.isFile()) {
            throw new IllegalStateException("No se encuentra dji_irp en " + exe.getAbsolutePath()
                    + " (configura iadron.ia.irp-path)");
        }
        Path dir = Files.createTempDirectory("iadron-irp");
        Path in = dir.resolve("in.jpg");
        Path out = dir.resolve("out.raw");
        try {
            Files.write(in, rjpeg);
            Process p = new ProcessBuilder(List.of(exe.getAbsolutePath(),
                    "-s", in.toString(), "-a", "measure", "-o", out.toString(), "--measurefmt", "float32"))
                    .directory(exe.getParentFile())   // para que encuentre sus DLL
                    .redirectErrorStream(true)
                    .start();
            byte[] console = p.getInputStream().readAllBytes();
            if (!p.waitFor(props.getIrpTimeoutMs(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("dji_irp no terminó a tiempo");
            }
            if (p.exitValue() != 0 || !Files.exists(out)) {
                throw new IllegalStateException("dji_irp falló (código " + p.exitValue() + "): "
                        + new String(console).trim());
            }
            byte[] raw = Files.readAllBytes(out);
            int expected = width * height * 4;
            if (raw.length != expected) {
                throw new IllegalStateException("dji_irp devolvió " + raw.length + " bytes, se esperaban "
                        + expected + " (" + width + "x" + height + ")");
            }
            float[] temps = new float[width * height];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(temps);
            return temps;
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
            Files.deleteIfExists(dir);
        }
    }
}