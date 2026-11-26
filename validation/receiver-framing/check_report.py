#!/usr/bin/env python3
"""Independent standard-library projection check of the six retained synthetic framing cases."""
import itertools,json,math
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def dot(a,b):return sum(x*y for x,y in zip(a,b))
def sub(a,b):return [x-y for x,y in zip(a,b)]
def unit(a):
    length=math.sqrt(dot(a,a));assert length>0
    return [x/length for x in a]
def cross(a,b):return [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
def check():
    data=json.loads((ROOT/'results/reports.json').read_text());assert len(data['cases'])==6
    # Fixed synthetic receiver in ReceiverFramingPackageTest, not the offset camera aim point.
    receiver=[38.0,12.0,-17.0]
    for c in data['cases']:
        assert c['selectedPick']==c['focus']['receiverId'] and c['focus']['view']==c['view']
        forward=unit(sub(c['target'],c['eye']));right=unit(cross(forward,[0,1,0]));up=cross(right,forward)
        focal=c['height']/(2*math.tan(math.radians(45)/2))
        def project(p):
            q=sub(p,c['eye']);depth=dot(q,forward);assert .1<depth<200
            return [c['width']/2+dot(q,right)*focal/depth,c['height']/2-dot(q,up)*focal/depth]
        center=project(receiver);assert max(abs(a-b) for a,b in zip(center,c['receiverScreen']))<.001
        x,y,w,h=c['usableRect'];radius=c['focus']['spanM']/2
        for signs in itertools.product([-1,1],repeat=3):
            p=[receiver[i]+radius*(signs[0]*right[i]+signs[1]*up[i]-signs[2]*forward[i]) for i in range(3)]
            sx,sy=project(p);assert x-.001<=sx<=x+w+.001 and y-.001<=sy<=y+h+.001
    print('Six framing cases passed independent projection, context-corner and clipping checks')
if __name__=='__main__':check()
