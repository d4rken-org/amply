package eu.darken.amply.charging.core.access.shizuku;

interface IChargingControlService {
    String readSetting(String namespace, String key) = 1;
    boolean writeSetting(String namespace, String key, String value) = 2;
    boolean grantWriteSecureSettings(String packageName) = 3;
    String snapshotSettings(String namespace) = 4;
    // LineageOS charging control lives in a separate provider (content://lineagesettings/system) that
    // /system/bin/settings cannot write. Dedicated op: `content insert` (Shizuku shell UID holds the
    // Lineage write permission). Reads are unprivileged (LineageSettingsClient), so there is no read op.
    boolean writeLineageSetting(String key, String value) = 5;
    void destroy() = 16777114;
}
