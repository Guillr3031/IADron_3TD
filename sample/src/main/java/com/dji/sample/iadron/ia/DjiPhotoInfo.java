package com.dji.sample.iadron.ia;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IADron: lee de la propia foto de DJI los datos del momento exacto del disparo:
 *  - tamaño de la imagen (cabecera JPEG, marcador SOF)
 *  - actitud del dron y del gimbal (XMP "drone-dji:...", en grados)
 *  - altura relativa (XMP, metros)
 *  - fecha/hora (EXIF DateTimeOriginal)
 * Así no hace falta emparejar la foto con el OSD que llegó por MQTT.
 */
public class DjiPhotoInfo {

    public int width;
    public int height;

    public float flightPitch, flightYaw, flightRoll;     // grados (actitud del dron)
    public float gimbalPitch, gimbalYaw, gimbalRoll;     // grados (cámara)
    public float relativeAltitude;                       // metros sobre el punto de despegue
    public Long timestampMs;                             // epoch en ms, null si no hay EXIF

    private static final Pattern XMP_ATTR = Pattern.compile("drone-dji:(\\w+)=\"([^\"]*)\"");
    private static final Pattern XMP_ELEM = Pattern.compile("<drone-dji:(\\w+)>([^<]*)</drone-dji:\\1>");
    private static final Pattern EXIF_DATE = Pattern.compile("(\\d{4}):(\\d{2}):(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})");
    private static final DateTimeFormatter EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    public static DjiPhotoInfo parse(byte[] jpeg) {
        DjiPhotoInfo info = new DjiPhotoInfo();
        int[] wh = jpegSize(jpeg);
        info.width = wh[0];
        info.height = wh[1];

        // Los metadatos están al principio del fichero; no hace falta recorrerlo entero.
        String head = new String(jpeg, 0, Math.min(jpeg.length, 256 * 1024), StandardCharsets.ISO_8859_1);
        Map<String, String> xmp = new HashMap<>();
        for (Pattern p : new Pattern[]{XMP_ATTR, XMP_ELEM}) {
            Matcher m = p.matcher(head);
            while (m.find()) {
                xmp.putIfAbsent(m.group(1), m.group(2).trim());
            }
        }
        info.flightPitch = num(xmp, "FlightPitchDegree");
        info.flightYaw = num(xmp, "FlightYawDegree");
        info.flightRoll = num(xmp, "FlightRollDegree");
        info.gimbalPitch = num(xmp, "GimbalPitchDegree");
        info.gimbalYaw = num(xmp, "GimbalYawDegree");
        info.gimbalRoll = num(xmp, "GimbalRollDegree");
        info.relativeAltitude = num(xmp, "RelativeAltitude");

        Matcher d = EXIF_DATE.matcher(head);
        if (d.find()) {
            try {
                info.timestampMs = LocalDateTime.parse(d.group(), EXIF_FMT)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                // fecha ilegible: se queda null y se usa la hora actual
            }
        }
        return info;
    }

    private static float num(Map<String, String> xmp, String key) {
        String v = xmp.get(key);
        if (v == null || v.isEmpty()) {
            return 0f;
        }
        try {
            return Float.parseFloat(v.replace("+", ""));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** Ancho y alto leídos del marcador SOF de la cabecera JPEG. */
    static int[] jpegSize(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("No es un JPEG");
        }
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) {          // relleno
                i++;
                continue;
            }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return new int[]{w, h};
            }
            if (marker == 0xDA) {          // empieza la imagen comprimida sin haber visto SOF
                break;
            }
            i += 2 + len;
        }
        throw new IllegalArgumentException("No se encontró el tamaño en la cabecera JPEG");
    }
}