#include "cgm_service.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <string.h>

namespace svc {

CgmService g_cgm;

// UUID strings in the SIG 128-bit form (0x181F service, per CgmUuids.kt).
static const char* kServiceUuid = "0000181f-0000-1000-8000-00805f9b34fb";
static const char* kMeasurementUuid = "00002aa7-0000-1000-8000-00805f9b34fb";
static const char* kFeatureUuid = "00002aa8-0000-1000-8000-00805f9b34fb";
static const char* kStatusUuid = "00002aa9-0000-1000-8000-00805f9b34fb";
static const char* kSessionStartUuid = "00002aaa-0000-1000-8000-00805f9b34fb";
static const char* kRacpUuid = "00002a52-0000-1000-8000-00805f9b34fb";
static const char* kOpsUuid = "00002aac-0000-1000-8000-00805f9b34fb";

static NimBLECharacteristic* g_measurement = nullptr;
static NimBLECharacteristic* g_statusChar = nullptr;
static NimBLECharacteristic* g_sessionStartChar = nullptr;
static NimBLECharacteristic* g_racp = nullptr;
static NimBLECharacteristic* g_ops = nullptr;

// RACP constants (mirror of core/ble/Racp.kt).
namespace racp {
constexpr uint8_t OP_REPORT_STORED_RECORDS = 0x01;
constexpr uint8_t OP_DELETE_STORED_RECORDS = 0x02;
constexpr uint8_t OP_ABORT_OPERATION = 0x03;
constexpr uint8_t OP_REPORT_NUMBER_OF_RECORDS = 0x04;
constexpr uint8_t OP_NUMBER_OF_RECORDS_RESPONSE = 0x05;
constexpr uint8_t OP_RESPONSE_CODE = 0x06;
constexpr uint8_t OPERATOR_NULL = 0x00;
constexpr uint8_t OPERATOR_ALL = 0x01;
constexpr uint8_t OPERATOR_LESS_OR_EQUAL = 0x02;
constexpr uint8_t OPERATOR_GREATER_OR_EQUAL = 0x03;
constexpr uint8_t OPERATOR_WITHIN_RANGE = 0x04;
constexpr uint8_t OPERATOR_FIRST = 0x05;
constexpr uint8_t OPERATOR_LAST = 0x06;
constexpr uint8_t RESPONSE_SUCCESS = 0x01;
constexpr uint8_t RESPONSE_OP_NOT_SUPPORTED = 0x02;
constexpr uint8_t RESPONSE_INVALID_OPERATOR = 0x03;
constexpr uint8_t RESPONSE_OPERATOR_NOT_SUPPORTED = 0x04;
constexpr uint8_t RESPONSE_INVALID_OPERAND = 0x05;
constexpr uint8_t RESPONSE_NO_RECORDS_FOUND = 0x06;
constexpr uint8_t RESPONSE_ABORT_UNSUCCESSFUL = 0x07;
constexpr uint8_t RESPONSE_PROCEDURE_NOT_COMPLETED = 0x08;
constexpr uint8_t RESPONSE_OPERAND_NOT_SUPPORTED = 0x09;
constexpr uint8_t FILTER_TIME_OFFSET = 0x01;
}  // namespace racp

// Ops CP constants (mirror of core/ble/CgmOpsCodec.kt).
namespace ops {
constexpr uint8_t OP_SET_INTERVAL = 0x01;
constexpr uint8_t OP_GET_INTERVAL = 0x02;
constexpr uint8_t OP_INTERVAL_RESPONSE = 0x03;
constexpr uint8_t OP_START_SESSION = 0x1A;
constexpr uint8_t OP_STOP_SESSION = 0x1B;
constexpr uint8_t OP_RESPONSE_CODE = 0x1C;
constexpr uint8_t RESPONSE_SUCCESS = 0x01;
constexpr uint8_t RESPONSE_OP_NOT_SUPPORTED = 0x02;
constexpr uint8_t RESPONSE_OUT_OF_RANGE = 0x05;
}  // namespace ops

// --- Callbacks, delegating to the singleton service -------------------------------------------

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
        Serial.println(F("[ble] central connected"));
    }

    void onDisconnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
        Serial.println(F("[ble] central disconnected; advertising"));
        NimBLEDevice::startAdvertising();
    }

    void onMTUChange(uint16_t mtu, ble_gap_conn_desc* desc) override {
        g_cgm.setPeerMtu(mtu);
        Serial.printf("[ble] MTU now %u\n", mtu);
    }
};

