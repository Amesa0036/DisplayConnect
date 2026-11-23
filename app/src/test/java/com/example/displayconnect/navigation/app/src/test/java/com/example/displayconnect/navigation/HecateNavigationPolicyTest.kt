package com.example.displayconnect.navigation
import org.junit.Assert.*
import org.junit.Test
class HecateNavigationPolicyTest {
 private fun supported(v:String,ok:Any?=true,supported:Any?=true,reader:Any?=true)=HecateNavigationPolicy.supported(v,ok,supported,reader)
 @Test fun diagnosticCannotStartRoute(){for(v in listOf("0.28.23-175g-ota1-trial-LOCAL","0.28.24-175g-ota1-trial-LOCAL","0.28.25-175g-ota1-fix-LOCAL"))assertFalse(supported(v))}
 @Test fun navCandidatePassesCapabilityNotApproval(){assertTrue(supported("0.28.26-175g-ota1-nav-LOCAL"))}
 @Test fun legacyNavCompatibilityRetained(){assertTrue(supported("0.28.18-175g-nav-life"))}
 @Test fun capabilitiesMustBeBoolean(){for(v in listOf(false,"true",null)){assertFalse(supported("nav",ok=v));assertFalse(supported("nav",supported=v));assertFalse(supported("nav",reader=v))}}
}
