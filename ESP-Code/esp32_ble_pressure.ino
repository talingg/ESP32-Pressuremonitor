/*
  ESP32 BLE Pressure Sensor
  ---------------------------------------------------------
  Reads the MPXV7025DP differential pressure sensor via an
  ADS1115 external I2C ADC, and sends the pressure over BLE as
  a notification every 100ms to any connected client.

  Board: ESP32 (Arduino core)
  Sensor: MPXV7025DP analog differential pressure sensor
  (range: -25 kPa to +25 kPa), read via ADS1115 (I2C, address 0x48,
  channel AIN0, GAIN_ONE => 0.125 mV/count, +/-4.096V range).

  --- Wiring notes ---
  A voltage divider (5.1kOhm in series from the sensor's output,
  10kOhm from that node to GND) sits between the sensor and the
  ADS1115 input, scaling the sensor's output voltage down before
  it reaches the ADC. The code below mathematically undoes this
  divider to recover the sensor's actual output voltage before
  applying the pressure conversion formula.

  MPXV7025DP transfer function (from datasheet, assuming a
  SENSOR_SUPPLY_VOLTAGE of 5.0V - adjust below if you're actually
  powering the sensor from a different rail, e.g. 3.3V):
      Vout = VS * (0.2 * P + 0.5)     [P in kPa, -25..25]
      => P = ((Vout / VS) - 0.5) / 0.2

  --- Power optimization (Stage 1) ---
  - CPU clock lowered to 80MHz (BLE doesn't need 240MHz; this cuts
    active-mode current draw noticeably).
  - Classic Bluetooth controller memory released, since only BLE is
    used - avoids initializing/reserving hardware we don't need.
  - Main loop no longer busy-polls every 5ms; it sleeps for the full
    interval between actions instead, giving FreeRTOS/the radio more
    opportunity to enter idle/modem-sleep states.
*/

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "esp_bt.h"
#include <Wire.h>
#include <Adafruit_ADS1X15.h>

// --- BLE identifiers -------------------------------------------------
// Randomly generated UUIDs - keep these consistent between firmware
// and any client app you write later.
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// --- Dummy data mode ---------------------------------------------------
// Set to true to fall back to generated test data (e.g. if the sensor
// or ADS1115 is temporarily disconnected) without touching the rest
// of the sketch.
#define USE_DUMMY_DATA false

// --- ADS1115 / sensor wiring & calibration -----------------------------
#define ADS1115_I2C_ADDRESS 0x48
#define ADS1115_SDA_PIN 21
#define ADS1115_SCL_PIN 22
#define ADS1115_CHANNEL 0

// Voltage divider between the sensor output and the ADS1115 input:
// R1 in series from the sensor, R2 from that node to GND.
const float DIVIDER_R1_OHMS = 5100.0f;
const float DIVIDER_R2_OHMS = 10000.0f;

// Sensor supply voltage - MPXV7025DP's transfer function is defined
// relative to this. Update if you're not powering it from 5V.
const float SENSOR_SUPPLY_VOLTAGE = 5.0f;

// Zero-offset calibration: baseline reading at rest (no pressure applied
// to either port). Re-measure and update this if you ever re-wire the
// sensor or notice drift.
const float PRESSURE_ZERO_OFFSET_KPA = -0.065f;

// Values within this distance of 0 kPa are snapped to exactly 0, to
// suppress sensor/ADC noise/jitter when no real pressure is present.
const float PRESSURE_DEADBAND_KPA = 0.085f;

Adafruit_ADS1115 ads;

BLECharacteristic *pCharacteristic;
BLEServer *pServer;
bool deviceConnected = false;
bool oldDeviceConnected = false;

class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *pServer) override {
    deviceConnected = true;
    Serial.println("Client connected");
  }

  void onDisconnect(BLEServer *pServer) override {
    deviceConnected = false;
    Serial.println("Client disconnected");
    // Do NOT restart advertising here directly - the BLE stack isn't
    // always ready for it immediately inside this callback. We handle
    // the restart in loop() instead, with a short delay.
  }
};

