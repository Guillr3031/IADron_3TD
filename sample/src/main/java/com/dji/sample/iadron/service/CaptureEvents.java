package com.dji.sample.iadron.service;

import com.dji.sample.component.oss.model.OssConfiguration;
import com.dji.sample.iadron.model.CaptureEvent;
import com.dji.sdk.cloudapi.media.FileUploadCallback;
import com.dji.sdk.cloudapi.media.FileUploadCallbackFile;
import com.dji.sdk.cloudapi.media.MediaFileExtension;
import com.dji.sdk.cloudapi.media.MediaFileMetadata;
import com.dji.sdk.cloudapi.media.MediaUploadCallbackRequest;
import com.dji.sdk.cloudapi.media.Position;
import com.dji.sdk.cloudapi.media.UploadCallbackFileExtension;
import com.dji.sdk.cloudapi.media.UploadCallbackFileMetadata;

import java.util.Locale;

/**
 * Convierte los dos avisos de "foto subida" del demo en un {@link CaptureEvent}:
 *  - Dock 2:  evento MQTT file_upload_callback (MediaServiceImpl.fileUploadCallback)
 *  - Pilot 2: callback HTTP /media/api/v1/files/{ws}/upload-callback (MediaServiceImpl.saveMediaFile)
 */
public final class CaptureEvents {

    private CaptureEvents() {
    }

    public static CaptureEvent fromDock(String gatewaySn, FileUploadCallback callback) {
        FileUploadCallbackFile file = callback.getFile();
        CaptureEvent.CaptureEventBuilder b = CaptureEvent.builder()
                .origin("DOCK_EVENT")
                .gatewaySn(gatewaySn)
                .fileName(file.getName())
                .objectKey(file.getObjectKey())
                .bucket(OssConfiguration.bucket)
                .thermal(isThermal(file.getName()))
                .receivedAt(System.currentTimeMillis());
        UploadCallbackFileExtension ext = file.getExt();
        if (ext != null) {
            b.flightId(ext.getFlightId())
                    .droneModel(ext.getDroneModelKey() == null ? null : ext.getDroneModelKey().name())
                    .payloadModel(ext.getPayloadModelKey() == null ? null : ext.getPayloadModelKey().name());
        }
        UploadCallbackFileMetadata md = file.getMetadata();
        if (md != null) {
            b.relativeAltitude(md.getRelativeAltitude())
                    .gimbalYawDegree(md.getGimbalYawDegree())
                    .createdTime(md.getCreatedTime() == null ? null : md.getCreatedTime().toString());
            fillPosition(b, md.getShootPosition());
        }
        return b.build();
    }

    public static CaptureEvent fromPilot(MediaUploadCallbackRequest file) {
        CaptureEvent.CaptureEventBuilder b = CaptureEvent.builder()
                .origin("PILOT_HTTP")
                .fileName(file.getName())
                .objectKey(file.getObjectKey())
                .bucket(OssConfiguration.bucket)
                .thermal(isThermal(file.getName()))
                .receivedAt(System.currentTimeMillis());
        MediaFileExtension ext = file.getExt();
        if (ext != null) {
            b.droneSn(ext.getSn())
                    .droneModel(ext.getDroneModelKey() == null ? null : ext.getDroneModelKey().name())
                    .payloadModel(ext.getPayloadModelKey() == null ? null : ext.getPayloadModelKey().name());
        }
        MediaFileMetadata md = file.getMetadata();
        if (md != null) {
            b.relativeAltitude(md.getRelativeAltitude())
                    .gimbalYawDegree(md.getGimbalYawDegree())
                    .createdTime(md.getCreatedTime() == null ? null : md.getCreatedTime().toString());
            fillPosition(b, md.getShootPosition());
        }
        return b.build();
    }

    /** Las fotos de la lente térmica de DJI terminan en _T (ej. DJI_20261009101500_0001_T.JPG). */
    static boolean isThermal(String fileName) {
        if (fileName == null) {
            return false;
        }
        String n = fileName.toUpperCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        return base.endsWith("_T");
    }

    private static void fillPosition(CaptureEvent.CaptureEventBuilder b, Position p) {
        if (p != null) {
            b.shootLatitude(p.getLat()).shootLongitude(p.getLng());
        }
    }
}
