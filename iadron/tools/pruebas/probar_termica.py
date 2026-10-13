"""
Prueba del DJI Thermal SDK (Windows).

Uso:
    python probar_termica.py RUTA_A_dji_irp.exe RUTA_A_IMAGEN_R.JPG

Hace tres cosas:
  1. Genera la imagen coloreada (modo process) y la guarda como PNG.
  2. Mide temperaturas (modo measure, float32) y muestra min / max / punto más caliente.
  3. Repite la medida con varias emisividades para ver cómo cambia la lectura.

Requisitos: pip install numpy pillow
"""
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

EMISIVIDADES = [0.95, 0.70, 0.30]
DISTANCIA = 5        # m (en interior, pocos metros)
HUMEDAD = 50         # %
REFLEXION = 22       # °C (temperatura ambiente de la nave)


def ejecutar(exe, args):
    # dji_irp necesita sus DLL, que están en su misma carpeta: lo lanzamos desde allí.
    r = subprocess.run([str(exe), *args], cwd=exe.parent, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout, r.stderr)
        sys.exit(f"dji_irp falló (código {r.returncode})")
    return r.stdout


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    exe = Path(sys.argv[1]).resolve()
    img = Path(sys.argv[2]).resolve()
    # Una subcarpeta por imagen: salida_termica\M3TD\DJI_0001_R\
    salida = (Path("salida_termica") / img.parent.name / img.stem).resolve()
    salida.mkdir(parents=True, exist_ok=True)

    # Tamaño de la imagen térmica (la R-JPEG visible tiene el mismo tamaño).
    ancho, alto = Image.open(img).size
    print(f"Imagen: {img.name}  ({ancho}x{alto})")

    # 1. Imagen coloreada
    rgb = salida / "process.rgb"
    ejecutar(exe, ["-s", str(img), "-a", "process", "-p", "iron_red", "-o", str(rgb)])
    datos = np.fromfile(rgb, dtype=np.uint8).reshape(alto, ancho, 3)
    Image.fromarray(datos).save(salida / "process.png")
    print(f"Imagen coloreada -> {salida / 'process.png'}")

    # 2 y 3. Temperaturas con varias emisividades
    caliente = None
    for e in EMISIVIDADES:
        raw = salida / f"measure_e{e:.2f}.raw"
        ejecutar(exe, ["-s", str(img), "-a", "measure", "--measurefmt", "float32",
                       "--distance", str(DISTANCIA), "--humidity", str(HUMEDAD),
                       "--emissivity", str(e), "--reflection", str(REFLEXION),
                       "-o", str(raw)])
        t = np.fromfile(raw, dtype=np.float32)
        if t.size != ancho * alto:
            sys.exit(f"Tamaño inesperado: {t.size} valores, esperaba {ancho * alto}")
        t = t.reshape(alto, ancho)
        if caliente is None:
            caliente = np.unravel_index(t.argmax(), t.shape)  # fijo el píxel con ε=0.95
        y, x = caliente
        print(f"ε={e:.2f}  min={t.min():6.1f} °C  max={t.max():6.1f} °C  "
              f"píxel caliente (x={x}, y={y}) = {t[y, x]:6.1f} °C")

    # 4. Medida SIN pasar parámetros: el SDK usa los que el dron guardó en la foto
    raw = salida / "measure_dron.raw"
    ejecutar(exe, ["-s", str(img), "-a", "measure", "--measurefmt", "float32", "-o", str(raw)])
    t = np.fromfile(raw, dtype=np.float32).reshape(alto, ancho)
    y, x = caliente
    print(f"params del dron  min={t.min():6.1f} °C  max={t.max():6.1f} °C  "
          f"píxel caliente (x={x}, y={y}) = {t[y, x]:6.1f} °C")

    # 5. Imagen con el píxel caliente marcado con una cruz verde
    marcada = datos.copy()
    marcada[max(y - 12, 0):y + 13, max(x - 1, 0):x + 2] = (0, 255, 0)
    marcada[max(y - 1, 0):y + 2, max(x - 12, 0):x + 13] = (0, 255, 0)
    Image.fromarray(marcada).save(salida / "caliente.png")
    print(f"Punto caliente marcado -> {salida / 'caliente.png'}")


if __name__ == "__main__":
    main()