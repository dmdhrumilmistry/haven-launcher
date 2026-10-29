package app.haven.launcher.system

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks for the phone's own screen lock: fingerprint or face where set up, with PIN, pattern or
 * password always available as a fallback. Haven never sees or stores any of it.
 */
object DeviceAuth {
    private val counter = AtomicInteger()

    /** False when the phone has no screen lock, so there's nothing to authenticate against. */
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Calls [onSuccess] only after the person proves it's them. Cancelling simply does nothing.
     * If the phone has no lock at all, there's nothing to protect with, so it succeeds.
     */
    fun authenticate(activity: ComponentActivity, title: String, subtitle: String?, onSuccess: () -> Unit) {
        if (!isAvailable(activity)) {
            onSuccess()
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
            if (subtitle != null) builder.setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            try {
                builder.build().authenticate(
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                    },
                )
                return
            } catch (e: Exception) {
                // Fall through to the classic confirm-credential screen.
            }
        }
        confirmCredential(activity, title, subtitle, onSuccess)
    }

    @Suppress("DEPRECATION")
    private fun confirmCredential(activity: ComponentActivity, title: String, subtitle: String?, onSuccess: () -> Unit) {
        val intent = activity.getSystemService(KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent == null) {
            onSuccess()
            return
        }
        var launcher: ActivityResultLauncher<android.content.Intent>? = null
        launcher = activity.activityResultRegistry.register(
            "haven-auth-${counter.incrementAndGet()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher?.unregister()
            if (result.resultCode == Activity.RESULT_OK) onSuccess()
        }
        launcher.launch(intent)
    }
}

/** The ComponentActivity behind a Compose context, if there is one. */
tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
