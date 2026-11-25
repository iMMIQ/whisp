// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.benchmark

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark Baseline Profile generator covering cold startup, library grid scrolling, and tab
 * navigation. Run on a rooted/userdebug API 28+ device or emulator with: `./gradlew
 * :benchmark:connectedBenchmarkAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.class=app.whisp.benchmark.BaselineProfileGenerator`
 */
@RequiresApi(Build.VERSION_CODES.P)
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        rule.collect(packageName = "app.whisp", includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("app.whisp")), 5_000L)
            device.waitForIdle()
            device.findObject(By.desc("来源"))?.click()
            device.waitForIdle()
            device.findObject(By.desc("设置"))?.click()
            device.waitForIdle()
            device.findObject(By.desc("作品库"))?.click()
            device.waitForIdle()
        }
    }
}
