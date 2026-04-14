package com.hertzify.settings.fragments.miscellaneous

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppConfig(
    val packageName: String,
    val appName: String,
    val props: Map<String, String>
)

data class DeviceProfile(
    val name: String,
    val props: Map<String, String>
)

data class AppPickerEntry(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val pm: PackageManager
) {
    private var _icon: Drawable? = null
    fun getIcon(): Drawable? {
        if (_icon == null) _icon = runCatching { info.loadIcon(pm) }.getOrNull()
        return _icon
    }
}

object AppSpoofConstants {
    const val FIELD_MODEL        = "MODEL"
    const val FIELD_MANUFACTURER = "MANUFACTURER"

    val DEFAULT_PROFILES = listOf(
        DeviceProfile("ROG Phone 8 Pro",  mapOf(FIELD_MODEL to "ASUS_AI2401_A",    FIELD_MANUFACTURER to "asus")),
        DeviceProfile("Galaxy S24 Ultra", mapOf(FIELD_MODEL to "SM-S928B",          FIELD_MANUFACTURER to "samsung")),
        DeviceProfile("Xiaomi 13 Pro",    mapOf(FIELD_MODEL to "2210132C",           FIELD_MANUFACTURER to "Xiaomi")),
        DeviceProfile("OnePlus 9 Pro",    mapOf(FIELD_MODEL to "LE2101",             FIELD_MANUFACTURER to "OnePlus")),
        DeviceProfile("Black Shark 4",    mapOf(FIELD_MODEL to "2SM-X706B",          FIELD_MANUFACTURER to "blackshark")),
        DeviceProfile("Lenovo Y700",      mapOf(FIELD_MODEL to "Lenovo TB-9707F",    FIELD_MANUFACTURER to "Lenovo")),
    )
}