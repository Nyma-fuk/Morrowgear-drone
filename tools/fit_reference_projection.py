"""Register a rigid 3D camera, never stretch the candidate render to fit."""
import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'work/visual-analysis-libs'))
import cv2
import numpy as np
from scipy.optimize import least_squares
from PIL import Image,ImageDraw,ImageFont

OUT=ROOT/'docs/design/reference-family-v10/analysis'
BASE=OUT.parent/'base'
FONT='C:/Windows/Fonts/meiryo.ttc'


def project(xyz,params):
    if len(params)==6:az,el,scale,distance,tx,ty=params
    else:az,el,scale,tx,ty=params;distance=None
    right=np.array([math.cos(az),math.sin(az),0])
    up=np.array([-math.sin(az)*math.sin(el),math.cos(az)*math.sin(el),math.cos(el)])
    factor=scale
    if distance is not None:
        view=np.array([math.sin(az)*math.cos(el),-math.cos(az)*math.cos(el),math.sin(el)])
        factor=scale*distance/(distance-np.asarray(xyz)@view)
    return np.stack([tx+np.asarray(xyz)@right*factor,ty-np.asarray(xyz)@up*factor],axis=-1)


def silhouette_metrics(reference,candidate):
    reference=np.asarray(reference,dtype=bool);candidate=np.asarray(candidate,dtype=bool)
    if reference.shape!=candidate.shape:raise ValueError('Silhouettes must share an unwarped image space')
    union=np.count_nonzero(reference|candidate)
    if not union:raise ValueError('Cannot compare empty silhouettes')
    re=cv2.morphologyEx(reference.astype(np.uint8),cv2.MORPH_GRADIENT,np.ones((3,3),np.uint8))>0
    ce=cv2.morphologyEx(candidate.astype(np.uint8),cv2.MORPH_GRADIENT,np.ones((3,3),np.uint8))>0
    if not re.any() or not ce.any():raise ValueError('A silhouette has no contour')
    rd=cv2.distanceTransform((~re).astype(np.uint8),cv2.DIST_L2,5)
    cd=cv2.distanceTransform((~ce).astype(np.uint8),cv2.DIST_L2,5)
    distances=np.concatenate([rd[ce],cd[re]])
    return {'silhouetteIoU':np.count_nonzero(reference&candidate)/union,
            'meanContourErrorPx':float(distances.mean()),
            'p95ContourErrorPx':float(np.quantile(distances,.95))}


def difference(reference,candidate):
    out=np.zeros((*reference.shape,3),np.uint8)+24
    out[reference&candidate]=(124,140,147)
    out[reference&~candidate]=(244,93,73)
    out[candidate&~reference]=(28,184,236)
    return out


