package com.example.displayconnect.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.zip.CRC32

/** Local bench entry point only. No network lookup, upload or automatic transfer. */
object LocalFirmwareFile {
    const val TRUSTED_HECATE_SPKI_SHA256 = "ddce1799c6afdeaa25fef1b4928ee1b8b7ddcf5af1048b33eb19307421ee2852"
    const val MAX_BYTES = 0x400000
    fun read(input: InputStream): LocalFirmwareImage = readWithVerifier(input, FirmwareSignatureVerifier(TRUSTED_HECATE_SPKI_SHA256))
    internal fun readWithVerifier(input: InputStream, verifier: FirmwareSignatureVerifier): LocalFirmwareImage {
        val bytes = readBounded(input)
        HardwareUpdatePolicy.requireImage(HardwareUpdatePolicy.G, bytes)
        verifier.requireAuthentic(bytes)
        val version = bytes.copyOfRange(48,80).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
        return LocalFirmwareImage(version,bytes.size,sha(bytes),bytes)
    }
    internal fun readBounded(input: InputStream): ByteArray {
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer,0,minOf(buffer.size,MAX_BYTES-output.size()+1))
            if(count<0)break
            if(count==0) {
                val one=input.read();if(one<0)break
                require(output.size()<MAX_BYTES) { "Archivo demasiado grande para Hecate" };output.write(one)
            } else {
                require(output.size()+count<=MAX_BYTES) { "Archivo demasiado grande para Hecate" }
                output.write(buffer,0,count)
            }
        }
        return output.toByteArray()
    }
    internal fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}

class LocalFirmwareImage internal constructor(val version:String,val size:Int,val sha256:String,internal val bytes:ByteArray)

/** ESP secure-boot-v2 RSA3072 block. Trust is pinned to the existing Hecate key,
 * not to a key supplied by the selected file. Device-side verification remains mandatory. */
internal class FirmwareSignatureVerifier(private val trustedSpkiSha:String) {
    fun requireAuthentic(bytes:ByteArray) {
        require(bytes.size>=8192 && bytes.size%4096==0) { "Falta la firma de firmware" }
        val start=bytes.size-4096
        require(bytes[start]==0xe7.toByte() && bytes[start+1]==2.toByte()) { "Firma de firmware no compatible" }
        fun le(offset:Int,count:Int)=BigInteger(1,bytes.copyOfRange(start+offset,start+offset+count).reversedArray())
        val crc=CRC32().apply{update(bytes,start,1196)}.value
        require(le(1196,4).toLong()==crc) { "Bloque de firma danado" }
        require((start+1216 until bytes.size).all{bytes[it]==0xff.toByte()}) { "Bloque de firma inesperado" }
        val digest=MessageDigest.getInstance("SHA-256").apply{update(bytes,0,start)}.digest()
        require(digest.contentEquals(bytes.copyOfRange(start+4,start+36))) { "Contenido de firmware alterado" }
        val modulus=le(36,384);val exponent=le(420,4)
        require(modulus.bitLength()==3072 && exponent==BigInteger.valueOf(65537)) { "Clave de firma no compatible" }
        val key=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus,exponent))
        require(LocalFirmwareFile.sha(key.encoded)==trustedSpkiSha) { "El archivo no esta firmado con la clave de Hecate" }
        val verifier=runCatching{Signature.getInstance("RSASSA-PSS")}.getOrElse{Signature.getInstance("SHA256withRSA/PSS")}
        verifier.initVerify(key)
        verifier.setParameter(PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1))
        verifier.update(bytes,0,start)
        require(verifier.verify(bytes.copyOfRange(start+812,start+1196).reversedArray())) { "Firma de firmware no valida" }
    }
}
