package com.dji.sample.iadron.ia;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * IADron: equivalente en Java de DroneThermalImage (C#) del equipo de IA.
 * Genera EXACTAMENTE los mismos bytes que su Serialize():
 *
 *   'T' 'I'                      2 bytes   cabecera ("Thermal Image")
 *   int32  Width                 4 bytes
 *   int32  Height                4 bytes
 *   float32[Height*Width]        W*H*4     temperaturas en ºC, fila a fila
 *   float32 Position X,Y,Z       12 bytes
 *   float32 Rotation X,Y,Z       12 bytes
 *   float32 Gimbal   X,Y,Z       12 bytes
 *   int64  Timestamp             8 bytes
 *
 * Todo en little-endian. Tamaño total = 54 + W*H*4  (640x512 -> 1.310.774 bytes).
 */
public class DroneThermalImage {

    public static final int HEADER_AND_TAIL = 2 + 4 + 4 + 9 * 4 + 8; // 54

    public float[] thermalData;   // Height*Width, fila a fila: thermalData[y*width + x]
    public int width;
    public int height;
    public float posX, posY, posZ;
    public float rotX, rotY, rotZ;
    public float gimbalX, gimbalY, gimbalZ;
    public long timestamp;

    public int rawSize() {
        return HEADER_AND_TAIL + width * height * 4;
    }

    public byte[] serialize() {
        if (thermalData.length != width * height) {
            throw new IllegalStateException("thermalData no coincide con width*height");
        }
        ByteBuffer b = ByteBuffer.allocate(rawSize()).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'T').put((byte) 'I');
        b.putInt(width).putInt(height);
        b.asFloatBuffer().put(thermalData);           // copia rápida del bloque de temperaturas
        b.position(b.position() + thermalData.length * 4);
        b.putFloat(posX).putFloat(posY).putFloat(posZ);
        b.putFloat(rotX).putFloat(rotY).putFloat(rotZ);
        b.putFloat(gimbalX).putFloat(gimbalY).putFloat(gimbalZ);
        b.putLong(timestamp);
        return b.array();
    }

    /** Lectura inversa, útil para comprobar en tests que el formato coincide con el suyo. */
    public static DroneThermalImage deserialize(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get() != 'T' || b.get() != 'I') {
            throw new IllegalArgumentException("Cabecera 'TI' no encontrada");
        }
        DroneThermalImage img = new DroneThermalImage();
        img.width = b.getInt();
        img.height = b.getInt();
        img.thermalData = new float[img.width * img.height];
        b.asFloatBuffer().get(img.thermalData);
        b.position(b.position() + img.thermalData.length * 4);
        img.posX = b.getFloat(); img.posY = b.getFloat(); img.posZ = b.getFloat();
        img.rotX = b.getFloat(); img.rotY = b.getFloat(); img.rotZ = b.getFloat();
        img.gimbalX = b.getFloat(); img.gimbalY = b.getFloat(); img.gimbalZ = b.getFloat();
        img.timestamp = b.getLong();
        return img;
    }
}