#ifndef CGM_SERVICE_H
#define CGM_SERVICE_H

#include <stdint.h>

#include "cgm_codec.h"
#include "glucose_sim.h"

// The BLE peripheral: a NimBLE GATT server that serves the CGM Service (0x181F)
// exactly as docs/ble-protocol.md specifies — measurement notifications with the
// E2E-CRC, feature and status reads, session start time, the Record Access
// Control Point for on-demand history, and the CGM Specific Ops Control Point
// for the interval and session control.
//
// Frames are paced through a bounded outbox (one short burst per tick) rather
// than blasted at the stack, so a 24-hour RACP report can not drop packets.

namespace svc {

constexpr int MAX_HISTORY = 300;            // 25 hours at 5-minute cadence, like the apps
constexpr int MAX_OUTBOX_FRAMES = 128;      // paced; plenty for a full history report
constexpr int MAX_FRAME_BYTES = 244;        // ATT MTU 247 minus the 3-byte header
constexpr uint32_t FRAME_INTERVAL_MS = 30;  // pacing between notification bursts

class CgmService {
public:
    void begin(sim::Scenario scenario, float speed, uint64_t seed);
    void tick();  // advance the sim, publish due readings, drain the outbox

    // Serial CLI and ops control point entry points.
    void startSession();
    void stopSession();
    bool setCommunicationInterval(int minutes);
    void setScenario(sim::Scenario scenario);
    void setSpeed(float speed);

    bool sessionActive() const { return sessionActive_; }
    int communicationIntervalMinutes() const { return intervalMinutes_; }
    sim::Scenario scenario() const { return sim_.scenario(); }
    float speed() const { return speed_; }
    int subscribers() const { return subscribers_; }
    size_t historySize() const { return historyCount_; }

private:
    friend class ServerCallbacks;
    friend class CharacteristicCallbacks;

    void setPeerMtu(uint16_t mtu);
    void onSubscribed(bool measurement, bool subscribed);
    void onRacpWrite(const uint8_t* data, size_t len);
    void onOpsWrite(const uint8_t* data, size_t len);
    void onSessionStartWrite(const uint8_t* data, size_t len);
    uint64_t sessionStartEpochMillis() const;
    uint64_t simNowMillis() const;

    void pushHistoryRecord(const cgm::Record& rec);
    void statusSnapshot(uint8_t out[7]);
    bool enqueueFrame(const uint8_t* frame, size_t len, bool report);
    void indicateRacp(const uint8_t* data, size_t len);
    void indicateOps(const uint8_t* data, size_t len);
    void drainOutbox();
    bool racpSelectReport(uint8_t op, uint8_t opCodeForResponse, const uint8_t* operand, size_t operandLen,
                          uint16_t* fromInclusive, uint16_t* toInclusive);

    sim::GlucoseSim sim_;
    uint64_t sessionStartSimMillis_ = 0;  // sim timestamp of the current session start
    uint64_t bootSimMillis_ = 0;          // sim origin for the epoch conversion
    uint64_t wallStartMillis_ = 0;
    float speed_ = 1.0f;
    uint64_t seed_ = 42;
    bool bleStarted_ = false;
    bool sessionActive_ = true;
    int intervalMinutes_ = 5;
    int subscribers_ = 0;
    uint16_t attMtu_ = 23;
    uint32_t lastFrameAt_ = 0;
    uint32_t lastTickAt_ = 0;

    // Record history, oldest first (ring buffer backing store, linearized on read).
    cgm::Record history_[MAX_HISTORY];
    size_t historyHead_ = 0;   // index of the oldest record
    size_t historyCount_ = 0;

    // Outbox (FIFO). reportFrames_ tracks frames owned by an RACP report for Abort.
    uint8_t outbox_[MAX_OUTBOX_FRAMES][MAX_FRAME_BYTES];
    uint16_t outboxLen_[MAX_OUTBOX_FRAMES]{};
    bool outboxReport_[MAX_OUTBOX_FRAMES]{};
    size_t outboxHead_ = 0;
    size_t outboxCount_ = 0;
};

// The fake wall clock the sensor reports as its session start (no RTC on the
// board): the epoch base plus sim-uptime. Companion charts line up as long as
// readings flow; absolute labels sit near this base. ADR-0015.
constexpr uint64_t FAKE_EPOCH_BASE_MS = 1767225600000ULL;  // 2026-01-01T00:00:00Z

extern CgmService g_cgm;

}  // namespace svc

#endif  // CGM_SERVICE_H
