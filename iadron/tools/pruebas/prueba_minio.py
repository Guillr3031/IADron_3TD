"""
Prueba del flujo MinIO -> base64 -> IA, sin dron.

Uso:
    python prueba_minio.py RUTA_IMAGEN_R.JPG
    python prueba_minio.py RUTA_IMAGEN_R.JPG --ia http://IP_DE_LA_IA:PUERTO/ruta   (opcional)

Pasos que hace:
  1. Sube la imagen a MinIO (cloud-bucket/prueba/...) como lo haría el dron.
  2. Genera una URL prefirmada, como la que generaría el backend.
  3. Descarga la imagen con esa URL (lo que hará el servidor central) y comprueba que es idéntica.
  4. La convierte a base64 y guarda la petición para la IA en peticion_ia.json.
  5. Hace de IA: decodifica el base64 y abre la imagen para comprobar que llega bien.
  6. Si pasas --ia, envía la petición a la IA de verdad y muestra su respuesta.

Requisitos: pip install minio pillow
Credenciales: las del usuario de la aplicación en MinIO (MINIO_APP_USER / MINIO_APP_PASSWORD
de iadron\\.env). Si no las cambiaste, son iadron / iadron-minio-123.
"""
import argparse
import base64
import hashlib
import io
import json
import os
import urllib.request
from datetime import timedelta
from pathlib import Path

from minio import Minio
from PIL import Image

MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_USER = os.getenv("MINIO_APP_USER", "iadron")
MINIO_PASSWORD = os.getenv("MINIO_APP_PASSWORD", "iadron-minio-123")
BUCKET = "cloud-bucket"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("imagen")
    ap.add_argument("--ia", help="URL de la IA (opcional)")
    args = ap.parse_args()

    img = Path(args.imagen).resolve()
    original = img.read_bytes()
    object_key = f"prueba/{img.parent.name}_{img.name}"

    # 1. Subir (lo que hace el dron)
    cliente = Minio(MINIO_ENDPOINT, access_key=MINIO_USER, secret_key=MINIO_PASSWORD, secure=False)
    cliente.fput_object(BUCKET, object_key, str(img), content_type="image/jpeg")
    print(f"1. Subida a MinIO          {BUCKET}/{object_key}  ({len(original) / 1e6:.2f} MB)")

    # 2. URL prefirmada (lo que hará el backend)
    url = cliente.presigned_get_object(BUCKET, object_key, expires=timedelta(hours=1))
    print(f"2. URL prefirmada (1 h)    {url[:90]}...")

    # 3. Descargar con la URL (lo que hará el servidor central)
    with urllib.request.urlopen(url, timeout=10) as r:
        descargado = r.read()
    igual = hashlib.sha256(descargado).digest() == hashlib.sha256(original).digest()
    print(f"3. Descarga por URL        {'OK, idéntica al original' if igual else 'ERROR: difiere del original'}")

    # 4. Base64 y petición para la IA
    peticion = {
        "image_id": img.stem,
        "image": base64.b64encode(descargado).decode("ascii"),
        "thermal": True,
    }
    Path("peticion_ia.json").write_text(json.dumps(peticion), encoding="utf-8")
    print(f"4. Base64                  {len(peticion['image']) / 1e6:.2f} MB de texto -> peticion_ia.json")

    # 5. Simular el lado de la IA
    recibida = Image.open(io.BytesIO(base64.b64decode(peticion["image"])))
    print(f"5. Decodificada en la IA   {recibida.format} {recibida.size[0]}x{recibida.size[1]}")

    # 6. Enviar a la IA real (opcional)
    if args.ia:
        req = urllib.request.Request(args.ia, data=json.dumps(peticion).encode("utf-8"),
                                     headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=60) as r:
            print(f"6. Respuesta de la IA ({r.status}):")
            print(r.read().decode("utf-8", errors="replace"))


if __name__ == "__main__":
    main()
