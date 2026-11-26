#!/usr/bin/env python3
"""Independent CF1 source-row references. No engine imports or electrical assumptions."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

ROOT=Path(__file__).resolve().parent
OCTAVES=[32,63,125,250,500,1000,2000,4000,8000,16000]


def generate():
    provenance=json.loads((ROOT/'provenance.json').read_text());cases=[]
    for pair in provenance['pairs']:
        text=ROOT/'fixtures'/pair['text'];binary=ROOT/'fixtures'/pair['binary']
        assert hashlib.sha256(text.read_bytes()).hexdigest()==pair['fixtureTextSha256']
        assert hashlib.sha256(binary.read_bytes()).hexdigest()==pair['binarySha256']
        lines=text.read_text().splitlines()
        levels=[float(v) for v in next(r for r in lines if r.startswith('<SENSITIVITY>')).split('\t')[1:]]
        bands=[int(r.split('\t')[1]) for r in lines if r.startswith('<BAND>')]
        assert len(levels)==len(bands)
        raw=binary.read_bytes()
        for hz,level in zip(bands,levels):
            offset=0x1240+OCTAVES.index(hz)*4
            decoded=struct.unpack_from('<f',raw,offset)[0]
            assert abs(decoded-level)<1e-4
            cases.append(dict(binary=pair['binary'],text=pair['text'],hz=hz,referenceDb=level,
                              binaryByteOffset=offset,binaryValueDb=decoded))
    powered=ROOT/'fixtures/Powered.CF1'
    assert hashlib.sha256(powered.read_bytes()).hexdigest()==provenance['poweredSha256']
    assert all(v==0 for v in struct.unpack_from('<10f',powered.read_bytes(),0x1240))
    return dict(scope='CF1_SENSITIVITY_ROW_BYTE_OFFSET_NOT_ELECTRICAL_CALIBRATION',toleranceDb=0.0001,
                referenceScriptSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                provenanceSha256=hashlib.sha256((ROOT/'provenance.json').read_bytes()).hexdigest(),cases=cases,
                poweredReference='UNAVAILABLE; zero placeholders must not become level data')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    result=generate();path=ROOT/'results/reference.json'
    if args.check:assert json.loads(path.read_text())==result,'Stale CF1 references'
    else:path.write_text(json.dumps(result,indent=2)+'\n')
    print('13 official CF1 sensitivity slots and powered missing-reference control verified')
