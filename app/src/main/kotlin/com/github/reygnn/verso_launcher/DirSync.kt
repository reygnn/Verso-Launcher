package com.github.reygnn.verso_launcher

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * fsync [dir] so a preceding rename() survives power loss, not merely is visible: the
 * [ConfigStore] syncDir MainActivity hands in. Best-effort: a failure only weakens durability,
 * so it is logged, not thrown.
 */
internal fun fsyncDir(dir: File, log: (String, Exception?) -> Unit) {
    var fd: FileDescriptor? = null
    try {
        fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        Os.fsync(fd)
    } catch (e: ErrnoException) {
        log("dir fsync failed (non-fatal): ${dir.path}", e)
    } finally {
        if (fd != null) {
            @Suppress("SwallowedException") try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                // Nothing actionable at close time; the rename or delete already happened.
            }
        }
    }
}
