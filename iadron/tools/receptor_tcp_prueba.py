#!/usr/bin/env python3
"""
Hace de aplicación de IA por TCP para probar sin tu compañero.

    python iadron/tools/receptor_tcp_prueba.py --port 9000                 (mensajes terminados en \\n)
    python iadron/tools/receptor_tcp_prueba.py --port 9000 --length-prefix (int32 LE + mensaje)

Decodifica cada "A|<base64>" como lo hará la IA y muestra un resumen. Solo librería estándar.
"""
import argparse
import base64
import socket
import struct
from array import array
from datetime import datetime


def resumen(msg: bytes):
    prefijo, _, b64 = msg.decode("ascii").partition("|")
    ti = base64.b64decode(b64, validate=True)
    if ti[:2] != b"TI":
        raise ValueError("cabecera TI no encontrada")
    w, h = struct.unpack_from("<ii", ti, 2)
    if len(ti) != 54 + w * h * 4:
        raise ValueError(f"tamaño {len(ti)}, se esperaban {54 + w * h * 4}")
    t = array("f")
    t.frombytes(ti[10:10 + w * h * 4])
    rot = struct.unpack_from("<3f", ti, 22 + w * h * 4)
    gim = struct.unpack_from("<3f", ti, 34 + w * h * 4)
    (ts,) = struct.unpack_from("<q", ti, 46 + w * h * 4)
    hora = datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S.%f")[:-3] if ts else "-"
    print(f"{datetime.now():%H:%M:%S} {prefijo}| {w}x{h} min {min(t):.1f} max {max(t):.1f} "
          f"valores distintos {len(set(t))} rot={tuple(round(v, 1) for v in rot)} "
          f"gimbal={tuple(round(v, 1) for v in gim)} foto {hora}")


def leer_exacto(f, n):
    datos = f.read(n)
    if len(datos) < n:
        raise EOFError
    return datos


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--length-prefix", action="store_true")
    args = ap.parse_args()
    srv = socket.create_server(("0.0.0.0", args.port))
    print(f"Receptor TCP de prueba en el puerto {args.port} "
          f"({'prefijo de longitud' if args.length_prefix else 'mensajes terminados en salto de línea'})")
    while True:
        conn, addr = srv.accept()
        print(f"Conectado {addr}")
        with conn, conn.makefile("rb") as f:
            try:
                while True:
                    if args.length_prefix:
                        (n,) = struct.unpack("<i", leer_exacto(f, 4))
                        msg = leer_exacto(f, n)
                    else:
                        msg = f.readline()
                        if not msg:
                            raise EOFError
                        msg = msg.rstrip(b"\r\n")
                    try:
                        resumen(msg)
                    except Exception as e:  # noqa: BLE001
                        print(f"MENSAJE INVÁLIDO: {e}")
            except EOFError:
                print("Conexión cerrada")


if __name__ == "__main__":
    main()