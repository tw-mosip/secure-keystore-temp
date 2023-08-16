package com.reactnativesecurekeystore

import android.os.Build
import android.security.keystore.KeyInfo
import android.util.Log
import androidx.biometric.BiometricPrompt.CryptoObject
import com.reactnativesecurekeystore.biometrics.Biometrics
import com.reactnativesecurekeystore.common.PemConverter
import com.reactnativesecurekeystore.common.Util.Companion.getKeyInfo
import com.reactnativesecurekeystore.common.Util.Companion.getLogTag
import com.reactnativesecurekeystore.dto.EncryptedOutput
import com.reactnativesecurekeystore.exception.InvalidEncryptionText
import com.reactnativesecurekeystore.exception.KeyInvalidatedException
import com.reactnativesecurekeystore.exception.KeyNotFound
import kotlinx.coroutines.runBlocking
import java.lang.Exception
import java.security.Key
import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.SecretKey

class SecureKeystoreImpl(
  private val keyGenerator: KeyGenerator, private val cipherBox: CipherBox, private val biometrics: Biometrics
) : SecureKeystore {
  private var ks: KeyStore = KeyStore.getInstance(KEYSTORE_TYPE)
  private val logTag = getLogTag(javaClass.simpleName)
  private val mutex = Object()

  init {
    ks.load(null)
  }

  /**  Generate secret key and store it in AndroidKeystore */
  override fun generateKey(alias: String, isAuthRequired: Boolean, authTimeout: Int?) {
    keyGenerator.generateKey(alias, isAuthRequired, authTimeout)
  }

  /** Generate a new key pair */
  override fun generateKeyPair(alias: String, isAuthRequired: Boolean, authTimeout: Int?): String {
    val keyPair = keyGenerator.generateKeyPair(alias, isAuthRequired, authTimeout)

    return PemConverter(keyPair.public).toPem()
  }

  override fun generateHmacSha256Key(alias: String) {
    keyGenerator.generateHmacKey(alias)
  }

  /** Remove key with provided name from security storage.  */
  override fun removeKey(alias: String) {
    if (ks.containsAlias(alias)) {
      ks.deleteEntry(alias)
    }
  }

  override fun hasAlias(alias: String): Boolean {
    if (ks.containsAlias(alias)) {
      return true
    }
    return false
  }

  override fun encryptData(
    alias: String,
    data: String,
    onSuccess: (encryptedText: String) -> Unit,
    onFailure: (code: Int, message: String) -> Unit
  ) {
    try {
      val key = getKeyOrThrow(alias)

      runBlocking {
        val createCryptoObject = {
          CryptoObject(cipherBox.initEncryptCipher(key))
        }

        val action = { cryptoObject: CryptoObject ->
          val encryptedText = cipherBox.encryptData(cryptoObject.cipher!!, data).toString()
          onSuccess(encryptedText)
        }

        biometrics.authenticateAndPerform(createCryptoObject, action, onFailure)
      }
    } catch (ex: Exception) {
      onFailure(ex.hashCode(), ex.message.toString())
    }
  }

  override fun decryptData(
    alias: String, encryptedText: String,
    onSuccess: (data: String) -> Unit,
    onFailure: (code: Int, message: String) -> Unit
  ) {
    try {
      val key = getKeyOrThrow(alias)

      if (!EncryptedOutput.validate(encryptedText)) {
        throw InvalidEncryptionText()
      }

      runBlocking {
        val encryptedOutput = EncryptedOutput(encryptedText)

        val createCryptoObject = { CryptoObject(cipherBox.initDecryptCipher(key, encryptedOutput)) }

        val action = { cryptoObject: CryptoObject ->
          val data = cipherBox.decryptData(cryptoObject.cipher!!, encryptedOutput)
          onSuccess(String(data))
        }

        biometrics.authenticateAndPerform(
          createCryptoObject,
          action,
          onFailure
        )
      }
    } catch (ex: Exception) {
      onFailure(ex.hashCode(), ex.message.toString())
    }
  }

  override fun sign(
    alias: String, data: String,
    onSuccess: (signature: String) -> Unit, onFailure: (code: Int, message: String) -> Unit
  ) {
    val key = getKeyOrThrow(alias) as PrivateKey

    runBlocking {
      val createCryptoObject = { CryptoObject(cipherBox.createSignature(key, data)) }

      val action = { cryptoObject: CryptoObject ->
        val signatureText = cipherBox.sign(cryptoObject.signature!!)
        onSuccess(signatureText)
      }

      biometrics.authenticateAndPerform(
        createCryptoObject,
        action,
        onFailure
      )
    }
  }

  override fun generateHmacSha(
    alias: String, data: String,
    onSuccess: (sha: String) -> Unit,
    onFailure: (code: Int, message: String) -> Unit
  ) {
    try {
      val key = getKeyOrThrow(alias) as SecretKey
      val hmacSha: ByteArray = cipherBox.generateHmacSha(key, data)

      onSuccess(String(hmacSha))
    } catch (e: RuntimeException) {
      Log.e(logTag, "Exception in Hmac generation: ", e)

      onFailure(e.hashCode(), e.message.toString())
    }
  }

  private fun getKeyOrThrow(alias: String): Key {
    if (!ks.containsAlias(alias)) {
      throw KeyNotFound("Key not found for the alias: $alias")
    }
    val key = ks.getKey(alias, null)
    val keyInfo: KeyInfo = getKeyInfo(key)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      if(keyInfo.isInvalidatedByBiometricEnrollment) {
        throw KeyInvalidatedException("Key Invalidated due to biometric enrollment")
      }
    }

    return key;
  }

  override fun removeAllKeys() {
    keyGenerator.removeAllKeys()
  }
}
