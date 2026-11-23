package com.example.displayconnect.network

import org.junit.Assert.*
import org.junit.Test

/** Policy tests only. Signature authenticity is enforced by LocalFirmwareFile before this gate. */
class LocalFirmwareTrialPolicyTest {
    private val closeSha = "bc2abb3a30142fc0e22a156b50aef18d4fa5b46aca8d365b2584ce9a28bde88a"
    private fun close() = LocalFirmwareImage(LocalFirmwareTrialPolicy.CLOSE_VERSION,659456,closeSha,byteArrayOf())
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun closeCaseRemainsSeparate() {
        val t=LocalFirmwareTrialPolicy.requireCandidate(close())
        assertEquals("close_checkpoint",t.purpose)
        assertEquals(LocalFirmwareTrialPolicy.CLOSE_VERSION,t.target)
        assertEquals(t.target,t.requiredBefore)
    }
    @Test fun closeHashCannotChange() { rejected { LocalFirmwareTrialPolicy.requireCandidate(LocalFirmwareImage(close().version,659456,"0".repeat(64),byteArrayOf())) } }
    @Test fun unlistedVersionsRejected() { rejected { LocalFirmwareTrialPolicy.requireCandidate(LocalFirmwareImage("other",659456,closeSha,byteArrayOf())) } }
    @Test fun closeRequiresInstalledCloseVersion() {
        val t=LocalFirmwareTrialPolicy.requireCandidate(close())
        LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(t.requiredBefore,"ota_0"))
        rejected { LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.POSITION_VERSION,"ota_1")) }
    }
    @Test fun unsignedPositionRejected() { rejected { LocalFirmwareTrialPolicy.requireCandidate(LocalFirmwareImage(LocalFirmwareTrialPolicy.POSITION_VERSION,655360,"0".repeat(64),ByteArray(655360))) } }
    @Test fun positionPayloadCannotChange() { rejected { LocalFirmwareTrialPolicy.requireCandidate(LocalFirmwareImage(LocalFirmwareTrialPolicy.POSITION_VERSION,659456,"0".repeat(64),ByteArray(659456))) } }
    @Test fun staleSignedHashRejectedEvenWithReviewedVersion() { rejected { LocalFirmwareTrialPolicy.requireCandidate(LocalFirmwareImage(LocalFirmwareTrialPolicy.POSITION_VERSION,659456,closeSha,ByteArray(659456))) } }
    @Test fun transitionStartsOnlyFromCloseVersion() {
        val t=LocalFirmwareTrialPolicy.Trial("stationary_position_once",LocalFirmwareTrialPolicy.POSITION_VERSION,LocalFirmwareTrialPolicy.CLOSE_VERSION)
        LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.CLOSE_VERSION,"ota_0"))
        rejected { LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(t.target,"ota_0")) }
        rejected { LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity("old","ota_1")) }
    }
    @Test fun navOnlyStartsFromPosition() {
        val t=LocalFirmwareTrialPolicy.Trial("mobile_navigation_once",LocalFirmwareTrialPolicy.NAV_VERSION,LocalFirmwareTrialPolicy.POSITION_VERSION)
        LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.POSITION_VERSION,"ota_1"))
        rejected { LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.CLOSE_VERSION,"ota_0")) }
        rejected { LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.NAV_VERSION,"ota_0")) }
    }
    @Test fun positionRecoveryIsOnlyFromNavOrOriginalTrial() {
        val t=LocalFirmwareTrialPolicy.Trial("stationary_position_once",LocalFirmwareTrialPolicy.POSITION_VERSION,LocalFirmwareTrialPolicy.CLOSE_VERSION,LocalFirmwareTrialPolicy.NAV_VERSION)
        LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.NAV_VERSION,"ota_0"))
        LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.CLOSE_VERSION,"ota_0"))
        rejected { LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity(LocalFirmwareTrialPolicy.POSITION_VERSION,"ota_1")) }
        rejected { LocalFirmwareTrialPolicy.requireBefore(t,OtaBootIdentity("other","ota_1")) }
    }
    @Test fun unsignedNavigationCannotPass() { rejected { LocalFirmwareTrialPolicy.requireCandidate(LocalFirmwareImage(LocalFirmwareTrialPolicy.NAV_VERSION,655360,"0".repeat(64),ByteArray(655360))) } }
    @Test fun navPayloadCannotChange() { rejected { LocalFirmwareTrialPolicy.requireCandidate(LocalFirmwareImage(LocalFirmwareTrialPolicy.NAV_VERSION,659456,"0".repeat(64),ByteArray(659456))) } }

}
