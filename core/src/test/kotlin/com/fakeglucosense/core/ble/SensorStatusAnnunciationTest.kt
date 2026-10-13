package com.fakeglucosense.core.ble

import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.Trend
import kotlin.test.Test
import kotlin.test.assertEquals

class SensorStatusAnnunciationTest {

    private val thresholds = AlertThresholds() // 55 / 70 / 180 / 250

    @Test
    fun `an in-range flat reading announces nothing`() {
        assertEquals(0, SensorStatusAnnunciation.warningFor(120.0, Trend.FLAT, thresholds))
    }

    @Test
    fun `maps threshold crossings to patient, hypo, and hyper bits`() {
        assertEquals(
            SensorStatusAnnunciation.WARNING_PATIENT_LOW,
            SensorStatusAnnunciation.warningFor(65.0, Trend.FLAT, thresholds),
        )
        assertEquals(
            SensorStatusAnnunciation.WARNING_HYPO or SensorStatusAnnunciation.WARNING_PATIENT_LOW,
            SensorStatusAnnunciation.warningFor(50.0, Trend.FLAT, thresholds),
        )
        assertEquals(
            SensorStatusAnnunciation.WARNING_HYPER or SensorStatusAnnunciation.WARNING_PATIENT_HIGH,
            SensorStatusAnnunciation.warningFor(260.0, Trend.FLAT, thresholds),
        )
    }

    @Test
    fun `maps double arrows to the rate-exceeded bits`() {
        assertEquals(
            SensorStatusAnnunciation.WARNING_RATE_OF_DECREASE,
            SensorStatusAnnunciation.warningFor(120.0, Trend.DOUBLE_DOWN, thresholds),
        )
        assertEquals(
            SensorStatusAnnunciation.WARNING_RATE_OF_INCREASE,
            SensorStatusAnnunciation.warningFor(120.0, Trend.DOUBLE_UP, thresholds),
        )
    }

    @Test
    fun `marks saturation at the device range ends`() {
        assertEquals(
            SensorStatusAnnunciation.WARNING_RESULT_BELOW_DEVICE_RANGE or
                SensorStatusAnnunciation.WARNING_PATIENT_LOW or SensorStatusAnnunciation.WARNING_HYPO,
            SensorStatusAnnunciation.warningFor(CgmMeasurementCodec.BELOW_RANGE_MGDL, Trend.FLAT, thresholds),
        )
        assertEquals(
            SensorStatusAnnunciation.WARNING_RESULT_ABOVE_DEVICE_RANGE or
                SensorStatusAnnunciation.WARNING_PATIENT_HIGH or SensorStatusAnnunciation.WARNING_HYPER,
            SensorStatusAnnunciation.warningFor(CgmMeasurementCodec.ABOVE_RANGE_MGDL, Trend.FLAT, thresholds),
        )
    }
}
