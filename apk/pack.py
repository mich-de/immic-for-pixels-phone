#!/usr/bin/env python3
"""Compone l'APK non firmato: risorse+manifest (da aapt2), classes.dex, librerie native e asset.

Gli asset grossi (già compressi in .gz) vengono memorizzati senza ricompressione, così l'app li
legge in streaming e conosce la dimensione esatta per la barra di avanzamento.
"""
import argparse
import os
import zipfile

ap = argparse.ArgumentParser()
ap.add_argument("--base", required=True, help="APK di aapt2 con manifest e risorse")
ap.add_argument("--dex", required=True)
ap.add_argument("--lib", required=True, help="cartella con lib/<abi>/*.so")
ap.add_argument("--out", required=True)
ap.add_argument("--asset", action="append", default=[], help="nome=percorso (finisce in assets/nome)")
a = ap.parse_args()

STORED, DEFLATED = zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED

with zipfile.ZipFile(a.base) as src, zipfile.ZipFile(a.out, "w", allowZip64=True) as z:
    for info in src.infolist():
        zi = zipfile.ZipInfo(info.filename, date_time=(2026, 1, 1, 0, 0, 0))
        # resources.arsc non compresso (richiesto/consigliato da Android)
        zi.compress_type = STORED if info.filename.endswith(".arsc") else DEFLATED
        z.writestr(zi, src.read(info.filename))

    z.write(a.dex, "classes.dex", compress_type=DEFLATED)

    for root, _, files in os.walk(a.lib):
        for f in sorted(files):
            path = os.path.join(root, f)
            arc = os.path.join("lib", os.path.relpath(path, a.lib)).replace(os.sep, "/")
            z.write(path, arc, compress_type=STORED)  # affiancato da zipalign -p

    for spec in a.asset:
        name, path = spec.split("=", 1)
        big_or_packed = name.endswith((".gz", ".tgz")) or os.path.getsize(path) > 1 << 20
        z.write(path, "assets/" + name, compress_type=STORED if big_or_packed else DEFLATED)

print("creato", a.out, "%.1f MB" % (os.path.getsize(a.out) / 1e6))
