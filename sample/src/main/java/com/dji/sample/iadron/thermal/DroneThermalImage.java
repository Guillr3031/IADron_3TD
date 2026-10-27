package com.dji.sample.iadron.thermal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * IADron: imagen térmica TI para la IA
 */
public class DroneThermalImage {

    public static final int HEADER_AND_TAIL = 2 + 4 + 4 + 9 * 4 + 8;

    /** Height*Width, fila a fila: temperatura del píxel (x, y) = thermalData[y * width + x]. */
    public float[] thermalData;
    public int width;
    public int height;
    public float posX, posY, posZ;
    public float rotX, rotY, rotZ;
    public float gimbalX, gimbalY, gimbalZ;
    public long timestamp;

    public byte[] serialize() {
        if (thermalData == null || thermalData.length != width * height) {
            throw new IllegalStateException("thermalData no coincide con width*height");
        }
        ByteBuffer b = ByteBuffer.allocate(HEADER_AND_TAIL + width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'T').put((byte) 'I');
        b.putInt(width).putInt(height);
        b.asFloatBuffer().put(thermalData);
        b.position(b.position() + thermalData.length * 4);
        b.putFloat(posX).putFloat(posY).putFloat(posZ);
        b.putFloat(rotX).putFloat(rotY).putFloat(rotZ);
        b.putFloat(gimbalX).putFloat(gimbalY).putFloat(gimbalZ);
        b.putLong(timestamp);
        return b.array();
    }

    /** Lectura inversa para comprobar el formato. */
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