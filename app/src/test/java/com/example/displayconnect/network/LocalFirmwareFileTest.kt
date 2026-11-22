package com.example.displayconnect.network

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.interfaces.RSAPublicKey
import java.util.zip.CRC32

/** Synthetic crypto/policy fixtures only, never a bootable firmware image. */
class LocalFirmwareFileTest {
    companion object {
        private val keys=KeyPairGenerator.getInstance("RSA").apply{initialize(3072)}.generateKeyPair()
        private val trust=LocalFirmwareFile.sha(keys.public.encoded)
        fun fixture(version:String="0.28.21-175g-ota1-LOCAL",project:String="hecate-175g"):ByteArray {
            val b=ByteArray(8192){0xff.toByte()};b.fill(0,0,4096)
            b[0]=0xe9.toByte();intArrayOf(0x32,0x54,0xcd,0xab).forEachIndexed{i,n->b[32+i]=n.toByte()}
            version.toByteArray().copyInto(b,48);project.toByteArray().copyInto(b,80)
            val start=4096;b[start]=0xe7.toByte();b[start+1]=2;b[start+2]=0;b[start+3]=0
            java.security.MessageDigest.getInstance("SHA-256").digest(b.copyOfRange(0,start)).copyInto(b,start+4)
            val key=keys.public as RSAPublicKey
            fun put(n:BigInteger,offset:Int,size:Int){val le=n.toByteArray().reversedArray();repeat(size){b[start+offset+it]=if(it<le.size)le[it] else 0}}
            put(key.modulus,36,384);put(key.publicExponent,420,4)
            put(BigInteger.ONE.shiftLeft(6144).mod(key.modulus),424,384)
            put(key.modulus.modInverse(BigInteger.ONE.shiftLeft(32)).negate().mod(BigInteger.ONE.shiftLeft(32)),808,4)
            val s=Signature.getInstance("RSASSA-PSS");s.setParameter(PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1));s.initSign(keys.private);s.update(b,0,start);s.sign().reversedArray().copyInto(b,start+812)
            b.fill(0xff.toByte(),start+1200,start+1216);fixCrc(b);return b
        }
        fun fixCrc(b:ByteArray){val start=b.size-4096;val crc=CRC32().apply{update(b,start,1196)}.value;repeat(4){b[start+1196+it]=((crc ushr (it*8)) and 255).toByte()}}
        private val image=fixture()
    }
    private fun prepare(b:ByteArray)=LocalFirmwareFile.readWithVerifier(ByteArrayInputStream(b),FirmwareSignatureVerifier(trust))
    private fun rejected(b:ByteArray){assertTrue(runCatching{prepare(b)}.isFailure)}
    @Test fun validSyntheticSignedGFileNeedsExplicitLaterTransfer(){val c=prepare(image);assertEquals("0.28.21-175g-ota1-LOCAL",c.version);assertEquals(8192,c.size);assertEquals(LocalFirmwareFile.sha(image),c.sha256);assertArrayEquals(image,c.bytes)}
    @Test fun productionTrustDoesNotAcceptFixtureKey(){assertTrue(runCatching{LocalFirmwareFile.read(ByteArrayInputStream(image))}.isFailure)}
    @Test fun foreignImageRejected(){rejected(fixture(project="muse-gadget"))}
    @Test fun generationMustRetainOta(){rejected(fixture(version="0.28.19-175g-rawdiag-LOCAL"))}
    @Test fun unsignedImageRejected(){rejected(image.copyOfRange(0,4096))}
    @Test fun truncatedImageRejected(){rejected(image.copyOf(image.size-1))}
    @Test fun changedPayloadRejected(){val b=image.clone();b[500]=(b[500].toInt() xor 1).toByte();rejected(b)}
    @Test fun changedSignatureRejectedEvenWithFreshCrc(){val b=image.clone();b[4096+900]=(b[4096+900].toInt() xor 1).toByte();fixCrc(b);rejected(b)}
    @Test fun corruptBlockCrcRejected(){val b=image.clone();b[4096+1196]=(b[4096+1196].toInt() xor 1).toByte();rejected(b)}
    @Test fun extraSignatureBlocksRejected(){val b=image.clone();b[4096+1250]=0;rejected(b)}
    @Test fun tooLargeInputStopsAtOneBytePastLimit(){var read=0;val stream=object:InputStream(){override fun read():Int {read++;return 0};override fun read(b:ByteArray,o:Int,n:Int):Int{read+=n;b.fill(0,o,o+n);return n}};assertTrue(runCatching{LocalFirmwareFile.readBounded(stream)}.isFailure);assertEquals(LocalFirmwareFile.MAX_BYTES+1,read)}
    @Test fun zeroLengthReadCannotLoopForever(){val bytes=byteArrayOf(1,2,3);val delegate=ByteArrayInputStream(bytes);val stream=object:InputStream(){var first=true;override fun read()=delegate.read();override fun read(b:ByteArray,o:Int,n:Int):Int{if(first){first=false;return 0};return delegate.read(b,o,n)}};assertArrayEquals(bytes,LocalFirmwareFile.readBounded(stream))}
    @Test fun emptyFileRejected(){rejected(byteArrayOf())}
}
