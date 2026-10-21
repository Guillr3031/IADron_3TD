package com.dji.sdk.cloudapi.device;

import com.dji.sdk.cloudapi.livestream.LiveErrorCodeEnum;
import com.dji.sdk.common.ErrorCodeSourceEnum;
import com.dji.sdk.mqtt.MqttReply;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @author sean.zhou
 * @version 0.1
 * @date 2021/11/23
 */
public class DockLiveErrorStatus {

    private static final int MOD = 100_000;

    private ErrorCodeSourceEnum source = ErrorCodeSourceEnum.DOCK;

    private LiveErrorCodeEnum errorCode;

    private boolean success;

    // IADron: código original recibido, para no perderlo si el SDK no lo conoce.
    private int rawCode;

    @Override
    public String toString() {
        return "{" +
                "errorCode=" + getCode() +
                ", errorMsg=" + getMessage() +
                '}';
    }

    @JsonCreator
    public DockLiveErrorStatus(int code) {
        this.rawCode = code;
        this.success = MqttReply.CODE_SUCCESS == code;
        // IADron: con el firmware 17.x el Dock manda error_status = 0 cuando el directo va bien.
        // 0 / MOD = 0 no es ninguna fuente conocida y el demo lanzaba excepción, perdiendo el live_status.
        if (this.success) {
            return;
        }
        try {
            this.source = ErrorCodeSourceEnum.find(code / MOD);
            this.errorCode = LiveErrorCodeEnum.find(code % MOD);
        } catch (RuntimeException e) {
            // Código que el SDK no conoce: se conserva en rawCode.
            this.errorCode = null;
        }
    }

    public String getMessage() {
        if (success) {
            return "success";
        }
        return errorCode != null ? errorCode.getMessage() : "unknown (" + rawCode + ")";
    }

    @JsonValue
    public Integer getCode() {
        if (success || errorCode == null) {
            return rawCode;
        }
        return source.getSource() * MOD + errorCode.getCode();
    }

    public boolean isSuccess() {
        return success;
    }
}