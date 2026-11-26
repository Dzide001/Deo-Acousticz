#!/usr/bin/env python3
"""Verify raw UTF-8 payload/CRLF/BOM preservation and rejection of invalid encoding."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parent


class PackageCsvTest(unittest.TestCase):
    def package(self,raw,version=1):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);csv=root/'export.csv';csv.write_bytes(raw)
            out=root/'package.json'
            protocol=json.loads((ROOT/'protocol-template.json').read_text())
            protocol['version']=version
            if version in (3,4,5):
                protocol=json.loads((ROOT/'fixtures/broadband-reference.json').read_text())
                if version==4:
                    protocol=json.loads((ROOT/'fixtures/relative-broadband-reference.json').read_text())
                if version==5:
                    protocol=json.loads((ROOT/'fixtures/total-drive-reference.json').read_text())
                protocol.pop('payload');protocol.pop('sourceSha256')
            if version==2:
                protocol['normalization']=dict(method='per-frequency-level-difference',xM=10,yM=1,zM=0,minimumReferenceLevelDb=-120)
                protocol['conditions']['levelConvention']='RELATIVE_TO_REFERENCE'
                protocol['units']['level']='dB_relative'
            meta=root/'protocol.json';meta.write_text(json.dumps(protocol))
            completed=subprocess.run([sys.executable,str(ROOT/'package_csv.py'),str(csv),str(meta),str(out)],capture_output=True)
            return completed.returncode,json.loads(out.read_text()) if out.exists() else None

    def test_original_utf8_bytes_and_hash_survive_packaging(self):
        raw='\ufeffFrequency,Level,Note\r\n1000,80,"µ mic, left"\r\n'.encode('utf-8')
        code,document=self.package(raw)
        self.assertEqual(0,code)
        self.assertEqual(raw,document['payload'].encode('utf-8'))
        self.assertEqual(hashlib.sha256(raw).hexdigest(),document['sourceSha256'])
        self.assertEqual('export.csv',document['source']['fileName'])

    def test_version_two_preserves_the_explicit_normalization_basis(self):
        code,document=self.package(b'Frequency,Level\n1000,0\n',version=2)
        self.assertEqual(0,code)
        self.assertEqual(2,document['version'])
        self.assertEqual(10,document['normalization']['xM'])
        self.assertEqual('RELATIVE_TO_REFERENCE',document['conditions']['levelConvention'])
        self.assertEqual('dB_relative',document['units']['level'])

    def test_version_three_preserves_weighted_broadband_definition(self):
        code,document=self.package(b'Frequency,Level\n0,80\n',version=3)
        self.assertEqual(0,code)
        self.assertEqual(3,document['version'])
        self.assertEqual('A',document['broadband']['weighting'])
        self.assertEqual('aggregate',document['units']['frequency'])
        self.assertEqual('dB_A',document['units']['level'])

    def test_version_four_preserves_weighted_relative_basis(self):
        code,document=self.package(b'Frequency,Level\n0,0\n',version=4)
        self.assertEqual(0,code)
        self.assertEqual(4,document['version'])
        self.assertEqual('weighted-broadband-level-difference',document['normalization']['method'])
        self.assertEqual('dB_relative_A',document['units']['level'])

    def test_version_five_preserves_total_drive_spectrum(self):
        code,document=self.package(b'Frequency,Level\n0,80\n',version=5)
        self.assertEqual(0,code)
        self.assertEqual(5,document['version'])
        self.assertEqual('TOTAL_EQUAL_BAND_POWER',document['broadband']['sourceSpectrum']['mode'])
        self.assertAlmostEqual(1,sum(document['broadband']['driveFractions'].values()))

    def test_invalid_utf8_is_not_silently_replaced(self):
        code,document=self.package(b'Hz,Level\n1000,\xff\n')
        self.assertNotEqual(0,code)
        self.assertIsNone(document)


if __name__=='__main__':unittest.main()