class CharacteristicCallbacks : public NimBLECharacteristicCallbacks {
    void onSubscribe(NimBLECharacteristic* pCharacteristic, ble_gap_conn_desc* desc, uint16_t subValue) override {
        const bool measurement = pCharacteristic->getUUID().equals(NimBLEUUID(kMeasurementUuid));
        g_cgm.onSubscribed(measurement, subValue != 0);
    }

    void onWrite(NimBLECharacteristic* pCharacteristic, ble_gap_conn_desc* desc) override {
        const NimBLEUUID uuid = pCharacteristic->getUUID();
        const NimBLEAttValue& value = pCharacteristic->getValue();
        if (uuid.equals(NimBLEUUID(kRacpUuid))) {
            g_cgm.onRacpWrite(value.data(), value.size());
        } else if (uuid.equals(NimBLEUUID(kOpsUuid))) {
            g_cgm.onOpsWrite(value.data(), value.size());
        } else if (uuid.equals(NimBLEUUID(kSessionStartUuid))) {
            g_cgm.onSessionStartWrite(value.data(), value.size());
        }
    }

    void onRead(NimBLECharacteristic* pCharacteristic, ble_gap_conn_desc* desc) override {
        // Status and Session Start Time are refreshed lazily on read.
        const NimBLEUUID uuid = pCharacteristic->getUUID();
        if (uuid.equals(NimBLEUUID(kSessionStartUuid))) {
            uint8_t bytes[9];
            cgm::encodeSessionStart(g_cgm.sessionStartEpochMillis(), bytes);
            g_sessionStartChar->setValue(bytes, sizeof(bytes));
        } else if (uuid.equals(NimBLEUUID(kStatusUuid))) {
            uint8_t bytes[7];
            g_cgm.statusSnapshot(bytes);
            g_statusChar->setValue(bytes, sizeof(bytes));
        }
    }
};

// --- Service implementation --------------------------------------------------------------------

uint64_t CgmService::simNowMillis() const {
    const uint64_t elapsed = millis() - wallStartMillis_;
    return bootSimMillis_ + static_cast<uint64_t>(elapsed * static_cast<double>(speed_));
}

uint64_t CgmService::sessionStartEpochMillis() const {
    // Fake epoch: the base plus how far the sim has advanced overall (adr-0015).
    return FAKE_EPOCH_BASE_MS + (sessionStartSimMillis_ - bootSimMillis_);
}