void setup() {
  setCpuFrequencyMhz(80); // BLE doesn't need 240MHz; lowers active-mode current draw
  Serial.begin(115200);

  Wire.begin(ADS1115_SDA_PIN, ADS1115_SCL_PIN);
  if (!ads.begin(ADS1115_I2C_ADDRESS)) {
    Serial.println("ADS1115 not found! Check wiring.");
    while (1)
      ;
  }
  ads.setGain(GAIN_ONE); // +/-4.096V range, 0.125 mV/count

  // We only use BLE, not Bluetooth Classic - release the classic BT
  // controller memory/hardware reservation before initializing BLE.
  esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT);

  BLEDevice::init("ESP32-PressureSensor");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
      CHARACTERISTIC_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  pCharacteristic->addDescriptor(new BLE2902()); // required for notifications

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("ADS1115 ready");
  Serial.println("BLE advertising started as 'ESP32-PressureSensor'");
  Serial.println("Waiting for a client to connect...");
}

const unsigned long sendIntervalMs = 100; // ~real-time (10 readings/sec)

// Generates a plausible-looking MPXV7025DP reading in kPa, oscillating
// slowly within the sensor's +/-25 kPa range with a bit of random noise.
// Only used when USE_DUMMY_DATA is true.
float generateDummyPressure() {
  float t = millis() / 1000.0;
  float baseline = 10.0 * sin(t * 0.5);           // slow oscillation, +/-10 kPa
  float noise = (random(-100, 100) / 100.0) * 0.5; // +/-0.5 kPa jitter
  return baseline + noise;
}

// Reads the ADS1115, undoes the voltage divider to recover the sensor's
// actual output voltage, then converts that to a pressure in kPa using
// the MPXV7025DP's datasheet transfer function.
float readPressureKPa() {
  int16_t raw = ads.readADC_SingleEnded(ADS1115_CHANNEL);
  float measuredVoltage = raw * 0.125f / 1000.0f; // GAIN_ONE: 0.125 mV/count

  float sensorVoltage = measuredVoltage * (DIVIDER_R1_OHMS + DIVIDER_R2_OHMS) / DIVIDER_R2_OHMS;

  float pressureKPa = ((sensorVoltage / SENSOR_SUPPLY_VOLTAGE) - 0.5f) / 0.2f;
  pressureKPa -= PRESSURE_ZERO_OFFSET_KPA; // zero-offset calibration

  if (fabs(pressureKPa) < PRESSURE_DEADBAND_KPA) {
    pressureKPa = 0.0f;
  }

  return pressureKPa;
}

void loop() {
  // --- Handle reconnection lifecycle -----------------------------------
  if (!deviceConnected && oldDeviceConnected) {
    // Client just disconnected. Give the BLE stack a moment to settle
    // before restarting advertising (starting immediately can silently
    // fail on some ESP32 BLE stack versions).
    delay(500);
    pServer->startAdvertising();
    Serial.println("Advertising restarted, waiting for a new connection...");
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    // Client just connected.
    oldDeviceConnected = deviceConnected;
  }

  // --- Send data ---------------------------------------------------------
  if (deviceConnected) {
    float pressureValueKPa = USE_DUMMY_DATA ? generateDummyPressure() : readPressureKPa();
    float pressureValueHPa = pressureValueKPa * 10.0f; // 1 kPa = 10 hPa

    char buf[32];
    snprintf(buf, sizeof(buf), "%.3f", pressureValueHPa);

    pCharacteristic->setValue(buf);
    pCharacteristic->notify();

    Serial.printf("Sent: %s hPa\n", buf);

    // Sleep for the full interval rather than polling millis() every few
    // ms - this lets FreeRTOS/the BLE controller spend more time idle
    // between transmissions instead of repeatedly waking the CPU.
    delay(sendIntervalMs);
  } else {
    // No client connected yet - check back periodically rather than
    // spinning tightly while waiting.
    delay(100);
  }
}
