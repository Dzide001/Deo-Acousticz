#!/usr/bin/env python3
"""Summarize JFR execution stacks without retaining JVM environment/event payloads."""
import argparse
import collections
import hashlib
import json
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('samples',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('--recording',type=Path,required=True)
args=parser.parse_args()
events=json.loads(args.samples.read_text())['recording']['events']
leaf=collections.Counter();project=collections.Counter();inclusive=collections.Counter()
for event in events:
    frames=(event['values'].get('stackTrace') or {}).get('frames',[])
    names=[f["method"]["type"]["name"]+'.'+f['method']['name']+':'+str(f.get('lineNumber','')) for f in frames]
    if names:leaf[names[0]]+=1
    own=[name for name in names if name.startswith(('com/droidacoustic','com.droidacoustic'))]
    if own:project[own[0]]+=1
    inclusive.update(set(name.rsplit(':',1)[0] for name in own))
count=sum(project.values());assert count>0
report=dict(scope='Sampled JVM stacks; shares are project-containing samples, not exact CPU time',
    recordingSha256=hashlib.sha256(args.recording.read_bytes()).hexdigest(),
    executionEvents=len(events),projectSamples=count,
    projectLeaf=[dict(frame=n,samples=v,projectSamplePercent=100*v/count) for n,v in project.most_common(30)],
    inclusiveProjectMethods=[dict(method=n,samples=v) for n,v in inclusive.most_common(30)],
    allLeaf=[dict(frame=n,samples=v) for n,v in leaf.most_common(20)])
args.output.write_text(json.dumps(report,indent=2)+'\n')
print(f'{len(events)} sampled events; {count} stacks contain project frames')
