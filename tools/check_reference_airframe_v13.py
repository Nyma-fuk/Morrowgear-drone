"""Geometry diagnostics only; no implied aerodynamic certification."""
import json
import math
from pathlib import Path

import reference_airframe_sections as s

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-airframe-v13'


def main():
    mesh=json.loads((OUT/'hull-mesh.json').read_text());vertices=mesh['vertices'];deviations=[];errors=[]
    for f in mesh['faces']:
        points=[vertices[i] for i in f]
        if len(points)!=3 or any(abs(z-s.skin(x,y))>.001 for x,y,z in points):continue
        x,y,z=[sum(p[k] for p in points)/3 for k in range(3)]
        delta=abs(z-s.skin(x,y))
        errors.append(delta)
        if delta>.02:deviations.append({'delta':delta,'center':[x,y,z],'vertices':points})
    samples=[]
    for x in (0.,.9,1.3,2.95,3.3,3.7):
        lo,hi=s.chord_bounds(x);c=hi-lo
        thickness=[s.skin(x,lo+c*i/400)-s.skin(x,lo+c*i/400,False) for i in range(401)]
        samples.append({'x':x,'chord':c,'maxThickness':max(thickness),
                        'thicknessToChord':max(thickness)/c,
                        'leadingThickness':thickness[0],'trailingThickness':thickness[-1]})
    report={'scope':'Geometric section and surface sampling, not airflow simulation.',
            'maximumSampledTriangleSurfaceError':max(errors,default=0),
            'sampledTriangleCount':len(errors),
            'deviatingTriangleCount':len(deviations),'worstTriangles':sorted(deviations,key=lambda d:d['delta'],reverse=True)[:8],
            'sections':samples}
    (OUT/'section-diagnostics.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