void CgmService::begin(sim::Scenario scenario, float speed, uint64_t seed) {
    seed_ = seed ? seed : 42;
    speed_ = speed;
    wallStartMillis_ = millis();
    bootSimMillis_ = 0;
    sessionStartSimMillis_ = 0;
    sim_.begin(scenario, seed_, 0, 5);
    historyCount_ = 0;
    historyHead_ = 0;
    sessionActive_ = true;
    intervalMinutes_ = 5;

    if (bleStarted_) return;
    bleStarted_ = true;

    NimBLEDevice::init("FakeCGM");
    NimBLEDevice::setMTU(247);
    NimBLEServer* server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    NimBLEService* service = server->createService(kServiceUuid);
    g_measurement = service->createCharacteristic(kMeasurementUuid, NIMBLE_PROPERTY::NOTIFY);
    NimBLECharacteristic* feature = service->createCharacteristic(kFeatureUuid, NIMBLE_PROPERTY::READ);
    g_statusChar = service->createCharacteristic(kStatusUuid, NIMBLE_PROPERTY::READ);
    g_sessionStartChar = service->createCharacteristic(kSessionStartUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
    g_racp = service->createCharacteristic(kRacpUuid, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::INDICATE);
    g_ops = service->createCharacteristic(kOpsUuid, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::INDICATE);

    uint8_t featureBytes[4];
    cgm::encodeFeature(featureBytes);
    feature->setValue(featureBytes, sizeof(featureBytes));

    CharacteristicCallbacks* callbacks = new CharacteristicCallbacks();
    g_measurement->setCallbacks(callbacks);
    g_statusChar->setCallbacks(callbacks);
    g_sessionStartChar->setCallbacks(callbacks);
    g_racp->setCallbacks(callbacks);
    g_ops->setCallbacks(callbacks);

    service->start();
    NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
    advertising->addServiceUUID(kServiceUuid);
    advertising->setScanResponse(true);
    advertising->start();
    Serial.println(F("[ble] advertising CGM service 0x181F as 'FakeCGM'"));
}

void CgmService::tick() {
    const uint32_t now = millis();
    if (now - lastTickAt_ < 250) {
        drainOutbox();
        return;
    }
    lastTickAt_ = now;

    if (sessionActive_) {
        cgm::Record due[8];
        const size_t fresh = sim_.advanceTo(simNowMillis(), due, 8);
        for (size_t i = 0; i < fresh; i++) {
            pushHistoryRecord(due[i]);
            const int interval = intervalMinutes_ < 5 ? 5 : intervalMinutes_;
            if (due[i].timeOffsetMinutes % interval == 0 && subscribers_ > 0) {
                // Live notifications are single-record frames at default MTU, batched higher.
                cgm::Record one = due[i];
                uint8_t frame[MAX_FRAME_BYTES];
                const size_t len = cgm::encodeValue(&one, 1, true, frame, sizeof(frame));
                if (len > 0) enqueueFrame(frame, len, false);
            }
        }
        if (fresh > 0) {
            Serial.printf("[sim] offset %4u min  %3d mg/dL\n", due[fresh - 1].timeOffsetMinutes,
                          static_cast<int>(due[fresh - 1].mgdl));
        }
    }
    drainOutbox();
}

void CgmService::statusSnapshot(uint8_t out[7]) {
    const uint64_t simNow = simNowMillis();
    uint64_t minutes = simNow > sessionStartSimMillis_ ? (simNow - sessionStartSimMillis_) / 60000ULL : 0;
    if (minutes > 0xFFFFULL) minutes = 0xFFFF;
    uint8_t warning = 0;
    if (historyCount_ > 0) {
        warning = history_[(historyHead_ + historyCount_ - 1) % MAX_HISTORY].warning;
    }
    const uint8_t status = sessionActive_ ? 0 : cgm::ssa::STATUS_SESSION_STOPPED;
    cgm::encodeStatus(static_cast<uint16_t>(minutes), status, 0, warning, out);
}

void CgmService::pushHistoryRecord(const cgm::Record& rec) {
    if (historyCount_ < MAX_HISTORY) {
        history_[(historyHead_ + historyCount_) % MAX_HISTORY] = rec;
        historyCount_++;
    } else {
        history_[historyHead_] = rec;
        historyHead_ = (historyHead_ + 1) % MAX_HISTORY;
    }
}

bool CgmService::enqueueFrame(const uint8_t* frame, size_t len, bool report) {
    if (outboxCount_ == MAX_OUTBOX_FRAMES) return false;  // slow central; drop the oldest
    const size_t slot = (outboxHead_ + outboxCount_) % MAX_OUTBOX_FRAMES;
    memcpy(outbox_[slot], frame, len);
    outboxLen_[slot] = static_cast<uint16_t>(len);
    outboxReport_[slot] = report;
    outboxCount_++;
    return true;
}

void CgmService::drainOutbox() {
    const uint32_t now = millis();
    if (outboxCount_ == 0 || now - lastFrameAt_ < FRAME_INTERVAL_MS) return;
    lastFrameAt_ = now;
    // One burst per pace window: up to 4 frames, so a big report moves briskly.
    int burst = 4;
    while (outboxCount_ > 0 && burst-- > 0) {
        g_measurement->notify(outbox_[outboxHead_], outboxLen_[outboxHead_], true);
        outboxHead_ = (outboxHead_ + 1) % MAX_OUTBOX_FRAMES;
        outboxCount_--;
    }
}

void CgmService::indicateRacp(const uint8_t* data, size_t len) {
    g_racp->notify(data, len, false);  // is_notification=false -> indication
}

void CgmService::indicateOps(const uint8_t* data, size_t len) {
    g_ops->notify(data, len, false);
}

void CgmService::setPeerMtu(uint16_t mtu) {
    attMtu_ = mtu;
}

void CgmService::onSubscribed(bool measurement, bool subscribed) {
    if (!measurement) return;
    subscribers_ += subscribed ? 1 : -1;
    if (subscribers_ < 0) subscribers_ = 0;
    Serial.printf("[ble] measurement subscribers: %d\n", subscribers_);
}

// --- Record Access Control Point -----------------------------------------------------------------

// Parses the operand bounds for the bounded operators.
bool CgmService::racpSelectReport(uint8_t op, uint8_t opCodeForResponse, const uint8_t* operand,
                                  size_t operandLen, uint16_t* fromInclusive, uint16_t* toInclusive) {
    // Returns false when the request was already answered with an error indication.
    uint8_t error = 0;
    *fromInclusive = 0;
    *toInclusive = 0xFFFF;
    switch (op) {
        case racp::OPERATOR_ALL:
            return true;
        case racp::OPERATOR_FIRST:
        case racp::OPERATOR_LAST:
            *toInclusive = 0;  // handled by caller via from/to markers below
            return true;
        case racp::OPERATOR_GREATER_OR_EQUAL:
        case racp::OPERATOR_LESS_OR_EQUAL:
        case racp::OPERATOR_WITHIN_RANGE: {
            if (operandLen < 3) { error = racp::RESPONSE_INVALID_OPERAND; break; }
            if (operand[0] != racp::FILTER_TIME_OFFSET) { error = racp::RESPONSE_OPERAND_NOT_SUPPORTED; break; }
            const uint16_t first = operand[1] | (operand[2] << 8);
            if (op == racp::OPERATOR_LESS_OR_EQUAL) {
                *toInclusive = first;
            } else {
                *fromInclusive = first;
            }
            if (op == racp::OPERATOR_WITHIN_RANGE) {
                if (operandLen < 5) { error = racp::RESPONSE_INVALID_OPERAND; break; }
                *toInclusive = operand[3] | (operand[4] << 8);
                if (*fromInclusive > *toInclusive) { error = racp::RESPONSE_INVALID_OPERAND; break; }
            }
            return true;
        }
        case racp::OPERATOR_NULL:
            error = racp::RESPONSE_INVALID_OPERATOR;
            break;
        default:
            error = racp::RESPONSE_OPERATOR_NOT_SUPPORTED;
            break;
    }
    if (error != 0) {
        const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCodeForResponse, error};
        indicateRacp(rsp, sizeof(rsp));
    }
    return false;
}

