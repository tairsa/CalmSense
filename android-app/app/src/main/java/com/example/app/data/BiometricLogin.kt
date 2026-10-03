package com.example.app.data

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Fingerprint sign-in, the usual way: turned on by hand in Settings by
 * confirming the password once, which is then kept encrypted under a Keystore
 * key that only a fingerprint unlocks. The sign-in screen offers the
 * fingerprint instead of the password from then on.
 *
 * Android destroys the key when a new fingerprint is enrolled, so someone who
 * adds their own finger to the phone cannot use it to get in; the feature just
 * switches itself off. It survives signing out - that is when it is needed.
 */
/** The activity behind a Compose [Context], which BiometricPrompt needs. */
tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

object BiometricLogin {

    private const val KEY_ALIAS = "calmsense_fingerprint_login"
    private const val PREFS = "calmsense_fingerprint"
    private const val KEY_EMAIL = "email"
    private const val KEY_SECRET = "secret"
    private const val KEY_IV = "iv"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    sealed interface Unlock {
        data class Ok(val email: String, val password: String) : Unlock
        data object Cancelled : Unlock
        /** A fingerprint was added or removed since setup; the feature is now off. */
        data object Invalidated : Unlock
    }

    /** True when the phone has a fingerprint enrolled that can unlock a key. */
    fun canUse(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** The account fingerprint sign-in is set up for, or null when it is off. */
    fun enabledFor(context: Context): String? {
        val p = prefs(context)
        return p.getString(KEY_EMAIL, null)?.takeIf { p.contains(KEY_SECRET) }
    }

    /** Ask for a fingerprint, then store [password] so only that unlocks it. */
    suspend fun enable(activity: FragmentActivity, email: String, password: String,
                       title: String, cancel: String): Boolean {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey()) }
        val unlocked = authenticate(activity, cipher, title, cancel) ?: return false
        val secret = unlocked.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs(activity).edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_SECRET, Base64.encodeToString(secret, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(unlocked.iv, Base64.NO_WRAP))
            .commit()
        return true
    }

    suspend fun unlock(activity: FragmentActivity, title: String, cancel: String): Unlock {
        val p = prefs(activity)
        val email = enabledFor(activity) ?: return Unlock.Cancelled
        val secret = Base64.decode(p.getString(KEY_SECRET, ""), Base64.NO_WRAP)
        val iv = Base64.decode(p.getString(KEY_IV, ""), Base64.NO_WRAP)
        val cipher = try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(activity)
            return Unlock.Invalidated
        }
        val unlocked = authenticate(activity, cipher, title, cancel) ?: return Unlock.Cancelled
        return Unlock.Ok(email, String(unlocked.doFinal(secret), Charsets.UTF_8))
    }

    fun disable(context: Context) {
        prefs(context).edit().clear().commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /** Shows the system fingerprint sheet; the cipher it unlocks, or null if dismissed. */
    private suspend fun authenticate(activity: FragmentActivity, cipher: Cipher,
                                     title: String, cancel: String): Cipher? =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(result.cryptoObject?.cipher)
                    }
                    // A wrong finger keeps the sheet up for another try; only
                    // cancel, lockout and the like end it.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setNegativeButtonText(cancel)
                    .setAllowedAuthenticators(BIOMETRIC_STRONG)
                    .build(),
                BiometricPrompt.CryptoObject(cipher),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    private fun newKey(): SecretKey {
        keyStore().deleteEntry(KEY_ALIAS)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
        }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
