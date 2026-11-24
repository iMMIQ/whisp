// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * Platform-native biometric and device credential authentication without extra third-party
 * dependencies. Supports API 30+ BiometricPrompt authenticators, API 29 device credential prompt,
 * and API 26-28 KeyguardManager confirmation fallback.
 */
object AppLockAuthenticator {
    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return false
        return km.isDeviceSecure
    }

    fun authenticate(
        context: Context,
        title: String = "解锁 Whisp",
        subtitle: String = "验证指纹、人脸或锁屏密码以继续",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onLegacyCredentialIntent: (Intent) -> Unit,
    ): CancellationSignal? {
        if (!isDeviceSecure(context)) {
            onSuccess()
            return null
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            val prompt =
                BiometricPrompt.Builder(context)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                    .build()
            prompt.authenticate(
                signal,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult?,
                    ) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            onError(errString?.toString().orEmpty().ifBlank { "身份验证未完成" })
                        }
                    }
                },
            )
            return signal
        }
        if (Build.VERSION.SDK_INT == 29) {
            @Suppress("DEPRECATION")
            val prompt =
                BiometricPrompt.Builder(context)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setDeviceCredentialAllowed(true)
                    .build()
            val signal = CancellationSignal()
            prompt.authenticate(
                signal,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult?,
                    ) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            onError(errString?.toString().orEmpty().ifBlank { "身份验证未完成" })
                        }
                    }
                },
            )
            return signal
        }
        @Suppress("DEPRECATION")
        val legacyIntent =
            context
                .getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent(title, subtitle)
        if (legacyIntent != null) {
            onLegacyCredentialIntent(legacyIntent)
        } else {
            onSuccess()
        }
        return null
    }
}
