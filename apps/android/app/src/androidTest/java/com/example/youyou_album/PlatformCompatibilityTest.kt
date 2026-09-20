package com.example.youyou_album

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformCompatibilityTest {

    @Test
    fun installedAppTargetsAndroid12OrNewerWithoutLegacyWritePermission() {
        assertTrue(Build.VERSION.SDK_INT >= 31)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()

        when {
            Build.VERSION.SDK_INT >= 34 -> {
                assertTrue(requested.contains(Manifest.permission.READ_MEDIA_IMAGES))
                assertTrue(requested.contains(Manifest.permission.READ_MEDIA_VIDEO))
                assertTrue(requested.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            }
            Build.VERSION.SDK_INT >= 33 -> {
                assertTrue(requested.contains(Manifest.permission.READ_MEDIA_IMAGES))
                assertTrue(requested.contains(Manifest.permission.READ_MEDIA_VIDEO))
            }
            else -> assertTrue(requested.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
        assertFalse(requested.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
}