void CgmService::onRacpWrite(const uint8_t* data, size_t len) {
    if (len < 2) return;
    const uint8_t opCode = data[0];
    const uint8_t op = data[1];
    const uint8_t* operand = len > 2 ? data + 2 : data;
    const size_t operandLen = len > 2 ? len - 2 : 0;

    if (opCode == racp::OP_DELETE_STORED_RECORDS) {
        // The simulated sensor's memory is append-only, like Nordic's CGMS.
        const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCode, racp::RESPONSE_OP_NOT_SUPPORTED};
        indicateRacp(rsp, sizeof(rsp));
        return;
    }
    if (opCode == racp::OP_ABORT_OPERATION) {
        // Abort an in-flight report by dropping queued report frames.
        bool removed = false;
        const size_t kept = outboxHead_;
        size_t count = 0;
        for (size_t i = 0; i < outboxCount_; i++) {
            const size_t idx = (outboxHead_ + i) % MAX_OUTBOX_FRAMES;
            if (outboxReport_[idx]) {
                removed = true;
            } else {
                if (count != i) {
                    const size_t dst = (kept + count) % MAX_OUTBOX_FRAMES;
                    memcpy(outbox_[dst], outbox_[idx], outboxLen_[idx]);
                    outboxLen_[dst] = outboxLen_[idx];
                    outboxReport_[dst] = false;
                }
                count++;
            }
        }
        outboxCount_ = count;
        if (op != racp::OPERATOR_NULL) {
            const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCode, racp::RESPONSE_INVALID_OPERATOR};
            indicateRacp(rsp, sizeof(rsp));
            return;
        }
        const uint8_t outcome = removed ? racp::RESPONSE_SUCCESS : racp::RESPONSE_ABORT_UNSUCCESSFUL;
        const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCode, outcome};
        indicateRacp(rsp, sizeof(rsp));
        return;
    }
    if (opCode != racp::OP_REPORT_STORED_RECORDS && opCode != racp::OP_REPORT_NUMBER_OF_RECORDS) {
        const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCode, racp::RESPONSE_OP_NOT_SUPPORTED};
        indicateRacp(rsp, sizeof(rsp));
        return;
    }

    uint16_t fromInclusive, toInclusive;
    if (!racpSelectReport(op, opCode, operand, operandLen, &fromInclusive, &toInclusive)) return;

    // Collect the selection, oldest first.
    cgm::Record selected[MAX_HISTORY];
    size_t selectedCount = 0;
    for (size_t i = 0; i < historyCount_ && selectedCount < MAX_HISTORY; i++) {
        const cgm::Record& rec = history_[(historyHead_ + i) % MAX_HISTORY];
        bool take = false;
        switch (op) {
            case racp::OPERATOR_ALL: take = true; break;
            case racp::OPERATOR_FIRST: take = (i == 0); break;
            case racp::OPERATOR_LAST: take = (i == historyCount_ - 1); break;
            default:
                take = rec.timeOffsetMinutes >= fromInclusive && rec.timeOffsetMinutes <= toInclusive;
                break;
        }
        if (take) selected[selectedCount++] = rec;
    }

    if (opCode == racp::OP_REPORT_NUMBER_OF_RECORDS) {
        const uint8_t rsp[4] = {
            racp::OP_NUMBER_OF_RECORDS_RESPONSE,
            racp::OPERATOR_NULL,
            static_cast<uint8_t>(selectedCount & 0xFF),
            static_cast<uint8_t>((selectedCount >> 8) & 0xFF),
        };
        indicateRacp(rsp, sizeof(rsp));
        return;
    }
    if (selectedCount == 0) {
        const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCode, racp::RESPONSE_NO_RECORDS_FOUND};
        indicateRacp(rsp, sizeof(rsp));
        return;
    }

    // Batch whole records into the negotiated payload (max a frame can carry).
    const size_t payload = attMtu_ > 3 ? attMtu_ - 3 : 20;
    const size_t maxPerFrame = payload < 13 ? 1 : payload / 10;  // 10 bytes per typical record
    const size_t perFrame = maxPerFrame > 12 ? 12 : (maxPerFrame < 1 ? 1 : maxPerFrame);

    for (size_t first = 0; first < selectedCount; first += perFrame) {
        const size_t n = (selectedCount - first < perFrame) ? selectedCount - first : perFrame;
        cgm::Record group[12];
        memcpy(group, selected + first, n * sizeof(cgm::Record));
        uint8_t frame[MAX_FRAME_BYTES];
        const size_t len = cgm::encodeValue(group, n, true, frame, sizeof(frame));
        if (len == 0 || !enqueueFrame(frame, len, true)) {
            const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCode,
                                    racp::RESPONSE_PROCEDURE_NOT_COMPLETED};
            indicateRacp(rsp, sizeof(rsp));
            return;
        }
    }
    const uint8_t rsp[4] = {racp::OP_RESPONSE_CODE, racp::OPERATOR_NULL, opCode, racp::RESPONSE_SUCCESS};
    indicateRacp(rsp, sizeof(rsp));
    Serial.printf("[racp] reported %u stored record(s)\n", static_cast<unsigned>(selectedCount));
}

