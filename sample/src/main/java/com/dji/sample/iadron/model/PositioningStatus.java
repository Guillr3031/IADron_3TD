package com.dji.sample.iadron.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Estado de posicionamiento GNSS del dron, derivado de {@code position_state} de la OSD.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositioningStatus {

    private String droneSn;
    private Integer gpsNumber;
    private Integer rtkNumber;
    /** NOT_START / FIXING / SUCCESSFUL / FAILED, o UNKNOWN si no llega el campo. */
    private String fixState;
    private Integer quality;
    /** true si lat/lon son fiables según el criterio de iadron.gnss. */
    private boolean gnssUsable;
    /** Explicación legible de por qué se considera (o no) utilizable. */
    private String reason;
    private long timestamp;

    public static PositioningStatus unknown(String droneSn, String reason) {
        return PositioningStatus.builder()
                .droneSn(droneSn)
                .fixState("UNKNOWN")
                .gnssUsable(false)
                .reason(reason)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
