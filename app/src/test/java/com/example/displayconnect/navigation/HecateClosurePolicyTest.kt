package com.example.displayconnect.navigation
import org.junit.Assert.*
import org.junit.Test
class HecateClosurePolicyTest {
 private fun checkClose(fw:String="0.28.26-175g-ota1-nav-LOCAL",ok:Any?=true,running:Any?=false,closing:Any?=false,clean:Any?=true,uncertain:Any?=false,has:Boolean=true)=HecateClosurePolicy.confirmed(fw,ok,running,closing,clean,uncertain,has)
 @Test fun cleanCloseAccepted(){assertTrue(checkClose())}
 @Test fun failedOrUncleanRejected(){assertFalse(checkClose(ok=false));assertFalse(checkClose(clean=false))}
 @Test fun activeOrUncertainRejected(){assertFalse(checkClose(running=true));assertFalse(checkClose(closing=true));assertFalse(checkClose(uncertain=true))}
 @Test fun knownVersionsCannotOmitClose(){for(v in listOf("0.28.24-175g-ota1-trial-LOCAL","0.28.25-175g-ota1-fix-LOCAL","0.28.26-175g-ota1-nav-LOCAL")){assertFalse(checkClose(fw=v,clean=null));assertFalse(checkClose(fw=v,closing=null));assertFalse(checkClose(fw=v,uncertain=null))}}
 @Test fun malformedFieldsRejected(){assertFalse(checkClose(clean="true"));assertFalse(checkClose(closing="false"));assertFalse(checkClose(uncertain="false"))}
 @Test fun legacyCloseRetained(){assertTrue(checkClose(fw="legacy",closing=null,clean=null,uncertain=null,has=false))}
 @Test fun partialNewContractCannotDowngrade(){assertFalse(checkClose(fw="legacy",clean=null))}
 @Test fun malformedLegacyStateRejected(){assertFalse(checkClose(fw="legacy",ok="true",has=false))}
}
