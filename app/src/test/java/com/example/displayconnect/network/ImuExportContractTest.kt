package com.example.displayconnect.network
import org.junit.Assert.*
import org.junit.Test
class ImuExportContractTest {
    @Test fun legacyAndMultiMinuteAreDifferent() {
        assertTrue(ImuExportContract.exportable(2,128,128,false,"fifo_window_full"))
        assertFalse(ImuExportContract.exportable(2,127,128,false,"user"))
        assertTrue(ImuExportContract.exportable(3,11250,12000,false,"duration_limit"))
        assertTrue(ImuExportContract.exportable(3,25,12000,false,"user"))
        assertTrue(ImuExportContract.exportable(3,8,12000,false,"sensor_errors"))
        assertTrue(ImuExportContract.exportable(3,8,12000,false,"sensor_stale"))
    }
    @Test fun rejectUnknownLiveEmptyOrOversized() {
        assertFalse(ImuExportContract.exportable(3,20,12000,true,"user"))
        assertFalse(ImuExportContract.exportable(3,0,12000,false,"user"))
        assertFalse(ImuExportContract.exportable(3,12001,12000,false,"capacity_limit"))
        assertFalse(ImuExportContract.exportable(3,10,12001,false,"user"))
        assertFalse(ImuExportContract.exportable(4,20,12000,false,"user"))
        assertFalse(ImuExportContract.exportable(3,20,12000,false,"running"))
    }
    @Test fun doNotLabelHostTimeAsPhysicalSampleTime() {
        assertTrue(ImuExportContract.header(3).startsWith("host_read_ms,sensor_sample_counter_24bit"))
        assertTrue(ImuExportContract.header(2).contains("sample_ms_nominal"))
        assertFalse(ImuExportContract.header(3).contains("fifo_status"))
    }
}
