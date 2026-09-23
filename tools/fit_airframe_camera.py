"""Fit only the camera. No airframe vertices or reference pixels are deformed."""
import json
import hashlib
from pathlib import Path
from fit_reference_projection import project,np,cv2,least_squares
from scipy.ndimage import map_coordinates
from scipy.spatial import cKDTree
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-airframe-v11'
REF=ROOT/'docs/design/reference-family-v10/analysis'


def main():
    mesh=json.loads((OUT/'hull-mesh.json').read_text());vertices=np.array(mesh['vertices'])
    start=np.array(json.loads((OUT/'design-parameters.json').read_text())['cameraParameters'])
    alpha=np.asarray(Image.open(OUT/'hero.png'))[:,:,3]>127
    contours,_=cv2.findContours(alpha.astype(np.uint8),cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
    outline=np.zeros_like(alpha,dtype=np.uint8);cv2.drawContours(outline,contours,-1,1,1)
    dist=cv2.distanceTransform(1-outline,cv2.DIST_L2,5)
    edges={tuple(sorted((a,b))) for f in mesh['faces'] for a,b in zip(f,f[1:]+f[:1])}
    points=np.array([vertices[a]*(1-t)+vertices[b]*t for a,b in edges for t in (.0,.25,.50,.75)])
    uv=project(points,start)
    points=points[map_coordinates(dist,[uv[:,1],uv[:,0]],order=1,mode='nearest')<2.5]
    points=np.unique(np.round(points,5),axis=0)
    ref=np.asarray(Image.open(REF/'reference-silhouette.png'))>127
    signed=cv2.distanceTransform((~ref).astype(np.uint8),cv2.DIST_L2,5)-cv2.distanceTransform(ref.astype(np.uint8),cv2.DIST_L2,5)
    contours,_=cv2.findContours(ref.astype(np.uint8),cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_NONE)
    target=max(contours,key=len)[::5,0,:]
    data=json.loads((OUT/'landmarks.json').read_text());source=json.loads((REF/'reference-annotations.json').read_text())
    keys=['fan_left','fan_right','chin_sensor'];anchors=np.array([data[k]['xyz'] for k in keys]);anchor_targets=np.array([source['landmarks'][k] for k in keys])
    def residual(camera):
        uv=project(points,camera)
        direct=map_coordinates(signed,[uv[:,1],uv[:,0]],order=1,mode='nearest')
        reverse=cKDTree(uv).query(target)[0]
        anchor=(project(anchors,camera)-anchor_targets)*9
        prior=(camera-start)*np.array([30,30,.04,.01,.015,.015])
        return np.concatenate([direct,reverse,anchor.ravel(),prior])
    low=start+np.array([-.08,-.10,-25,-30,-60,-60]);high=start+np.array([.08,.10,25,30,60,60])
    fit=least_squares(residual,start,bounds=(low,high),loss='soft_l1',f_scale=10,max_nfev=240,diff_step=.0005)
    report={'method':'Rigid perspective camera only. Airframe mesh and input image are unchanged.',
            'cameraParameters':fit.x.tolist(),'startingCamera':start.tolist(),
            'initialObjective':float(np.mean(residual(start)**2)),
            'finalObjective':float(np.mean(residual(fit.x)**2)),
            'anchorErrorsPx':dict(zip(keys,np.linalg.norm(project(anchors,fit.x)-anchor_targets,axis=1).tolist())),
            'sourceMeshSha256':hashlib.sha256((OUT/'hull-mesh.json').read_bytes()).hexdigest(),
            'visualApproval':False}
    (OUT/'camera-fit.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