// --- CGM Specific Ops Control Point ---------------------------------------------------------------

void CgmService::onOpsWrite(const uint8_t* data, size_t len) {
    if (len < 1) return;
    const uint8_t opCode = data[0];
    switch (opCode) {
        case ops::OP_GET_INTERVAL: {
            const uint8_t rsp[2] = {ops::OP_INTERVAL_RESPONSE, static_cast<uint8_t>(intervalMinutes_)};
            indicateOps(rsp, sizeof(rsp));
            break;
        }
        case ops::OP_SET_INTERVAL: {
            if (len < 2) return;
            const bool ok = setCommunicationInterval(data[1]);
            const uint8_t rsp[3] = {ops::OP_RESPONSE_CODE, opCode,
                                    static_cast<uint8_t>(ok ? ops::RESPONSE_SUCCESS : ops::RESPONSE_OUT_OF_RANGE)};
            indicateOps(rsp, sizeof(rsp));
            break;
        }
        case ops::OP_START_SESSION: {
            startSession();
            const uint8_t rsp[3] = {ops::OP_RESPONSE_CODE, opCode, ops::RESPONSE_SUCCESS};
            indicateOps(rsp, sizeof(rsp));
            break;
        }
        case ops::OP_STOP_SESSION: {
            stopSession();
            const uint8_t rsp[3] = {ops::OP_RESPONSE_CODE, opCode, ops::RESPONSE_SUCCESS};
            indicateOps(rsp, sizeof(rsp));
            break;
        }
        default: {
            const uint8_t rsp[3] = {ops::OP_RESPONSE_CODE, opCode, ops::RESPONSE_OP_NOT_SUPPORTED};
            indicateOps(rsp, sizeof(rsp));
            break;
        }
    }
}

