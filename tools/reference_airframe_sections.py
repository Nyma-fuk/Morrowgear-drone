"""Shared geometric hypothesis for the V13 game-art airframe, not a CFD model."""
import math
from bisect import bisect_right

BOUNDARY=[(0,-2.66,.08),(.59,-2.62,.085),(.94,-2.34,.09),
          (1.30,-2.10,.10),(3.98,.14,.10),(4.74,.74,.37),
          (4.08,1.36,.68),(3.58,1.08,.10),(2.61,1.74,.12),
          (1.12,1.92,.25),(0,1.91,.28)]
CROWN=[(-2.66,-.005),(-2.36,.18),(-1.70,.44),(-.85,.67),(-.25,.72),(.70,.66),(1.32,.40),(1.91,.06)]
BELLY=[(-2.66,-.28),(-2.10,-.39),(-1.0,-.40),(.5,-.33),(1.32,-.15),(1.91,-.025)]
CX,CY,RADIUS=1.91,.01826677,.90
DECK=.365


def interpolate(value,samples):
    if value<=samples[0][0]:return samples[0][1]
    if value>=samples[-1][0]:return samples[-1][1]
    i=bisect_right([q[0] for q in samples],value)-1
    (a,b),(c,d)=samples[i:i+2];u=(value-a)/(c-a)
    return b*(1-u)+d*u


def smooth(value):
    u=max(0.0,min(1.0,value))
    return u*u*u*(10+u*(-15+6*u))


def chord_bounds(x):
    x=max(0,min(abs(x),BOUNDARY[5][0]-1e-7));hits=[]
    for a,b in zip(BOUNDARY,BOUNDARY[1:]+BOUNDARY[:1]):
        if min(a[0],b[0])<=x<max(a[0],b[0]):
            t=(x-a[0])/(b[0]-a[0]);hits.append(a[1]+(b[1]-a[1])*t)
    if len(hits)<2:return -2.66,1.91
    return min(hits),max(hits)


def folded_tip(x,y):
    corners=[BOUNDARY[i] for i in (4,5,6,7)]
    for a,b,c in ((corners[0],corners[1],corners[2]),(corners[0],corners[2],corners[3])):
        denominator=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        u=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/denominator
        w=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/denominator
        if min(u,w,1-u-w)>-1e-6:
            return max(0,u*a[2]+w*b[2]+(1-u-w)*c[2]-.10)
    return 0.


def skin(x,y,top=True):
    x=abs(x);leading,trailing=chord_bounds(x);chord=trailing-leading
    u=max(0.,min(1.,(y-leading)/max(chord,1e-7)))
    # A rounded-leading-edge / tapered-trailing-edge class function. Its
    # coefficients are art hypotheses, not an airfoil performance selection.
    thickness_ratio=interpolate(x,[(0,.16),(1.2,.16),(2.8,.14),(4.74,.11)])
    shape=math.sqrt(u)*(1-u)/(.3849001794597505)
    half_thickness=chord*(.5*thickness_ratio*shape+.0035)
    mean=.005+.015*chord*math.sin(math.pi*u)+folded_tip(x,y)
    outer=mean+(half_thickness if top else -half_thickness)
    fan_distance=math.hypot(x-CX,y-CY)
    collar=1-smooth((fan_distance-RADIUS-.045)/.32)
    packaged=(DECK if top else -.19)
    outer=outer*(1-collar)+packaged*collar
    root=1-smooth((x-.58)/.68)
    body=interpolate(y,CROWN if top else BELLY)
    return root*body+(1-root)*outer


def parameters():
    return {'status':'UNAPPROVED_GEOMETRY_HYPOTHESIS','boundary':BOUNDARY,
            'crown':CROWN,'belly':BELLY,'fanCenter':[CX,CY],
            'fanRadius':RADIUS,'fanLipTop':DECK-.008,'wingDeck':DECK,
            'geometricSymmetry':True,'isAerodynamicSimulation':False,
            'rootBlendX':[.58,1.26],
            'thicknessRatioArtParameters':[[0,.16],[1.2,.16],[2.8,.14],[4.74,.11]],
            'sectionLaw':'sqrt(u)*(1-u), normalized at u=1/3; finite edge thickness',
            'controlAuthorityVerified':False,'massPropertiesVerified':False}
