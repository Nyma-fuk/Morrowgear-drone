"""Fit a constrained mesh cage against the reference silhouette, not its lighting."""
import json
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'work/visual-analysis-libs'))
import cv2
import numpy as np
from PIL import Image
from scipy.optimize import least_squares
from scipy.ndimage import map_coordinates
from scipy.spatial import cKDTree
from reference_surface_cage import CONTROLS,basis
from fit_reference_projection import project

OUT=ROOT/'docs/design/reference-family-v10/analysis'
BASE=OUT.parent/'base'


def main():
    mesh=json.loads((BASE/'hull-mesh.json').read_text(encoding='utf-8'))
    vertices=np.array(mesh['vertices']);faces=mesh['faces']
    fitted=json.loads((OUT/'reference-shape-fit.json').read_text(encoding='utf-8'))
    camera=np.array(fitted['cameraParameters']);az,el=camera[:2]
    direction=np.array([np.sin(az)*np.cos(el),-np.cos(az)*np.cos(el),np.sin(el)])
    edge_faces={}
    for index,face in enumerate(faces):
        for a,b in zip(face,face[1:]+face[:1]):edge_faces.setdefault(tuple(sorted((a,b))),[]).append(index)
    facing=[]
    for face in faces:
        a,b,c=vertices[face[:3]];facing.append(np.cross(b-a,c-a)@direction>0)
    edges=[edge for edge,ids in edge_faces.items() if len(ids)==1 or any(facing[i] for i in ids) and not all(facing[i] for i in ids)]
    # Duct inner boundaries are not part of an outer silhouette comparison.
    edges=[(a,b) for a,b in edges if min(np.hypot(abs(vertices[v,0])-1.91,vertices[v,1]-fitted['fanY']) for v in (a,b))>1.0]
    points=[]
    for a,b in edges:
        points.extend(vertices[a]*(1-t)+vertices[b]*t for t in np.linspace(0,1,8))
    points=np.array(points)
    initial_mask=np.array(Image.open(OUT/'contour-corrected-silhouette.png'))>127
    outline=cv2.morphologyEx(initial_mask.astype(np.uint8),cv2.MORPH_GRADIENT,np.ones((3,3),np.uint8))>0
    to_outline=cv2.distanceTransform((~outline).astype(np.uint8),cv2.DIST_L2,5)
    initial_pixels=project(points,camera)
    visible_outline=map_coordinates(to_outline,[initial_pixels[:,1],initial_pixels[:,0]],order=1,mode='nearest')<4
    points=points[visible_outline]
    weights=basis(points,fitted['fanY'])
    refmask=np.array(Image.open(OUT/'reference-silhouette.png'))>127
    inside=cv2.distanceTransform(refmask.astype(np.uint8),cv2.DIST_L2,5)
    outside=cv2.distanceTransform((~refmask).astype(np.uint8),cv2.DIST_L2,5)
    signed=outside-inside
    contours,_=cv2.findContours(refmask.astype(np.uint8),cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_NONE)
    refpoints=max(contours,key=len)[:,0,:][::6].astype(float)
    landmark_data=json.loads((BASE/'landmarks.json').read_text(encoding='utf-8'))
    refdata=json.loads((OUT/'reference-annotations.json').read_text(encoding='utf-8'))
    keys=['fan_left','fan_right','chin_sensor','nose_visor','nose_right_corner']
    landmark_xyz=np.array([landmark_data[k]['xyz'] for k in keys]);lmweights=basis(landmark_xyz,fitted['fanY'])
    landmark_target=np.array([refdata['landmarks'][k] for k in keys])
    def residual(values):
        moved=points+np.einsum('ijk,k->ij',weights,values)
        pixels=project(moved,camera)
        distance=map_coordinates(signed,[pixels[:,1],pixels[:,0]],order=1,mode='nearest')
        inverse=cKDTree(pixels).query(refpoints)[0]
        landmarks=project(landmark_xyz+np.einsum('ijk,k->ij',lmweights,values),camera)-landmark_target
        return np.concatenate([distance,inverse,landmarks.ravel()*2,values*30])
    initial=np.zeros(len(CONTROLS))
    fitted_cage=least_squares(residual,initial,bounds=(-.38,.38),diff_step=.002,
                             loss='soft_l1',f_scale=8,max_nfev=160,verbose=0)
    result={'method':'Smooth bilateral vertex deformation; fixed camera, no image warp. Rotor neighborhood is pinned.',
            'controls':CONTROLS,'values':fitted_cage.x.tolist(),'fanY':fitted['fanY'],
            'maxControlDisplacement':float(np.max(np.abs(fitted_cage.x))),
            'initialObjective':float(np.mean(residual(initial)**2)),
            'finalObjective':float(np.mean(residual(fitted_cage.x)**2)),
            'notUniqueDepthReconstruction':True,'fidelityApproved':False}
    (OUT/'reference-surface-cage.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
