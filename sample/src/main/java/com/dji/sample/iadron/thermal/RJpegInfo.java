package com.dji.sample.iadron.thermal;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IADron: lo que se lee de un R-JPEG de DJI sin el Thermal SDK:
 * tamaño (cabecera JPEG) y actitud del dron y del gimbal (XMP drone-dji).
 */
public class RJpegInfo {

    public int width;
    public int height;
    public boolean hasXmp;
    public Float flightPitch, flightYaw, flightRoll;
    public Float gimbalPitch, gimbalYaw, gimbalRoll;
    public Float relativeAltitude;

    public static RJpegInfo parse(byte[] jpg) {
        RJpegInfo info = new RJpegInfo();
        int[] wh = jpegSize(jpg);
        info.width = wh[0];
        info.height = wh[1];

        // El XMP va en texto dentro del APP1; basta con buscar en los primeros KB.
        String head = new String(jpg, 0, Math.min(jpg.length, 128 * 1024), StandardCharsets.ISO_8859_1);
        info.flightPitch = xmp(head, "FlightPitchDegree");
        info.flightYaw = xmp(head, "FlightYawDegree");
        info.flightRoll = xmp(head, "FlightRollDegree");
        info.gimbalPitch = xmp(head, "GimbalPitchDegree");
        info.gimbalYaw = xmp(head, "GimbalYawDegree");
        info.gimbalRoll = xmp(head, "GimbalRollDegree");
        info.relativeAltitude = xmp(head, "RelativeAltitude");
        info.hasXmp = info.gimbalYaw != null || info.flightYaw != null;
        return info;
    }

    /** Acepta las dos formas de XMP: drone-dji:X="1.0"  y  <drone-dji:X>1.0</drone-dji:X>. */
    private static Float xmp(String s, String key) {
        Matcher m = Pattern.compile("drone-dji:" + key + "\\s*=\\s*\"([^\"]+)\"").matcher(s);
        if (!m.find()) {
            m = Pattern.compile("<drone-dji:" + key + ">([^<]+)<").matcher(s);
            if (!m.find()) {
                return null;
            }
        }
        try {
            return Float.parseFloat(m.group(1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Ancho y alto desde el marcador SOF de la cabecera JPEG. */
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
            if (marker == 0xFF) {
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
            if (marker == 0xDA) {
                break;
            }
            i += 2 + len;
        }
        throw new IllegalArgumentException("No se encontró el tamaño en la cabecera JPEG");
    }
}