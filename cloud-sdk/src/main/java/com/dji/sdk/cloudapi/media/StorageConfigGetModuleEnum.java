package com.dji.sdk.cloudapi.media;

import com.dji.sdk.exception.CloudSDKException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * @author sean
 * @version 1.7
 * @date 2023/6/27
 */
public enum StorageConfigGetModuleEnum {

    MEDIA(0),

    PSDK_UI(1),

    UNKNOWN(-1);


    private final int module;

    StorageConfigGetModuleEnum(int module) {
        this.module = module;
    }

    @JsonValue
    public int getModule() {
        return module;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static StorageConfigGetModuleEnum find(Object value) {
        int m;
        try{
            m = Integer.parseInt(String.valueOf(value).trim());
        }catch (NumberFormatException e){
            return UNKNOWN;
        }
        return Arrays.stream(values()).filter(x ->x.module == m).findAny().orElse(UNKNOWN);
    }
}