void CgmService::onSessionStartWrite(const uint8_t* data, size_t len) {
    // Collector time sync (CGMS Session Start Time write): re-anchor the fake epoch so the
    // reported wall clock matches the collector. History keeps its offsets; only the epoch
    // base moves, matching the spec's "set the session start time" semantics.
    uint64_t epoch = 0;
    if (!cgm::decodeSessionStart(data, len, &epoch)) return;
    // The served epoch mapping (sessionStartEpochMillis) is FAKE_BASE + (sessionStartSim - bootSim).
    // Re-anchor so that mapping reads as the written epoch now. Offsets already in history keep
    // their positions on the new time line (they are deltas, not absolutes).
    const uint64_t simNow = simNowMillis();
    if (epoch < FAKE_EPOCH_BASE_MS) return;  // before the fake epoch base makes no sense here
    sessionStartSimMillis_ = simNow;
    bootSimMillis_ = simNow - (epoch - FAKE_EPOCH_BASE_MS);
    Serial.println(F("[ble] session start written by collector (time sync)"));
}

bool CgmService::setCommunicationInterval(int minutes) {
    if (minutes < 1 || minutes > 255) return false;
    intervalMinutes_ = minutes;
    Serial.printf("[ops] communication interval now %d min\n", minutes);
    return true;
}

void CgmService::startSession() {
    if (sessionActive_) return;  // running already is a successful no-op
    wallStartMillis_ = millis();
    bootSimMillis_ = simNowMillis();
    sessionStartSimMillis_ = bootSimMillis_;
    sim_.begin(sim_.scenario(), seed_, sessionStartSimMillis_, 5);
    historyCount_ = 0;
    historyHead_ = 0;
    sessionActive_ = true;
    Serial.println(F("[ops] session started"));
}

void CgmService::stopSession() {
    if (!sessionActive_) return;
    sessionActive_ = false;
    Serial.println(F("[ops] session stopped"));
}

void CgmService::setScenario(sim::Scenario scenario) {
    sim_.begin(scenario, seed_, sessionStartSimMillis_, 5);
    historyCount_ = 0;
    historyHead_ = 0;
}

void CgmService::setSpeed(float speed) {
    if (speed < 0.1f) speed = 0.1f;
    if (speed > 3600.0f) speed = 3600.0f;
    // Re-anchor so simNow stays continuous across the speed change.
    bootSimMillis_ = simNowMillis();
    wallStartMillis_ = millis();
    speed_ = speed;
}

}  // namespace svc
