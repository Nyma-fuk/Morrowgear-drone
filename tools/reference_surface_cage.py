"""Small bilateral deformations of the real mesh, with fixed rotor clearances."""
import numpy as np

# (x, y, sigma-x, sigma-y, displacement axis). Coordinates are model units.
CONTROLS=[(.65,-2.42,.60,.55,0),(.65,-2.42,.60,.55,1),(.65,-2.42,.60,.55,2),
          (1.12,-1.90,.65,.65,0),(1.12,-1.90,.65,.65,1),(1.12,-1.90,.65,.65,2),
          (3.0,-.30,1.0,.60,1),(3.0,-.30,1.0,.60,2),
          (4.72,.85,.42,.38,0),(4.72,.85,.42,.38,1),(4.72,.85,.42,.38,2),
          (4.10,1.47,.42,.38,0),(4.10,1.47,.42,.38,1),(4.10,1.47,.42,.38,2),
          (2.60,1.36,.92,.35,1),(2.60,1.36,.92,.35,2),
          (.60,1.74,.7,.35,1),(.60,1.74,.7,.35,2)]


def basis(points,fan_y):
    points=np.asarray(points);x=np.abs(points[:,0]);y=points[:,1]
    radius=np.hypot(x-1.91,y-fan_y)
    free=np.clip((radius-1.01)/.45,0,1)
    free=free*free*(3-2*free)
    weights=np.zeros((len(points),3,len(CONTROLS)))
    for index,(cx,cy,sx,sy,axis) in enumerate(CONTROLS):
        influence=np.exp(-.5*((x-cx)/sx)**2-.5*((y-cy)/sy)**2)*free
        if axis==0:influence*=np.tanh(points[:,0]/.60)
        weights[:,axis,index]=influence
    return weights


def deform(points,values,fan_y):
    points=np.asarray(points,dtype=float)
    return points+np.einsum('ijk,k->ij',basis(points,fan_y),np.asarray(values))
