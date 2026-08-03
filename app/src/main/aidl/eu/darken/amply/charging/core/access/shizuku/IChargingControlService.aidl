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
    // Read-only capability probe for LineageOS charge control (`dumpsys lineagehealth`). Takes NO arguments —
    // the command is a compile-time constant, so there is nothing for a caller to influence. Needs the shell UID
    // because android.permission.DUMP is not grantable to a normal app. Returns the reduced `PROVIDER|mode`
    // form, never the raw dump: that text carries the user's charging schedule and battery level, so it is
    // parsed inside this process and only the two non-sensitive fields cross the boundary.
    String dumpLineageChargingControl() = 6;
    void destroy() = 16777114;
}
