#!/usr/bin/env python3
"""
Servidor central de pega para las primeras pruebas: recibe lo que reenvía el backend y lo muestra.

    python3 iadron/tools/servidor_central_mock.py --port 8090

Endpoints (los de iadron.forward en application-iadron.yml):
    POST /api/drone/pose      -> pose del dron (JSON snake_case)
    POST /api/drone/capture   -> foto subida a MinIO (object_key, thermal, pose_at_upload...)

Sustitúyelo por el servidor central real cuando exista. Sólo usa la librería estándar.
"""
import argparse
import json
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            body = {"_raw": raw.decode(errors="replace")}

        now = datetime.now().strftime("%H:%M:%S.%f")[:-3]
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
