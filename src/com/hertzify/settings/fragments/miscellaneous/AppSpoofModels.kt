package com.hertzify.settings.fragments.miscellaneous

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppConfig(
    val packageName: String,
    val appName: String,
    val profileName: String,
    val props: Map<String, String>
)

data class DeviceProfile(
    val name: String,
    val props: Map<String, String>
)

data class AppPickerEntry(
    val packageName: String,
    val label: String,
    val ai: ApplicationInfo,
    val pm: PackageManager
) {
    private var iconCache: Drawable? = null
    fun getIcon(): Drawable? {
        if (iconCache == null) iconCache = ai.loadIcon(pm)
        return iconCache
    }
}

object AppSpoofConstants {
    const val KEY_ENABLED = "app_spoof_enabled"
    const val KEY_APP_LIST_CAT = "app_spoof_app_list_category"
    
    const val MENU_MANAGE_PROFILES = 1
    const val MENU_IMPORT_JSON = 2
    const val MENU_EXPORT_JSON = 3
    const val MENU_CLEAR_ALL = 4
    
    const val FIELD_MODEL = "MODEL"
    const val FIELD_MANUFACTURER = "MANUFACTURER"
    
    val DEFAULT_PROFILES = listOf(
        DeviceProfile("ROG Phone 8 Pro", mapOf(
            FIELD_MODEL to "ASUS_AI2401_A", 
            FIELD_MANUFACTURER to "asus"
        )),
        DeviceProfile("Galaxy S24 Ultra", mapOf(
            FIELD_MODEL to "SM-S928B", 
            FIELD_MANUFACTURER to "samsung"
        )),
        DeviceProfile("Xiaomi 13 Pro", mapOf(
            FIELD_MODEL to "2210132C", 
            FIELD_MANUFACTURER to "Xiaomi"
        )),
        DeviceProfile("OnePlus 9 Pro", mapOf(
            FIELD_MODEL to "LE2101", 
            FIELD_MANUFACTURER to "OnePlus"
        )),
        DeviceProfile("Black Shark 4", mapOf(
            FIELD_MODEL to "2SM-X706B", 
            FIELD_MANUFACTURER to "blackshark"
        )),
        DeviceProfile("Lenovo Y700", mapOf(
            FIELD_MODEL to "Lenovo TB-9707F", 
            FIELD_MANUFACTURER to "Lenovo"
        ))
    )
}