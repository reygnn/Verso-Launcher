package com.github.reygnn.verso_launcher

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.os.UserHandle
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * [entry] as the system lists it in the profile of [user]. LauncherActivityInfo's constructor is
 * hidden, so it is called by reflection: the one place these tests leave public API.
 *
 * Its label needs `@GraphicsMode(NATIVE)`: LauncherActivityInfo.getLabel keeps a label only if a
 * Paint finds glyphs for it, and without native graphics Robolectric finds none, so every app would
 * be listed under its package.
 */
internal fun launcherActivityOf(context: Context, entry: AppEntry, user: UserHandle): LauncherActivityInfo {
    val info = ActivityInfo().apply {
        packageName = entry.packageName
        name = entry.className
        nonLocalizedLabel = entry.label
        applicationInfo = ApplicationInfo().apply { packageName = entry.packageName }
    }
    val internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal")
    val incremental = Class.forName("android.content.pm.IncrementalStatesInfo")
    val internal = ReflectionHelpers.callConstructor(
        internalClass,
        ClassParameter.from(ActivityInfo::class.java, info),
        ClassParameter(incremental, ReflectionHelpers.callConstructor(incremental)),
        ClassParameter.from(UserHandle::class.java, user),
        ClassParameter.from(Boolean::class.javaPrimitiveType, false),
    )
    return ReflectionHelpers.callConstructor(
        LauncherActivityInfo::class.java,
        ClassParameter.from(Context::class.java, context),
        ClassParameter(internalClass, internal),
    )
}
