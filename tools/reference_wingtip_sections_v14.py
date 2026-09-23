"""Mirrored tip reconstruction from visible paired corners, not flight geometry."""
import math
import reference_airframe_sections as base

CROWN = base.CROWN[:-1]+[(1.78,.17),(2.38,.12)]
BELLY = base.BELLY[:-1]+[(2.38,-.025)]
CX, CY, RADIUS, DECK = base.CX, base.CY, base.RADIUS, base.DECK
BOUNDARY = list(base.BOUNDARY)
BOUNDARY[4:8] = [(4.20326628,.15709158,.30180101),
                 (4.83663667,.91032177,.29112542),
                 (4.20888248,1.66371968,.54729770),
                 (4.06503438,1.12532096,.48638087)]
BOUNDARY[9:11] = [(1.12,2.30,.15),(0,2.38,.12)]


def chord_bounds(x):
    x=max(0,min(abs(x),max(p[0] for p in BOUNDARY)-1e-7));hits=[]
    for a,b in zip(BOUNDARY,BOUNDARY[1:]+BOUNDARY[:1]):
        if min(a[0],b[0])<=x<max(a[0],b[0]):
            t=(x-a[0])/(b[0]-a[0]);hits.append(a[1]+(b[1]-a[1])*t)
    return (min(hits),max(hits)) if len(hits)>1 else (-2.66,1.91)


def tip_surface(x,y):
    # Extrapolate the plane, not an inverse bilinear patch: the latter has
    # ambiguous roots outside the patch and would create mesh spikes.
    p,q,r,s=BOUNDARY[4:8]
    ax,ay,az=[q[i]-p[i] for i in range(3)]
    bx,by,bz=[s[i]-p[i] for i in range(3)]
    det=ax*by-ay*bx
    u=((x-p[0])*by-(y-p[1])*bx)/det
    v=(ax*(y-p[1])-ay*(x-p[0]))/det
    return p[2]+u*az+v*bz,u,v


def inner_skin(x,y,top=True):
    leading,trailing=chord_bounds(x);chord=trailing-leading
    u=max(0.,min(1.,(y-leading)/max(chord,1e-7)))
    ratio=base.interpolate(x,[(0,.16),(1.2,.16),(2.8,.14),(4.84,.11)])
    shape=math.sqrt(u)*(1-u)/.3849001794597505
    half=chord*(.5*ratio*shape+.0035)
    outer=.005+.015*chord*math.sin(math.pi*u)+(half if top else -half)
    collar=1-base.smooth((math.hypot(x-CX,y-CY)-RADIUS-.045)/.32)
    outer=outer*(1-collar)+(DECK if top else -.19)*collar
    root=1-base.smooth((x-.58)/.68)
    return root*base.interpolate(y,CROWN if top else BELLY)+(1-root)*outer


def skin(x,y,top=True):
    x=abs(x)
    if x<2.88:return inner_skin(x,y,top)
    # Blend the raised outer deck into the existing wing; the reference does
    # not support V13's steep triangular winglet and diagonal crease.
    z,u,v=tip_surface(x,y)
    t=max(0,min(1,(y-BOUNDARY[4][1])/(BOUNDARY[7][1]-BOUNDARY[4][1])))
    root_x=BOUNDARY[4][0]*(1-t)+BOUNDARY[7][0]*t
    weight=max(0,min(1,(x-2.88)/max(.1,root_x-2.88)))
    if x<root_x:z=tip_surface(root_x,y)[0]
    old=inner_skin(x,y,top)
    return old*(1-weight)+(z if top else z-.10)*weight


def parameters():
    result=base.parameters()
    result.update(boundary=BOUNDARY,crown=CROWN,belly=BELLY,tipConstruction='planar full-chord panel; mirrored',
                  sourceEvidence='single oblique image; corner correspondences are hypotheses',
                  cameraRefitted=False,unseenViewsAreInferred=True)
    return result
