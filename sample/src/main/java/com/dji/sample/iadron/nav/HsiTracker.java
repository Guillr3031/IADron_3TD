package com.dji.sample.iadron.nav;

import com.dji.sdk.cloudapi.control.HsiInfoPush;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IADron: última lectura de los sensores de obstáculos (hsi_info_push, en DRC) por Dock.
 *
 * around_distance: distancias en mm repartidas en [0, 360) en sentido horario,
 *   índice 0 = frente del dron, 90° = derecha, 180° = detrás, 270° = izquierda.
 * up_distance / down_distance: mm.
 *
 * Valores <= 0 se tratan como "sin dato". Valores muy grandes (>= 60000) como "nada a la vista".
 * EN LA PRIMERA PRUEBA mira en logs/iadron qué valores reales manda el 3TD (líneas hsi_info_push).
 */
@Component
public class HsiTracker {

    public static final int NO_DATA = -1;

    public static class Reading {
        public final HsiInfoPush data;
        public final long receivedAt;

        Reading(HsiInfoPush data, long receivedAt) {
            this.data = data;
            this.receivedAt = receivedAt;
        }
    }

    private final Map<String, Reading> latest = new ConcurrentHashMap<>();

    public void update(String dockSn, HsiInfoPush data) {
        if (dockSn != null && data != null) {
            latest.put(dockSn, new Reading(data, System.currentTimeMillis()));
        }
    }

    public Reading get(String dockSn) {
        return latest.get(dockSn);
    }

    /**
     * Distancia mínima (mm) en el sector [angleDeg - halfWidthDeg, angleDeg + halfWidthDeg] (0 = frente, horario).
     * Devuelve NO_DATA si en ese sector no hay ninguna lectura válida.
     */
    public static int minAround(HsiInfoPush d, double angleDeg, double halfWidthDeg) {
        List<Integer> a = d.getAroundDistance();
        if (a == null || a.isEmpty()) {
            return NO_DATA;
        }
        int n = a.size();
        int min = Integer.MAX_VALUE;
        for (double ang = angleDeg - halfWidthDeg; ang <= angleDeg + halfWidthDeg; ang += 1.0) {
            int idx = (int) Math.floorMod(Math.round(ang * n / 360.0), (long) n);
            Integer v = a.get(idx);
            if (v != null && v > 0) {
                min = Math.min(min, v);
            }
        }
        return min == Integer.MAX_VALUE ? NO_DATA : min;
    }
}