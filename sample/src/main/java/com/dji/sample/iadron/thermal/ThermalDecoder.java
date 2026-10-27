package com.dji.sample.iadron.thermal;

import com.dji.sample.iadron.config.IadronProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * IADron: temperaturas reales (ºC por píxel) de un R-JPEG con el DJI Thermal SDK:
 */
@Component("tiThermalDecoder")
public class ThermalDecoder {

    private final IadronProperties properties;

    public ThermalDecoder(IadronProperties properties) {
        this.properties = properties;
    }

    public float[] measure(byte[] rjpeg, int width, int height) throws Exception {
        IadronProperties.Thermal cfg = properties.getThermal();
        File exe = new File(cfg.getIrpPath());
        if (!exe.isFile()) {
            throw new IllegalStateException("No se encuentra dji_irp en '" + exe.getAbsolutePath()
                    + "' (configura iadron.thermal.irp-path)");
        }
        Path dir = Files.createTempDirectory("iadron-irp");
        Path in = dir.resolve("in.jpg");
        Path out = dir.resolve("out.raw");
        try {
            Files.write(in, rjpeg);
            List<String> cmd = new ArrayList<>(List.of(exe.getAbsolutePath(),
                    "-s", in.toString(), "-a", "measure", "-o", out.toString(), "--measurefmt", "float32"));
            // Parámetros de medida opcionales: si no se ponen, se usan los que guardó la foto.
            if (cfg.getEmissivity() != null) {
                cmd.add("--emissivity");
                cmd.add(String.valueOf(cfg.getEmissivity()));
            }
            if (cfg.getDistance() != null) {
                cmd.add("--distance");
                cmd.add(String.valueOf(cfg.getDistance()));
            }
            Process p = new ProcessBuilder(cmd)
                    .directory(exe.getParentFile())   // para que encuentre sus DLL / .so
                    .redirectErrorStream(true)
                    .start();
            byte[] console = p.getInputStream().readAllBytes();
            if (!p.waitFor(cfg.getIrpTimeoutMs(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("dji_irp no terminó en " + cfg.getIrpTimeoutMs() + " ms");
            }
            if (p.exitValue() != 0 || !Files.exists(out)) {
                throw new IllegalStateException("dji_irp falló (código " + p.exitValue() + "): "
                        + new String(console).trim());
            }
            byte[] raw = Files.readAllBytes(out);
            if (raw.length != width * height * 4) {
                throw new IllegalStateException("dji_irp devolvió " + raw.length + " bytes, se esperaban "
                        + width * height * 4 + " (" + width + "x" + height + ")");
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