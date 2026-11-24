// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.whisp.system.ExternalIntentParser

/**
 * Target of `android:manageSpaceActivity` so Android Settings -> Storage -> Manage Space opens
 * Whisp's built-in cache & offline storage page instead of wiping the entire database.
 */
class ManageSpaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ExternalIntentParser.ACTION_OPEN_STORAGE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
