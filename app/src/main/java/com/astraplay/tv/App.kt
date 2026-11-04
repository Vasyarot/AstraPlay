package com.astraplay.tv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import coil.request.CachePolicy
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator

/**
 * Точка входа. Здесь настраиваются глобальные оптимизации памяти,
 * критичные для устройств с 1 ГБ RAM.
 */
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Фиксируем момент сворачивания: после убийства фонового процесса
        // главный экран решает, восстанавливать ли последний экран ([[LastScreen]]).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) = LastScreen.markPaused(this@App)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Лёгкий ImageLoader для слабых устройств:
     *  - RGB_565: каждый постер занимает вдвое меньше памяти;
     *  - кэш памяти ограничен 8 МБ;
     *  - дисковый кэш 64 МБ;
     *  - без crossfade (лишние кадры анимации = лишний GPU-трафик).
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .components {
                add(BitmapFactoryDecoder.Factory())
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizeBytes(8 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true)
            .crossfade(false)
            .build()

    companion object {
        fun get(context: Context): App = context.applicationContext as App
    }
}
