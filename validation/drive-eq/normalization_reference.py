import json, math
weights={'Z':[0]*8,'A':[-26.2,-16.1,-8.6,-3.2,0,1.2,1,-1.1],'C':[-.8,-.2,0,0,0,-.2,-.8,-3]}
print(json.dumps([dict(weighting=w,fixedTotalNoAirDb=100+10*math.log10(sum(10**(x/10) for x in a)/8),perBandReferenceNoAirDb=100+10*math.log10(sum(10**(x/10) for x in a)),driveDifferenceDb=10*math.log10(8)) for w,a in weights.items()],indent=2))
