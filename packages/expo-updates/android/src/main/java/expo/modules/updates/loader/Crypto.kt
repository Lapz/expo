package expo.modules.updates.loader

import android.annotation.SuppressLint
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.*
import java.security.*
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec

object Crypto {
  private const val PUBLIC_KEY_URL = "https://exp.host/--/manifest-public-key"

  suspend fun verifyPublicRSASignature(
    plainText: String,
    cipherText: String,
    fileDownloader: FileDownloader,
  ): Boolean {
    return fetchPublicKeyAndVerifyPublicRSASignature(true, plainText, cipherText, fileDownloader)
  }

  // On first attempt use cache. If verification fails try a second attempt without
  // cache in case the keys were actually rotated.
  // On second attempt reject promise if it fails.
  private suspend fun fetchPublicKeyAndVerifyPublicRSASignature(
    isFirstAttempt: Boolean,
    plainText: String,
    cipherText: String,
    fileDownloader: FileDownloader,
  ): Boolean {
    val cacheControl = if (isFirstAttempt) CacheControl.FORCE_CACHE else CacheControl.FORCE_NETWORK
    val request = Request.Builder()
      .url(PUBLIC_KEY_URL)
      .cacheControl(cacheControl)
      .build()
    val response = fileDownloader.downloadData(request)
    val exception: Exception = try {
      return verifyPublicRSASignatureInternal(
        response.body()!!.string(), plainText, cipherText
      )
    } catch (e: Exception) {
      e
    }
    if (isFirstAttempt) {
      return fetchPublicKeyAndVerifyPublicRSASignature(
        false,
        plainText,
        cipherText,
        fileDownloader,
      )
    } else {
      throw exception
    }
  }

  @Throws(
    NoSuchAlgorithmException::class,
    InvalidKeySpecException::class,
    InvalidKeyException::class,
    SignatureException::class
  )
  private fun verifyPublicRSASignatureInternal(
    publicKey: String,
    plainText: String,
    cipherText: String
  ): Boolean {
    // remove comments from public key
    val publicKeySplit = publicKey.split("\\r?\\n".toRegex()).toTypedArray()
    var publicKeyNoComments = ""
    for (line in publicKeySplit) {
      if (!line.contains("PUBLIC KEY-----")) {
        publicKeyNoComments += line + "\n"
      }
    }

    val signature = Signature.getInstance("SHA256withRSA")
    val decodedPublicKey = Base64.decode(publicKeyNoComments, Base64.DEFAULT)
    val publicKeySpec = X509EncodedKeySpec(decodedPublicKey)
    @SuppressLint("InlinedApi") val keyFactory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
    val key = keyFactory.generatePublic(publicKeySpec)
    signature.initVerify(key)
    signature.update(plainText.toByteArray())
    return signature.verify(Base64.decode(cipherText, Base64.DEFAULT))
  }

  interface RSASignatureListener {
    fun onError(exception: Exception, isNetworkError: Boolean)
    fun onCompleted(isValid: Boolean)
  }
}
