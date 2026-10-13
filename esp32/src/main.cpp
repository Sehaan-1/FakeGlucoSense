// FakeGlucoSense ESP32 firmware (ADR-0015).
//
// Turns a plain ESP32 dev board into a simulated CGM sensor over real BLE, speaking the same
// Bluetooth SIG CGM Service profile as the sensor-app, so the FakeGlucoSense companion connects
// with no changes. Not a medical device; all data is synthetic.
//
// Serial CLI (115200 baud): help, status, scenario <1-5>, speed <x>, start, stop, interval <min>.

#include <Arduino.h>

#include "cgm_service.h"

namespace {

void printHelp() {
    Serial.println(F(
        "commands:\n"
        "  help                this text\n"
        "  status              session, scenario, speed, subscribers\n"
        "  scenario <1-5>      1 STEADY, 2 MEAL_DAY, 3 NOCTURNAL_LOW, 4 HIGH_SPIKE, 5 ACTIVE_DAY\n"
        "  speed <x>           simulated minutes per wall minute, 0.1 to 3600 (e.g. 60)\n"
        "  start               start the CGM session\n"
        "  stop                stop the CGM session (sets SESSION_STOPPED status)\n"
        "  interval <min>      communication interval for live records, 1-255 (default 5)"));
}

void printStatus() {
    Serial.printf("[status] session %s · scenario %s · speed %.1fx · interval %d min · subscribers %d · history %u\n",
                  svc::g_cgm.sessionActive() ? "running" : "stopped",
                  sim::scenarioName(svc::g_cgm.scenario()),
                  static_cast<double>(svc::g_cgm.speed()),
                  svc::g_cgm.communicationIntervalMinutes(),
                  svc::g_cgm.subscribers(),
                  static_cast<unsigned>(svc::g_cgm.historySize()));
}

void handleCommand(const String& line) {
    if (line == F("help")) {
        printHelp();
    } else if (line == F("status")) {
        printStatus();
    } else if (line.startsWith(F("scenario "))) {
        const int n = line.substring(9).toInt();
        if (n >= 1 && n <= sim::SCENARIO_COUNT) {
            svc::g_cgm.setScenario(static_cast<sim::Scenario>(n - 1));
            Serial.printf("[cmd] scenario %s\n", sim::scenarioName(static_cast<sim::Scenario>(n - 1)));
        } else {
            Serial.println(F("[cmd] scenario must be 1-5"));
        }
    } else if (line.startsWith(F("speed "))) {
        svc::g_cgm.setSpeed(line.substring(6).toFloat());
        Serial.println(F("[cmd] speed updated"));
    } else if (line == F("start")) {
        svc::g_cgm.startSession();
        Serial.println(F("[cmd] session running"));
    } else if (line == F("stop")) {
        svc::g_cgm.stopSession();
        Serial.println(F("[cmd] session stopped"));
    } else if (line.startsWith(F("interval "))) {
        if (svc::g_cgm.setCommunicationInterval(line.substring(9).toInt())) {
            Serial.println(F("[cmd] interval updated"));
        } else {
            Serial.println(F("[cmd] interval must be 1-255"));
        }
    } else if (line.length() > 0) {
        Serial.println(F("[cmd] unknown command; try 'help'"));
    }
}

String commandLine;

}  // namespace

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println(F("FakeGlucoSense ESP32 sensor — simulated data, not a medical device."));
    svc::g_cgm.begin(sim::MEAL_DAY, 1.0f, 42);
    printHelp();
}

void loop() {
    svc::g_cgm.tick();
    while (Serial.available() > 0) {
        const char c = static_cast<char>(Serial.read());
        if (c == '\r') continue;
        if (c == '\n') {
            commandLine.trim();
            commandLine.toLowerCase();
            handleCommand(commandLine);
            commandLine = "";
        } else if (commandLine.length() < 64) {
            commandLine += c;
        }
    }
}
