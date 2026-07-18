package com.uts.pdfviewer

import android.content.Context
import android.provider.Settings
import android.util.Base64
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

/**
 * Offline activation / keygen protection — a faithful port of the app-activation-kit
 * (Ed25519 signature over "PREFIX|device|days", Base32 packet, device-bound, no
 * internet). Activation codes produced by the kit's keygen.mjs (with the matching
 * secret seed) are accepted here. The public key below is safe to ship; it cannot
 * generate codes. The secret seed stays only in your keygen.
 */
object License {

    /* ==================== EDIT: your key + prefix ====================
     * Paste the PUBLIC key (base64) printed by `node keygen.mjs new`.
     * While it starts with REPLACE_, protection is OFF (app runs free). */
    private const val PUBLIC_KEY = "W5Kc9hRB7lb9xSh/VqdR4T8GT6VaDznEwYQgXZpLZz0="
    private const val PREFIX = "UNI3"
    private const val SALT = "alaoufi:"
    /* ================================================================ */

    private const val B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFS = "guard"
    private const val K_D = "lic_d"
    private const val K_A = "lic_a"
    private const val K_S = "lic_s"
    private const val K_HAS = "lic_has"

    enum class State { DISABLED, NONE, ACTIVE, EXPIRED }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun disabled(): Boolean = PUBLIC_KEY.isEmpty() || PUBLIC_KEY.startsWith("REPLACE_")

    // ---- Base32 (same alphabet as the kit) ----
    private fun b32e(bytes: ByteArray): String {
        var bits = 0; var v = 0; val o = StringBuilder()
        for (b in bytes) {
            v = (v shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { o.append(B32[(v ushr (bits - 5)) and 31]); bits -= 5 }
            v = v and ((1 shl bits) - 1)
        }
        if (bits > 0) o.append(B32[(v shl (5 - bits)) and 31])
        return o.toString()
    }

    private fun b32d(s: String): ByteArray {
        var bits = 0; var v = 0; val o = ArrayList<Byte>()
        for (ch in s) {
            val k = B32.indexOf(ch); if (k < 0) continue
            v = (v shl 5) or k; bits += 5
            if (bits >= 8) { o.add(((v ushr (bits - 8)) and 255).toByte()); bits -= 8 }
            v = v and ((1 shl bits) - 1)
        }
        return o.toByteArray()
    }

    private fun norm(s: String): String = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    // ---- Device number: UNI3 standard — deterministic from hardware, so every
    // app on the same device shows the SAME number (one code unlocks them all):
    //   deviceId = Base32( SHA-256("alaoufi:" + ANDROID_ID)[0..10] )  → 16 chars.
    fun deviceId(c: Context): String {
        val androidId = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((SALT + androidId).toByteArray(Charsets.UTF_8))
        return b32e(digest.copyOfRange(0, 10))
    }

    fun deviceIdPretty(c: Context): String = deviceId(c).chunked(4).joinToString("-")

    private fun publicKeyBytes(): ByteArray = Base64.decode(PUBLIC_KEY, Base64.DEFAULT)

    private fun verifyEd(msg: ByteArray, sig: ByteArray): Boolean = try {
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val pk = EdDSAPublicKey(EdDSAPublicKeySpec(publicKeyBytes(), spec))
        val eng = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
        eng.initVerify(pk); eng.update(msg); eng.verify(sig)
    } catch (e: Exception) { false }

    /** Days (0 = permanent) if the code is valid for THIS device, else null. */
    private fun verify(c: Context, code: String): Int? {
        val p = b32d(norm(code)); if (p.size != 66) return null
        val d = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val sig = p.copyOfRange(2, 66)
        val msg = (PREFIX + "|" + norm(deviceId(c)) + "|" + d).toByteArray(Charsets.UTF_8)
        return if (verifyEd(msg, sig)) d else null
    }

    fun tryActivate(c: Context, code: String): Boolean {
        val d = verify(c, code) ?: return false
        writeRecord(c, d)
        return true
    }

    fun deactivate(c: Context) {
        prefs(c).edit().remove(K_D).remove(K_A).remove(K_S).remove(K_HAS).apply()
    }

    private fun writeRecord(c: Context, days: Int) {
        val t = System.currentTimeMillis()
        prefs(c).edit().putInt(K_D, days).putLong(K_A, t).putLong(K_S, t).putBoolean(K_HAS, true).apply()
    }

    fun state(c: Context): State {
        if (disabled()) return State.DISABLED
        val p = prefs(c)
        if (!p.getBoolean(K_HAS, false)) return State.NONE
        val d = p.getInt(K_D, 0)
        val a = p.getLong(K_A, 0)
        val s = p.getLong(K_S, 0)
        val now = System.currentTimeMillis()
        val eff = maxOf(now, s)              // clock guard: never rewind
        if (eff != s) p.edit().putLong(K_S, eff).apply()
        if (d == 0) return State.ACTIVE
        return if (eff < a + d.toLong() * 86_400_000L) State.ACTIVE else State.EXPIRED
    }

    /** Owner master unlock: entering the secret seed (64 hex) activates permanently. */
    fun recoverWithSeed(c: Context, seedHex: String): Boolean {
        val h = seedHex.trim().lowercase().replace(Regex("[^0-9a-f]"), "")
        if (h.length != 64) return false
        return try {
            val seed = ByteArray(32) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val derivedPub = EdDSAPrivateKeySpec(seed, spec).a.toByteArray()
            if (derivedPub.contentEquals(publicKeyBytes())) { writeRecord(c, 0); true } else false
        } catch (e: Exception) { false }
    }
}
