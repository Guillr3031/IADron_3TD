package com.dji.sdk.config.version;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * @author sean
 * @version 1.7
 * @date 2023/9/7
 */
public enum Dock2ThingVersionEnum implements IThingVersion {

    V1_1_2("1.1.2", CloudSDKVersionEnum.V1_0_1),

    V1_2_0("1.2.0", CloudSDKVersionEnum.V1_0_3),

    // IADron: firmware actual del Dock 2 (oct 2026). El CloudSDK del demo solo llega a 1.0.3.
    V1_3_2("1.3.2", CloudSDKVersionEnum.V1_0_3),

    ;

    private final String thingVersion;

    private final CloudSDKVersionEnum cloudSDKVersion;

    Dock2ThingVersionEnum(String thingVersion, CloudSDKVersionEnum cloudSDKVersion) {
        this.thingVersion = thingVersion;
        this.cloudSDKVersion = cloudSDKVersion;
    }

    @JsonValue
    public String getThingVersion() {
        return thingVersion;
    }

    public CloudSDKVersionEnum getCloudSDKVersion() {
        return cloudSDKVersion;
    }

    public static Dock2ThingVersionEnum find(String thingVersion) {
        // IADron: si una actualización de firmware trae una versión desconocida, en lugar de
        // rechazar el Dock (CloudSDKVersionException -> sale "Disconnected") se usa la más nueva conocida.
        Dock2ThingVersionEnum[] versiones = values();
        return Arrays.stream(versiones).filter(thingVersionEnum -> thingVersionEnum.thingVersion.equals(thingVersion))
                .findAny().orElse(versiones[versiones.length - 1]);
    }
}