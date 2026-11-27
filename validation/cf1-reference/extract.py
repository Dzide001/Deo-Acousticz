#!/usr/bin/env python3
"""Prepare private external inputs; --output must point OUTSIDE this repository."""
from pathlib import Path
import subprocess,sys
subprocess.run([sys.executable,str(Path(__file__).resolve().parents[1]/'fixture-permissions/prepare_external.py'),*sys.argv[1:]],check=True)
