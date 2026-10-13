#ifndef CGM_CODEC_H
#define CGM_CODEC_H

#include <stddef.h>
#include <stdint.h>

// Byte-level codec for the CGM Service profile shared with the Kotlin core module
// (core/ble/*; see docs/ble-protocol.md). Keep behavior identical: the companion
// app must not be able to tell whether it is talking to a phone or to an ESP32.

namespace cgm {

// One measurement record, ready to encode.
struct Record {
    uint16_t timeOffsetMinutes;  // minutes since the session start (UINT16)
    double   mgdl;               // glucose concentration
    double   trendMgdlPerMin;    // rate of change; ignored when hasTrend is false
    bool     hasTrend;
    uint8_t  warning;            // Warning annunciation octet (0 when unremarkable)
    uint8_t  calTemp;            // Cal/Temp octet (always 0 for the simulator)
    uint8_t  status;             // Status octet (0 while the session runs)
};

// Flags of the CGM Measurement record.
constexpr uint8_t FLAG_TREND_PRESENT    = 0x01;
constexpr uint8_t FLAG_WARNING_PRESENT  = 0x20;
constexpr uint8_t FLAG_CALTEMP_PRESENT  = 0x40;
constexpr uint8_t FLAG_STATUS_PRESENT   = 0x80;

constexpr size_t RECORD_BASE_SIZE = 6;
constexpr size_t RECORD_MAX_SIZE  = 13;  // base + 3 annunciation octets + trend + CRC

// Sensor Status Annunciation bits (CGMS v1.0.2), as in core/ble/SensorStatusAnnunciation.kt.
namespace ssa {
constexpr uint8_t WARNING_PATIENT_LOW         = 0x01;
constexpr uint8_t WARNING_PATIENT_HIGH        = 0x02;
constexpr uint8_t WARNING_HYPO                = 0x04;
constexpr uint8_t WARNING_HYPER               = 0x08;
constexpr uint8_t WARNING_RATE_OF_DECREASE    = 0x10;
constexpr uint8_t WARNING_RATE_OF_INCREASE    = 0x20;
constexpr uint8_t STATUS_SESSION_STOPPED      = 0x01;
}  // namespace ssa

// CRC-16-CCITT per the E2E-CRC definition: polynomial 0x1021, init 0xFFFF,
// no reflection, no final XOR. Check value for "123456789" is 0x29B1.
uint16_t crc16(const uint8_t* data, size_t len);

// IEEE-11073 16-bit SFLOAT (12-bit signed mantissa, 4-bit signed exponent).
// Exponent candidates are tried nearest zero first, matching SFloat.kt.
uint16_t sfloatEncode(double value);

// Length of one encoded record, CRC excluded.
size_t recordSizeNoCrc(const Record& rec);

// Encodes one record body (no CRC field) into out. Returns bytes written.
size_t encodeRecordBody(const Record& rec, bool withCrc, uint8_t* out, size_t cap);

// Encodes a whole characteristic value: every record, and when withCrc is set the
// whole-value CRC stamped at the end of each record (same rule as
// CgmMeasurementCodec.encodeValue). Returns bytes written, 0 on overflow.
size_t encodeValue(const Record* recs, size_t count, bool withCrc, uint8_t* out, size_t cap);

// Encodes the CGM Feature value (0x00901E, type ISF, location subcutaneous).
void encodeFeature(uint8_t out[4]);

// Encodes the CGM Session Start Time value (9 bytes, always UTC + no DST).
// epochMillis uses the fake epoch base documented in main.cpp.
void encodeSessionStart(uint64_t epochMillis, uint8_t out[9]);

// Parses a written Session Start Time (7-byte date time, plus optional zone/DST, UTC assumed
// here). Returns false on a malformed value. Lets a collector time-sync the clockless board.
bool decodeSessionStart(const uint8_t* value, size_t len, uint64_t* epochMillisOut);

// Encodes the CGM Status value: offset, status, calTemp, warning, then CRC of those 5 bytes.
void encodeStatus(uint16_t offsetMinutes, uint8_t status, uint8_t calTemp, uint8_t warning, uint8_t out[7]);

}  // namespace cgm

#endif  // CGM_CODEC_H
