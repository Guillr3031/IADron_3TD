package com.dji.sample.iadron.model;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * Comando de joystick virtual por DRC. Todos los valores en el marco del dron:
 * x = adelante(+)/atrás(-) m/s, y = derecha(+)/izquierda(-) m/s,
 * h = subir(+)/bajar(-) m/s, w = giro de yaw °/s.
 *
 * El backend recorta cada valor a los límites de iadron.drc.* y repite el comando a la frecuencia
 * configurada durante durationMs; pasado ese tiempo envía ceros (hombre muerto).
 */
@Data
public class StickCommandParam {

    private float x;
    private float y;
    private float h;
    private float w;

    @Min(50)
    @Max(5000)
    private long durationMs = 500;
}
