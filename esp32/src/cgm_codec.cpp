#include "cgm_codec.h"

#include <math.h>
#include <string.h>
#include <time.h>

namespace cgm {

uint16_t crc16(const uint8_t* data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= static_cast<uint16_t>(data[i]) << 8;
        for (int bit = 0; bit < 8; bit++) {
            crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x1021) : static_cast<uint16_t>(crc << 1);
        }
    }
    return crc;
}

uint16_t sfloatEncode(double value) {
    static const int kExponents[] = {0, -1, 1, -2, 2, -3, 3, 4, 5, 6, 7};
    int bestPacked = -1;
    double bestError = 1e300;
    for (size_t i = 0; i < sizeof(kExponents) / sizeof(kExponents[0]); i++) {
        const int exponent = kExponents[i];
        const double scale = pow(10.0, static_cast<double>(exponent));
        const long mantissa = lround(value / scale);
        if (mantissa < -2045 || mantissa > 2045) continue;
        const double error = fabs(mantissa * scale - value);
        if (bestPacked < 0 || error < bestError - 1e-9) {
            bestError = error;
            bestPacked = ((exponent & 0xF) << 12) | (mantissa & 0xFFF);
        }
    }
    return static_cast<uint16_t>(bestPacked < 0 ? 0 : bestPacked);
}

static uint8_t recordFlags(const Record& rec) {
    uint8_t flags = 0;
    if (rec.hasTrend) flags |= FLAG_TREND_PRESENT;
    if (rec.warning != 0) flags |= FLAG_WARNING_PRESENT;
    if (rec.calTemp != 0) flags |= FLAG_CALTEMP_PRESENT;
    if (rec.status != 0) flags |= FLAG_STATUS_PRESENT;
    return flags;
}

size_t recordSizeNoCrc(const Record& rec) {
    size_t size = RECORD_BASE_SIZE;
    if (rec.status != 0) size++;
    if (rec.calTemp != 0) size++;
    if (rec.warning != 0) size++;
    if (rec.hasTrend) size += 2;
    return size;
}

size_t encodeRecordBody(const Record& rec, bool withCrc, uint8_t* out, size_t cap) {
    const size_t size = recordSizeNoCrc(rec) + (withCrc ? 2u : 0u);
    const size_t written = size - (withCrc ? 2u : 0u);
    if (cap < written) return 0;
    out[0] = static_cast<uint8_t>(size);  // Size counts the CRC field too.
    out[1] = recordFlags(rec);
    const uint16_t glucose = sfloatEncode(rec.mgdl);
    out[2] = static_cast<uint8_t>(glucose & 0xFF);
    out[3] = static_cast<uint8_t>(glucose >> 8);
    out[4] = static_cast<uint8_t>(rec.timeOffsetMinutes & 0xFF);
    out[5] = static_cast<uint8_t>(rec.timeOffsetMinutes >> 8);
    size_t cursor = RECORD_BASE_SIZE;
    // Wire order is status, cal/temp, warning; zero octets are not sent.
    if (rec.status != 0) out[cursor++] = rec.status;
    if (rec.calTemp != 0) out[cursor++] = rec.calTemp;
    if (rec.warning != 0) out[cursor++] = rec.warning;
    if (rec.hasTrend) {
        const uint16_t trend = sfloatEncode(rec.trendMgdlPerMin);
        out[cursor++] = static_cast<uint8_t>(trend & 0xFF);
        out[cursor] = static_cast<uint8_t>(trend >> 8);
    }
    return written;
}