def fit_shape(ref,model):
    model['nose_right_corner']={'xyz':[.57,-2.625,-.08]}
    names=['fan_left','fan_right','tip_left_front','tip_left_rear','tip_right_front','tip_right_rear','chin_sensor','rear_crown','nose_visor','nose_right_corner']
    target=np.array([ref['landmarks'][n] for n in names])
    start=np.array([.595,.373,197,18,731,432,4.40,.31,.25,3.80,1.28,.64,-.20,.145])
    def points(params):
        front_x,front_y,front_z,rear_x,rear_y,rear_z,fan_y,fan_z=params[6:]
        result=[]
        for name in names:
            xyz=model[name]['xyz']
            if name.startswith('fan_'):xyz=[(-1 if 'left' in name else 1)*1.91,fan_y,fan_z]
            elif name.startswith('tip_'):
                sign=-1 if 'left' in name else 1
                xyz=[sign*front_x,front_y,front_z] if name.endswith('front') else [sign*rear_x,rear_y,rear_z]
            result.append(xyz)
        return np.array(result)
    def residual(params):
        visible=(project(points(params),params[:6])-target).ravel()
        prior=(params[6:]-start[6:])*np.array([12,12,12,12,12,12,10,10])
        return np.concatenate([visible,prior,[max(0,.25-(params[10]-params[7]))*120,max(0,.10-(params[6]-params[9]))*120]])
    fitted=least_squares(residual,start,
        bounds=([.15,.15,120,10,450,250,3.6,-.35,.06,3.1,.55,.22,-.55,-.03],
                [.95,.9,270,50,1050,650,5.3,1.1,.58,4.7,2.2,1.0,.65,.64]),
        loss='soft_l1',f_scale=14,max_nfev=3000)
    params=fitted.x
    errors={name:float(np.linalg.norm(p-q)) for name,p,q in zip(names,project(points(params),params[:6]),target)}
    result={'method':'Bilateral mesh landmarks fitted with a perspective camera and dimensional priors; single-view inference, not recovered ground-truth depth.',
            'parameters':params.tolist(),'cameraParameters':params[:6].tolist(),
            'frontTip':params[6:9].tolist(),'rearTip':params[9:12].tolist(),
            'fanY':float(params[12]),'fanHubZ':float(params[13]),
            'landmarkErrorPx':errors,'landmarkRmsErrorPx':float(np.sqrt(np.mean(np.array(list(errors.values()))**2))),
            'silhouetteNotYetTested':True,'fidelityApproved':False}
    (OUT/'reference-shape-fit.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--name',default='before');parser.add_argument('--fit-shape',action='store_true');parser.add_argument('--fixed-camera',action='store_true');args=parser.parse_args()
    ref=json.loads((OUT/'reference-annotations.json').read_text(encoding='utf-8'))
    model=json.loads((BASE/'landmarks.json').read_text(encoding='utf-8'))
    if args.fit_shape:return fit_shape(ref,model)
    names=[n for n in ('fan_left','fan_right','tip_left_front','tip_left_rear','tip_right_front','tip_right_rear','chin_sensor','rear_crown') if n in model]
    xyz=np.array([model[n]['xyz'] for n in names]);target=np.array([ref['landmarks'][n] for n in names])
    if args.fixed_camera:params=np.array(json.loads((OUT/'reference-shape-fit.json').read_text(encoding='utf-8'))['cameraParameters'])
    else:
        fit=least_squares(lambda params:(project(xyz,params)-target).ravel(),[.49,.51,180,840,420],
                          bounds=([.05,.1,70,300,100],[1.2,1.25,400,1400,800]),loss='soft_l1',f_scale=24)
        params=fit.x
    camera={'method':('Fixed perspective camera from the joint shape fit.' if args.fixed_camera else 'Rigid orthographic camera fit.')+' No pixel warp or nonuniform image scaling.',
            'azimuth':float(params[0]),'elevation':float(params[1]),'pixelsPerUnit':float(params[2]),
            'centerX':float(params[-2]),'centerY':float(params[-1]),'resolution':ref['size'],
            'fitLandmarks':names,'parameters':params.tolist()}
    (OUT/'reference-camera.json').write_text(json.dumps(camera,indent=2),encoding='utf-8')
    (OUT/(args.name+'-camera.json')).write_text(json.dumps(camera,indent=2),encoding='utf-8')
    residual={n:float(np.linalg.norm(project([model[n]['xyz']],params)[0]-ref['landmarks'][n])) for n in names}
    mesh=json.loads((BASE/'hull-mesh.json').read_text(encoding='utf-8'))
    vertices=np.array(mesh['vertices']);pixels=project(vertices,params)
    width,height=ref['size'];mask=np.zeros((height,width),np.uint8)
    wire=np.zeros((height,width,3),np.uint8)
    camera_dir=np.array([math.sin(params[0])*math.cos(params[1]),-math.cos(params[0])*math.cos(params[1]),math.sin(params[1])])
    for face in mesh['faces']:
        poly=np.round(pixels[face]).astype(np.int32)
        cv2.fillPoly(mask,[poly],255)
        world=vertices[face]
        normal=np.cross(world[1]-world[0],world[2]-world[0])
        if normal@camera_dir>0:cv2.polylines(wire,[poly],True,(80,220,240),1,cv2.LINE_AA)
    contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
    mask[:]=0;cv2.drawContours(mask,contours,-1,255,-1)
    Image.fromarray(mask).save(OUT/(args.name+'-silhouette.png'))
    refmask=np.array(Image.open(OUT/'reference-silhouette.png'))>127;modmask=mask>127
    Image.fromarray(difference(refmask,modmask)).save(OUT/(args.name+'-silhouette-difference.png'))
    source=np.array(Image.open(ROOT/ref['source']).convert('RGB'))
    alpha=np.max(wire,axis=-1)>0
    source[alpha]=(source[alpha]*.35+wire[alpha]*.65).astype(np.uint8)
    Image.fromarray(source).save(OUT/(args.name+'-mesh-overlay.png'))
    stats={**silhouette_metrics(refmask,modmask),
           'landmarkRmsErrorPx':float(np.sqrt(np.mean(np.array(list(residual.values()))**2))),
           'landmarkErrorPx':residual,
           'interpretation':'Image-space diagnostics only, not a percentage of 3D fidelity. Hull silhouette excludes small sensor protrusions.',
           'cameraRefitted':not args.fixed_camera,'referenceMaskReviewed':ref['annotationReviewed'],
           'analyticWireVisibility':'Front-facing faces only; not a depth-buffered visibility test.',
           'landmarkCaveat':'These landmarks were used in fitting, not independent validation. Wingtip front-corner correspondence remains uncertain.',
           'gate':{'silhouetteIoU':.95,'meanContourErrorPx':8,'landmarkRmsErrorPx':12},
           'passed':False}
    stats['passed']=bool(stats['silhouetteIoU']>=.95 and stats['meanContourErrorPx']<=8 and stats['landmarkRmsErrorPx']<=12)
    inputs=[ROOT/ref['source'],OUT/'reference-silhouette.png',OUT/'reference-annotations.json',BASE/'hull-mesh.json',BASE/'landmarks.json']
    if args.fixed_camera:
        full=np.asarray(Image.open(BASE/'hero.png').convert('RGBA'))[:,:,3]
        if full.shape!=refmask.shape:raise ValueError('Render and reference dimensions differ')
        contours,_=cv2.findContours((full>127).astype(np.uint8),cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
        mask=np.zeros_like(full);cv2.drawContours(mask,contours,-1,255,-1)
        stats['fullModelSilhouette']={**silhouette_metrics(refmask,mask>0),
            'scope':'All rendered parts, thresholded alpha, external outline with interior holes filled.'}
        Image.fromarray(difference(refmask,mask>0)).save(OUT/(args.name+'-full-model-difference.png'))
        inputs.append(BASE/'hero.png')
        visible=BASE/'visible-wire.png'
        if visible.exists() and visible.stat().st_mtime>=(BASE/'hero.png').stat().st_mtime:
            rgba=np.asarray(Image.open(visible).convert('RGBA'));rgb=rgba[:,:,:3]
            weight=np.max(rgb,axis=-1)/255*(rgba[:,:,3]/255)*.82
            background=np.asarray(Image.open(ROOT/ref['source']).convert('RGB'))
            composite=(background*(1-weight[:,:,None])+np.array([65,210,236])*weight[:,:,None]).astype(np.uint8)
            Image.fromarray(composite).save(OUT/(args.name+'-visible-mesh-overlay.png'))
            stats['visibleWireVisibility']='Blender material pass with normal surface occlusion; hidden edges are not drawn through nearer surfaces.'
            inputs.append(visible)
    stats['inputSha256']={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
    (OUT/(args.name+'-comparison.json')).write_text(json.dumps(stats,indent=2),encoding='utf-8')
    print(json.dumps(stats,indent=2))


if __name__=='__main__':main()
