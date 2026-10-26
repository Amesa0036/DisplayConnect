package com.example.displayconnect.navigation
import org.junit.Assert.*
import org.junit.Test
class AltitudeEstimatorTest {
 private fun sample(e:AltitudeEstimator,msl:Double?=100.0,wgs:Double?=140.0,p:Float?=1000f,ready:Boolean=true,error:Float?=5f,t:Long=1000)=e.update(msl,wgs,error,p,ready,t,t,"Sin barometro; GPS aprox.")
 @Test fun noBarometerStillHasGpsMsl() { val r=sample(AltitudeEstimator(),ready=false);assertEquals(100.0,r.metres!!,0.01);assertEquals("msl",r.reference);assertEquals("gps",r.source) }
 @Test fun oldAndroidKeepsWgs84Explicit() { val r=sample(AltitudeEstimator(),msl=null,ready=false);assertEquals("wgs84",r.reference);assertEquals(140.0,r.metres!!,0.01) }
 @Test fun noFixNeverManufacturesAltitude() { assertNull(sample(AltitudeEstimator(),msl=null,wgs=null).metres) }
 @Test fun calibrationNeedsGoodGpsAndFiveFixes() { val e=AltitudeEstimator();repeat(4){ assertEquals("gps",sample(e,t=1000L+it*1000).source) };assertEquals("gps_baro",sample(e,t=5000).source);val changed=sample(e,p=999f,t=6000);assertTrue(changed.metres!!>100);assertEquals("gps",sample(e,ready=false,t=7000).source) }
 @Test fun poorUnknownOrDivergentSensorFallsBack() { val e=AltitudeEstimator();repeat(6){assertEquals("gps",sample(e,error=null,t=1000L+it*1000).source)};repeat(5){sample(e,t=10000L+it*1000)};assertEquals("gps",sample(e,p=990f,t=16000).source);assertEquals("gps",sample(e,p=Float.NaN,t=17000).source) }
 @Test fun staleAndRangeChecks() { val e=AltitudeEstimator();assertNull(e.update(100.0,null,5f,null,false,5000,1000,"").metres);assertNull(sample(e,msl=Double.NaN,wgs=null).metres) }
}
