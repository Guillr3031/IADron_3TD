"""
Simula un Dock 2 con un Matrice 3TD hablando con el backend IADron por MQTT (EMQX).

Uso (por fases, en este orden):
    python simular_dron.py alta        # 1. el Dock se conecta y anuncia que lleva el dron
    python simular_dron.py vincular    # 2. vincula Dock y dron al workspace (como haría Pilot 2)
    python simular_dron.py volar       # 3. alta + telemetría del dron cada segundo (Ctrl+C para parar)

Qué mirar mientras corre:
  - Consola del backend: líneas [IADRON] y errores.
  - Mock del servidor central (:8090): líneas POSE.
  - EMQX (:18083) -> Clients: aparece 'sim-dock'.

Requisitos: pip install paho-mqtt
Los mensajes siguen el protocolo DJI Cloud API (topología en sys/product/.../status,
telemetría en thing/product/.../osd). El dron simulado está en interior: sin GNSS.
"""
import json
import math
import os
import sys
import time
import urllib.request
import uuid

import paho.mqtt.client as mqtt

MQTT_HOST = os.getenv("MQTT_HOST", "localhost")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))
BACKEND = os.getenv("BACKEND_URL", "http://localhost:6789")

DOCK_SN = "SIMDOCK0000001"
DRONE_SN = "SIMDRONE000001"

# Códigos de modelo de DJI: Dock 2 = 3-2-0, Matrice 3TD = 0-91-1
DOCK = {"domain": "3", "type": 2, "sub_type": 0}
DRONE = {"domain": "0", "type": 91, "sub_type": 1}


def mensaje(data, method=None, gateway=None):
    m = {"tid": str(uuid.uuid4()), "bid": str(uuid.uuid4()),
         "timestamp": int(time.time() * 1000), "data": data}
    if method:
        m["method"] = method
    if gateway:
        m["gateway"] = gateway
    return m


def topologia(con_dron=True):
    sub = []
    if con_dron:
        sub.append({"sn": DRONE_SN, **DRONE, "index": "A",
                    "device_secret": "secret", "nonce": "nonce", "thing_version": "1.1.2"})
    return {**DOCK, "device_secret": "secret", "nonce": "nonce",
            "thing_version": "1.1.2", "sub_devices": sub}


def conectar():
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="sim-dock")

    def al_recibir(_c, _u, msg):
        print(f"  <- {msg.topic}: {msg.payload.decode(errors='replace')[:300]}")

    c.on_message = al_recibir
    c.connect(MQTT_HOST, MQTT_PORT, keepalive=30)
    c.loop_start()
    # Respuestas del backend hacia el Dock (para ver que nos contesta)
    for t in (f"sys/product/{DOCK_SN}/status_reply", f"thing/product/{DOCK_SN}/services",
              f"thing/product/{DOCK_SN}/requests_reply"):
        c.subscribe(t)
    time.sleep(0.5)
    return c


def publicar(c, topic, msg):
    c.publish(topic, json.dumps(msg), qos=1).wait_for_publish()
    print(f"-> {topic}  {msg.get('method', '')}")


def alta(c):
    publicar(c, f"sys/product/{DOCK_SN}/status", mensaje(topologia(), method="update_topo"))
    time.sleep(2)


def baja(c):
    publicar(c, f"sys/product/{DOCK_SN}/status", mensaje(topologia(con_dron=False), method="update_topo"))


def http(metodo, ruta, cuerpo=None, token=None):
    req = urllib.request.Request(BACKEND + ruta, method=metodo,
                                 data=json.dumps(cuerpo).encode() if cuerpo is not None else None,
                                 headers={"Content-Type": "application/json",
                                          **({"x-auth-token": token} if token else {})})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read() or b"{}")


def vincular():
    login = http("POST", "/manage/api/v1/login",
                 {"username": "adminPC", "password": "adminPC", "flag": 1})
    d = login.get("data") or {}
    token, ws, user = d.get("access_token"), d.get("workspace_id"), d.get("user_id")
    print(f"Login: workspace={ws} user={user}")
    for sn in (DOCK_SN, DRONE_SN):
        r = http("POST", f"/manage/api/v1/devices/{sn}/binding",
                 {"device_sn": sn, "workspace_id": ws, "user_id": user}, token)
        print(f"Vincular {sn}: {r}")


def osd(t):
    """Telemetría del dron: sube a 3 m y da vueltas despacio. Sin GNSS (interior)."""
    altura = min(3.0, 0.5 * t)
    yaw = (t * 10) % 360 - 180
    return {
        "latitude": 0, "longitude": 0, "height": altura, "elevation": altura,
        "attitude_head": round(yaw, 1), "attitude_pitch": 0, "attitude_roll": 0,
        "horizontal_speed": 0.5, "vertical_speed": 0.5 if altura < 3 else 0,
        "mode_code": 3,
        "position_state": {"gps_number": 0, "rtk_number": 0, "is_fixed": 0, "quality": 0},
        "battery": {"capacity_percent": max(20, 100 - int(t / 10))},
        "payloads": [{"payload_index": "99-0-0", "gimbal_yaw": round(yaw, 1),
                      "gimbal_pitch": -30, "gimbal_roll": 0}],
    }


def volar(c):
    alta(c)
    print("Enviando telemetría cada segundo. Ctrl+C para parar.")
    t0 = time.time()
    try:
        while True:
            t = time.time() - t0
            d = osd(t)
            c.publish(f"thing/product/{DRONE_SN}/osd", json.dumps(mensaje(d, gateway=DOCK_SN)))
            print(f"-> osd  h={d['height']:.1f} yaw={d['attitude_head']:6.1f}", end="\r")
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nParando: el Dock anuncia que el dron ya no está.")
        baja(c)


def main():
    fase = sys.argv[1] if len(sys.argv) > 1 else ""
    if fase == "vincular":
        vincular()
        return
    if fase not in ("alta", "volar"):
        sys.exit(__doc__)
    c = conectar()
    alta(c) if fase == "alta" else volar(c)
    time.sleep(1)
    c.loop_stop()
    c.disconnect()


if __name__ == "__main__":
    main()
