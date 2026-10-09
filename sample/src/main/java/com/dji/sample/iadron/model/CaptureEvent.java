package com.dji.sample.iadron.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una foto (térmica o visible) que el dron ha terminado de subir al almacenamiento de objetos (MinIO).
 * Se reenvía al servidor central para que la IA la analice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CaptureEvent {

    private String gatewaySn;
    private String droneSn;
    /** DOCK_EVENT (subida desde el Dock por MQTT) o PILOT_HTTP (subida desde Pilot 2 por HTTP). */
    private String origin;
    private String fileName;
    /** Clave del objeto en el bucket: con ella el servidor central descarga la imagen. */
    private String objectKey;
    private String bucket;
    private String flightId;
    private String droneModel;
    private String payloadModel;
    /** true para la lente térmica (heurística por nombre de fichero: sufijo _T). */
    private Boolean thermal;

    /** Metadatos que el dron incrusta en la foto (pueden ser poco fiables sin GNSS). */
    private Double shootLatitude;
    private Double shootLongitude;
    private Double relativeAltitude;
    private Double gimbalYawDegree;
    private String createdTime;

    /** Última pose conocida del dron en el momento de recibir el aviso de subida. */
    private DronePose poseAtUpload;
    private long receivedAt;
}
