# Independent arithmetic for the existing base-two compatibility convention.
import json, math
rows=[dict(storedLowHz=a,storedHighHz=b,queryHz=f,expectedDb=80+10*math.log(f/31.25)/math.log(2)) for a in (31,32) for b in (63,64) for f in (40,50)]
print(json.dumps(rows,indent=2))