size_t encodeValue(const Record* recs, size_t count, bool withCrc, uint8_t* out, size_t cap) {
    // First pass: bodies only ("prefixes"); the CRC covers their concatenation.
    size_t bodyLen = 0;
    for (size_t i = 0; i < count; i++) {
        const size_t written = encodeRecordBody(recs[i], withCrc, out + bodyLen, cap - bodyLen);
        if (written == 0 && recordSizeNoCrc(recs[i]) > 0) return 0;
        bodyLen += written;
    }
    if (!withCrc) return bodyLen;
    const uint16_t crc = crc16(out, bodyLen);
    // Second pass: interleave the same CRC at the end of every record.
    size_t total = bodyLen + count * 2;
    if (total > cap) return 0;
    uint8_t scratch[RECORD_MAX_SIZE * 16];
    if (bodyLen > sizeof(scratch)) return 0;  // Frames are always small; see forEachFrame.
    memcpy(scratch, out, bodyLen);
    size_t read = 0, write = 0;
    for (size_t i = 0; i < count; i++) {
        const size_t body = recordSizeNoCrc(recs[i]);
        memcpy(out + write, scratch + read, body);
        read += body;
        write += body;
        out[write++] = static_cast<uint8_t>(crc & 0xFF);
        out[write++] = static_cast<uint8_t>(crc >> 8);
    }
    return write;
}

void encodeFeature(uint8_t out[4]) {
    // SENSOR_FEATURES = patient hi/lo(1) + hypo(2) + hyper(3) + rate(4) + E2E-CRC(12) + trend(15).
    out[0] = 0x1E;
    out[1] = 0x90;
    out[2] = 0x00;
    out[3] = 0x95;  // type 9 (interstitial fluid) << 4 | location 5 (subcutaneous)
}

void encodeSessionStart(uint64_t epochMillis, uint8_t out[9]) {
    const time_t seconds = static_cast<time_t>(epochMillis / 1000ULL);
    struct tm tmUtc;
    gmtime_r(&seconds, &tmUtc);
    const uint16_t year = static_cast<uint16_t>(tmUtc.tm_year + 1900);
    out[0] = static_cast<uint8_t>(year & 0xFF);
    out[1] = static_cast<uint8_t>(year >> 8);
    out[2] = static_cast<uint8_t>(tmUtc.tm_mon + 1);
    out[3] = static_cast<uint8_t>(tmUtc.tm_mday);
    out[4] = static_cast<uint8_t>(tmUtc.tm_hour);
    out[5] = static_cast<uint8_t>(tmUtc.tm_min);
    out[6] = static_cast<uint8_t>(tmUtc.tm_sec);
    out[7] = 0;  // UTC
    out[8] = 0;  // standard time
}

bool decodeSessionStart(const uint8_t* value, size_t len, uint64_t* epochMillisOut) {
    if (len < 7) return false;
    struct tm tmUtc;
    memset(&tmUtc, 0, sizeof(tmUtc));
    const int year = value[0] | (value[1] << 8);
    if (year < 1970 || value[2] < 1 || value[2] > 12 || value[3] < 1 || value[3] > 31) return false;
    tmUtc.tm_year = year - 1900;
    tmUtc.tm_mon = value[2] - 1;
    tmUtc.tm_mday = value[3];
    tmUtc.tm_hour = value[4];
    tmUtc.tm_min = value[5];
    tmUtc.tm_sec = value[6];
    // newlib has no timegm: pin the zone to UTC and use mktime (ADR-0015).
    setenv("TZ", "UTC0", 1);
    tzset();
    time_t seconds = mktime(&tmUtc);
    if (seconds < 0) return false;
    *epochMillisOut = static_cast<uint64_t>(seconds) * 1000ULL;
    return true;
}

void encodeStatus(uint16_t offsetMinutes, uint8_t status, uint8_t calTemp, uint8_t warning, uint8_t out[7]) {
    out[0] = static_cast<uint8_t>(offsetMinutes & 0xFF);
    out[1] = static_cast<uint8_t>(offsetMinutes >> 8);
    out[2] = status;
    out[3] = calTemp;
    out[4] = warning;
    const uint16_t crc = crc16(out, 5);
    out[5] = static_cast<uint8_t>(crc & 0xFF);
    out[6] = static_cast<uint8_t>(crc >> 8);
}

}  // namespace cgm
