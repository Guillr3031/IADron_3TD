#!/usr/bin/env python3
"""
Servidor central de pega para las primeras pruebas: recibe lo que reenvía el backend y lo muestra.

    python3 iadron/tools/servidor_central_mock.py --port 8090

Endpoints (los de iadron.forward en application-iadron.yml):
    POST /api/drone/pose      -> pose del dron (JSON snake_case)
    POST /api/drone/capture   -> foto subida a MinIO (object_key, thermal, pose_at_upload...)
    POST /api/ia/thermal      -> hace de aplicación de IA: recibe "A|<base64 TI>" (iadron.thermal.ia-url)

Sustitúyelo por el servidor central real cuando exista. Sólo usa la librería estándar.
"""
import argparse
import base64
import json
import struct
from array import array
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def mostrar_termica(now, raw):
    """Decodifica el mensaje "A|<base64>" igual que lo hará la IA y muestra un resumen."""
    texto = raw.decode("ascii").strip()
    prefijo, _, b64 = texto.partition("|")
    ti = base64.b64decode(b64, validate=True)
    if ti[:2] != b"TI":
        raise ValueError("cabecera TI no encontrada")
    w, h = struct.unpack_from("<ii", ti, 2)
    esperado = 54 + w * h * 4
    if len(ti) != esperado:
        raise ValueError(f"tamaño {len(ti)}, se esperaban {esperado}")
    temps = array("f")
    temps.frombytes(ti[10:10 + w * h * 4])  # little-endian en x86
    pos = struct.unpack_from("<3f", ti, 10 + w * h * 4)
    rot = struct.unpack_from("<3f", ti, 22 + w * h * 4)
    gim = struct.unpack_from("<3f", ti, 34 + w * h * 4)
    (ts,) = struct.unpack_from("<q", ti, 46 + w * h * 4)
    caliente = max(range(len(temps)), key=temps.__getitem__)
    hora = datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S.%f")[:-3] if ts else "-"
    print(f"{now} IA    {prefijo}| {w}x{h} min {min(temps):.1f} ºC max {max(temps):.1f} ºC "
          f"(px {caliente % w},{caliente // w}) valores distintos {len(set(temps))} foto {hora}")
    print(f"             pos={tuple(round(v, 2) for v in pos)} rot={tuple(round(v, 1) for v in rot)} "
          f"gimbal={tuple(round(v, 1) for v in gim)}")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        now = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        if self.path.endswith("/ia/thermal"):
            try:
                mostrar_termica(now, raw)
                self.send_response(204)
            except Exception as e:  # noqa: BLE001
                print(f"{now} IA    MENSAJE INVÁLIDO: {e}")
                self.send_response(400)
            self.end_headers()
            return
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            body = {"_raw": raw.decode(errors="replace")}

        if self.path.endswith("/pose"):
            pos = body.get("positioning") or {}
            print(
                f"{now} POSE  {body.get('source', '?'):8} gw={body.get('gateway_sn')} "
                f"h={body.get('height')} yaw={body.get('attitude_head')} "
                f"v=({body.get('speed_x')},{body.get('speed_y')},{body.get('speed_z')}) "
                f"gimbal_yaw={body.get('gimbal_yaw')} "
                f"gnss={'OK' if pos.get('gnss_usable') else 'NO'} ({pos.get('reason', '-')})"
            )
        elif self.path.endswith("/capture"):
            print(f"{now} FOTO  thermal={body.get('thermal')} key={body.get('object_key')}")
            print(json.dumps(body, indent=2, ensure_ascii=False))
        else:
            print(f"{now} {self.path}: {body}")

        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass  # silenciar el log por petición de http.server


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    print(f"Servidor central de prueba escuchando en {args.host}:{args.port}")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()