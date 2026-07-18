package eu.darken.amply.charging.core.access.shizuku;

interface IChargingControlService {
    String readSetting(String namespace, String key) = 1;
    boolean writeSetting(String namespace, String key, String value) = 2;
    boolean grantWriteSecureSettings(String packageName) = 3;
    String snapshotSettings(String namespace) = 4;
    void destroy() = 16777114;
}
