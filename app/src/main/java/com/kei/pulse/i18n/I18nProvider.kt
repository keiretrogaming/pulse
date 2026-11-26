package com.kei.pulse.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.kei.pulse.PulseApp
import java.util.Locale

val LocalPulseStrings = staticCompositionLocalOf<PulseStrings> {
    ResourcePulseStrings(PulseApp.context)
}

object LocaleHelper {
    /**
     * Applies the given [AppLanguage] using Android 13+ [LocaleManager] (per-app language)
     * or standard configuration update on older versions.
     */
    fun applyLanguage(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val desired = when (language) {
                AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
                AppLanguage.EN -> LocaleList(Locale.ENGLISH)
                AppLanguage.ZH_TW -> LocaleList(Locale.TRADITIONAL_CHINESE)
            }
            if (localeManager != null && localeManager.applicationLocales != desired) {
                localeManager.applicationLocales = desired
            }
        } else {
            val targetLocale = when (language) {
                AppLanguage.SYSTEM -> null
                AppLanguage.EN -> Locale.ENGLISH
                AppLanguage.ZH_TW -> Locale.TRADITIONAL_CHINESE
            }
            if (targetLocale != null) {
                Locale.setDefault(targetLocale)
                val config = context.resources.configuration
                config.setLocale(targetLocale)
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
            }
        }
    }

    fun getLocalizedContext(context: Context, language: AppLanguage): Context {
        val targetLocale = when (language) {
            AppLanguage.SYSTEM -> {
                val defaultLocale = Locale.getDefault()
                if (defaultLocale.language.equals("zh", ignoreCase = true)) {
                    Locale.TRADITIONAL_CHINESE
                } else {
                    Locale.ENGLISH
                }
            }
            AppLanguage.ZH_TW -> Locale.TRADITIONAL_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(targetLocale)
        return context.createConfigurationContext(config)
    }
}

fun resolvePulseStrings(language: AppLanguage, context: Context = PulseApp.context): PulseStrings {
    val effectiveContext = if (language == AppLanguage.SYSTEM) {
        context
    } else {
        LocaleHelper.getLocalizedContext(context, language)
    }
    return ResourcePulseStrings(effectiveContext)
}

@Composable
fun ProvidePulseStrings(language: AppLanguage, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val strings = remember(context, language) { resolvePulseStrings(language, context) }
    CompositionLocalProvider(LocalPulseStrings provides strings) {
        content()
    }
}
