// ESP32-S3 : يحاكي مستقبل Cypcut (VID 6125 / PID 2012) ويتحكم به الهاتف عبر Wi-Fi
// Arduino IDE (ESP32 core 3.x):
//   Board: ESP32S3 Dev Module | USB Mode: USB-OTG (TinyUSB) | USB CDC On Boot: Disabled
//   وصّل منفذ "USB" (OTG) بحاسوب الماكينة، لا منفذ UART.
// الهاتف: اتصل بشبكة LaserRemote ثم افتح http://192.168.4.1
// وضع USB: منفذ UART يذهب للهاتف (تطبيق WkbRemote)، ومنفذ USB-OTG للحاسوب.
// غير مختبر على العتاد: اختبره أولاً بأداة hid-tester قبل الماكينة.

#include <WiFi.h>
#include <WebServer.h>
#include "USB.h"
#include "USBHID.h"

const char* AP_SSID = "LaserRemote";
const char* AP_PASS = "change-me-123";   // غيّرها (8 أحرف على الأقل)

// ---- وصف الجهاز: تقرير Input رقم 1 بطول 31 بايتاً (صفحة المصنّع 0xFF00) ----
static const uint8_t kDesc[] = {
  0x06, 0x00, 0xFF,  // Usage Page (Vendor 0xFF00)
  0x09, 0x00,        // Usage (0)
  0xA1, 0x01,        // Collection (Application)
  0x85, 0x01,        //   Report ID (1)
  0x09, 0x01,        //   Usage (1)
  0x15, 0x00,        //   Logical Min 0
  0x26, 0xFF, 0x00,  //   Logical Max 255
  0x75, 0x08,        //   Report Size 8
  0x95, 0x1F,        //   Report Count 31
  0x81, 0x02,        //   Input (Data,Var,Abs)
  0xC0
};

USBHID HID;
class CypcutDev : public USBHIDDevice {
 public:
  CypcutDev() { HID.addDevice(this, sizeof(kDesc)); }
  uint16_t _onGetDescriptor(uint8_t* buf) {
    memcpy(buf, kDesc, sizeof(kDesc));
    return sizeof(kDesc);
  }
  bool send(const uint8_t* d) { return HID.SendReport(1, d, 31); }
};
CypcutDev dev;

WebServer server(80);
uint8_t keys[4] = {0, 0, 0, 0};   // البايتات B2..B5 (قناع الأزرار)
uint32_t lastCmd = 0, lastTx = 0;

// الصفحة التي يفتحها الهاتف. الخريطة: [الاسم, رقم البايت 0..3 (=B2..B5), القناع, اللون]
const char PAGE[] PROGMEM = R"HTML(<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>Laser Remote</title>
<style>body{margin:0;background:#15171c;font-family:sans-serif}
#g{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;padding:12px;max-width:420px;margin:auto}
button{height:62px;border:0;border-radius:10px;font:600 14px sans-serif;touch-action:none;user-select:none;-webkit-user-select:none}
.g{background:#2f9e5a;color:#fff}.y{background:#f2c230}.r{background:#d9382f;color:#fff}.t{background:#5fc7d6}.w{background:#eef1f4}
.d{filter:brightness(.65)}</style></head><body><div id="g"></div><script>
var K=[["Start",3,4,"g"],["Pause",2,4,"y"],["Dry Cut",1,4,"w"],["Stop",0,4,"r"],
["Blow",2,8,"w"],["Follow",3,8,"w"],["Shutter",1,8,"w"],["Aiming",0,64,"w"],
["PT LOC",0,128,"w"],["Back",3,16,"w"],["Forward",0,16,"w"],["Laser",0,8,"y"],
["Edge Seek",2,16,"w"],["Fn",3,128,"t"],["Frame",0,2,"t"],["Step",0,32,"t"],
["Zero",0,1,"w"],["\u2191",2,2,"w"],["Z\u2191",1,16,"t"],["Fast",2,1,"y"],
["\u2190",3,2,"w"],["\u2193",3,1,"w"],["\u2192",1,2,"w"],["Z\u2193",1,1,"t"]];
var held={},tm=null,g=document.getElementById('g');
function snd(){var s=[0,0,0,0];for(var id in held){var k=K[held[id]];s[k[1]]|=k[2]}
fetch('/s?b='+s.map(function(v){return('0'+v.toString(16)).slice(-2)}).join('')).catch(function(){})}
function upd(){snd();if(Object.keys(held).length){if(!tm)tm=setInterval(snd,100)}else{clearInterval(tm);tm=null}}
K.forEach(function(k,i){var b=document.createElement('button');b.className=k[3];b.textContent=k[0];
b.onpointerdown=function(e){e.preventDefault();b.setPointerCapture(e.pointerId);held[e.pointerId]=i;b.classList.add('d');upd()};
function up(e){delete held[e.pointerId];b.classList.remove('d');upd()}
b.onpointerup=up;b.onpointercancel=up;g.appendChild(b)});
document.oncontextmenu=function(){return false};
</script></body></html>)HTML";

void applyHex(const String& b) {
  if (b.length() == 8) {
    for (int i = 0; i < 4; i++) keys[i] = (uint8_t)strtoul(b.substring(i * 2, i * 2 + 2).c_str(), NULL, 16);
    lastCmd = millis();
  }
}

void onState() {
  applyHex(server.arg("b"));
  server.send(204, "text/plain", "");
}

// USB-Serial من الهاتف: أسطر بالشكل  S:AABBCCDD\n  (115200)
String sbuf;
void pollSerial() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      if (sbuf.length() == 10 && sbuf.startsWith("S:")) applyHex(sbuf.substring(2));
      sbuf = "";
    } else if (c != '\r' && sbuf.length() < 16) {
      sbuf += c;
    }
  }
}

void setup() {
  Serial.begin(115200);   // منفذ UART (للهاتف عبر USB)
  USB.VID(0x6125);
  USB.PID(0x2012);
  USB.firmwareVersion(0x0100);
  USB.manufacturerName("Cypcut");
  USB.productName("Cypcut Wireless USB Receiver");
  HID.begin();
  USB.begin();

  WiFi.softAP(AP_SSID, AP_PASS);
  server.on("/", []() { server.send_P(200, "text/html", PAGE); });
  server.on("/s", onState);
  server.begin();
}

void loop() {
  server.handleClient();
  pollSerial();

  // أمان: إن انقطعت نبضات الهاتف أكثر من 350 ms تُحرَّر كل الأزرار (يمنع تعلّق زر حركة)
  if (millis() - lastCmd > 350) memset(keys, 0, 4);

  if (millis() - lastTx >= 15) {          // نفس معدل المستقبل الأصلي (~15 ms)
    lastTx = millis();
    uint8_t r[31] = {0};
    r[0] = 0xBC; r[1] = 0x55;
    memcpy(&r[2], keys, 4);
    r[8] = 0x81; r[9] = 0x01;
    uint16_t sum = 1;                      // رقم التقرير (1) يدخل في المجموع
    for (int i = 0; i < 30; i++) sum += r[i];
    r[30] = sum & 0xFF;                    // خالٍ من الأزرار = 0x94
    if (HID.ready()) dev.send(r);
  }
}