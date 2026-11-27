#!/usr/bin/env python3
"""Matched fresh-worker trials; source swapping is exclusive. Candidate always restored."""
import argparse,hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parent;OUT=HERE/'results'
p=argparse.ArgumentParser();p.add_argument('--stage',choices=['bounded','expanded'],required=True);args=p.parse_args()
SOURCE=ROOT/'android/shared-core/src/commonMain/kotlin/com/droidacoustic/pro/acoustics/CoherentDiffuse.kt'
before=(OUT/'before-CoherentDiffuse.kt.txt').read_bytes();candidate=SOURCE.read_bytes()
cap=OUT/'candidate-CoherentDiffuse.kt.txt'
if cap.exists():
    assert cap.read_bytes()==candidate,'Current source differs from retained candidate'
else: cap.write_bytes(candidate)
trials=[('01-before',False,False,3,False),('02-after',True,False,3,False),
 ('03-after',True,False,3,False),('04-before',False,False,3,False),
 ('05-profile-before',False,True,3,False),('06-profile-after',True,True,3,False)] if args.stage=='bounded' else [
 ('07-expanded-before',False,False,33,False),('08-expanded-after',True,False,33,True)]
assert all(not (OUT/name).exists() for name,*_ in trials),'Refusing to overwrite retained trials'
assert not os.getenv('JAVA_TOOL_OPTIONS') and not os.getenv('JDK_JAVA_OPTIONS')
try:
 for name,after,profile,count,reopen in trials:
    folder=OUT/name;folder.mkdir()
    SOURCE.write_bytes(candidate if after else before)
    env=dict(os.environ,ACOUSTIC_COST_PROFILE_DIR=str(folder),ACOUSTIC_CACHE_PROFILE=str(profile).lower(),
        ACOUSTIC_GEOMETRY_RECEIVERS=str(count),ACOUSTIC_GEOMETRY_REOPEN=str(reopen).lower())
    command=['./gradlew',':app:testDebugUnitTest','-PacousticCostProfile=true','--tests','*GeometryTraversalBenchmarkTest',
        '-Pkotlin.compiler.execution.strategy=in-process','--offline']
    print(name,flush=True)
    with (folder/'build.log').open('w') as log:
        subprocess.run(command,cwd=ROOT/'android',env=env,check=True,stdout=log,stderr=subprocess.STDOUT)
    xml=ROOT/'android/app/build/test-results/testDebugUnitTest/TEST-com.droidacoustic.pro.scene.GeometryTraversalBenchmarkTest.xml'
    shutil.copyfile(xml,folder/'test.xml');r=ET.parse(xml).getroot()
    assert int(r.get('tests'))==1 and all(int(r.get(k,0))==0 for k in ['failures','errors','skipped'])
    rows=json.loads((folder/'costs.json').read_text())
    assert all(row['profilingEnabled']==profile and len(row['indices'])==count for row in rows.values())
    if reopen: assert all(row['diskSaveReopenMatched'] for row in rows.values())
    record=dict(variant='after' if after else 'before',receiverCount=count,profilingEnabled=profile,reopen=reopen,
      sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),loadAverage=os.getloadavg(),command=command,
      times={k:r['totalSeconds'] for k,r in rows.items()})
    (folder/'run.json').write_text(json.dumps(record,indent=2)+'\n');print(record['times'],flush=True)
finally:
 SOURCE.write_bytes(candidate)
 print('Candidate geometry source restored',flush=True)
