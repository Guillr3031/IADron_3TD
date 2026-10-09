package com.dji.sample.iadron.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pose del dron tal como la recibe el backend.
 *
 * En interior lat/lon NO son fiables: se envían igualmente (pueden venir a 0 o con la última
 * posición conocida), pero el consumidor debe mirar {@link #positioning}.gnssUsable antes de usarlas.
 * Lo que sí es útil sin GNSS: altura (height, por barómetro/visión), actitud, velocidades y gimbal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DronePose {

    public enum Source {
        /** OSD 0,5 Hz con el Dock como gateway. */
        DOCK_OSD,
        /** OSD con el mando (RC Plus + Pilot 2) como gateway. */
        RC_OSD,
        /** OSD de alta frecuencia (hasta 10 Hz) en modo DRC. */
        DRC_OSD
    }

    private Source source;
    /** SN del gateway (Dock o RC). */
    private String gatewaySn;
    /** SN del dron (en DRC_OSD puede no venir; se rellena si se conoce). */
    private String droneSn;
    /** Momento de recepción en el servidor (epoch ms). */
    private long receivedAt;

    private Float latitude;
    private Float longitude;
    /** Altura relativa al punto de despegue (m). */
    private Float height;
    /** Altura elipsoidal (m), sólo OSD normal. */
    private Float elevation;

    private Float attitudeHead;
    private Double attitudePitch;
    private Double attitudeRoll;

    /** Velocidades: en OSD normal horizontal/vertical; en DRC componentes X/Y/Z. */
    private Float horizontalSpeed;
    private Float verticalSpeed;
    private Float speedX;
    private Float speedY;
    private Float speedZ;

    private Float gimbalPitch;
    private Float gimbalRoll;
    private Float gimbalYaw;
    private String payloadIndex;

    /** Distancia medida por el telémetro de la carga, si el modelo la reporta. */
    private Float measureTargetDistance;

    private String modeCode;
    private PositioningStatus positioning;
}
