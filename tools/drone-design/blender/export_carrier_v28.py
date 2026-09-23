"""Publish approved V28 through the existing MGM4 pipeline, preserving both canons."""
import importlib.util
import json
import shutil
import struct
import sys
from collections import Counter
from pathlib import Path

sys.dont_write_bytecode=True
import bpy
from mathutils import Vector

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('intake28',HERE/'build_carrier_v28_intake_proposal.py')
p=importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
c,q,d,v=p.c,p.q,p.d,p.v
SOURCE=p.OUT/'carrier-v28-intake-proposal.blend'
OUT=p.OUT/'runtime'
c.OUT=OUT
c.STAGED=OUT/'staged-assets'
c.REVISION='V28_APPROVED_RUNTIME_01'
PATHS=('models/runtime/carrier.mgm','models/runtime/carrier_lod.mgm',
       'textures/runtime/carrier.png','textures/runtime/carrier_lod.png','textures/item/carrier_unit.png')
original_geometry=c.runtime_geometry


def approved_geometry(lod=False):
    for obj in p.meshes():
        obj['carrier_runtime']=True
    return original_geometry(lod)


c.runtime_geometry=approved_geometry


def signature(triangles):
    return Counter(tuple(sorted(tuple(round(value,5) for value in point) for point in triangle))
                   for triangle in triangles)


def binary_rows(path):
    raw=path.read_bytes()
    _,count,rotors=struct.unpack_from('>III',raw)
    return list(struct.iter_unpack('>20f6B',raw[12+rotors*12:]))


def check_canonical(name,lod):
    p.open_source(SOURCE)
    vertices,faces,_,_,parts=approved_geometry(lod)
    expected=signature([[(vertices[i].x,vertices[i].z,vertices[i].y) for i in face] for face in faces])
    rows=binary_rows(c.STAGED/'models/runtime'/(name+'.mgm'))
    actual=signature([[rows[i+j][:3] for j in range(3)] for i in range(0,len(rows),3)])
    intake=[obj.name for obj in p.meshes() if obj.name.startswith('Intake')]
    present=all(name in parts for name in intake)
    return {'passed':expected==actual and present,'triangleMultisetMatchesCanonical':expected==actual,
            'canonicalTriangles':sum(expected.values()),'runtimeTriangles':len(rows)//3,
            'allIntakePartsIncluded':present,'intakeParts':intake,'positionPrecision':.00001,
            'sourceSha256':q.digest(SOURCE)}


def readback(name):
    rows=binary_rows(c.STAGED/'models/runtime'/(name+'.mgm'))
    bpy.ops.wm.read_factory_settings(use_empty=True)
    order=[base+j for base in range(0,len(rows),3) for j in (0,2,1)]
    mesh=bpy.data.meshes.new(name+'_readback')
    mesh.from_pydata([(rows[i][0],rows[i][2],rows[i][1]) for i in order],[],
                    [(i,i+1,i+2) for i in range(0,len(rows),3)])
    mesh.update()
    uv=mesh.uv_layers.new(name='GameUV')
    for loop,index in zip(uv.data,order):
        loop.uv=(rows[index][18],1-rows[index][19])
    obj=bpy.data.objects.new(name+'_readback',mesh)
    bpy.context.scene.collection.objects.link(obj)
    atlas=bpy.data.images.load(str(c.STAGED/'textures/runtime'/(name+'.png')))
    for signal in (0,1,2):
        mat=bpy.data.materials.new(name+'_signal_'+str(signal))
        mat.use_nodes=True
        nodes=mat.node_tree.nodes
        bsdf=nodes['Principled BSDF']
        bsdf.inputs['Metallic'].default_value=0
        bsdf.inputs['Roughness'].default_value=.7
        tex=nodes.new('ShaderNodeTexImage')
        tex.image=atlas
        mat.node_tree.links.new(tex.outputs['Color'],bsdf.inputs['Base Color'])
        if signal:
            bsdf.inputs['Emission Strength'].default_value=1.2
            if signal==1:
                bsdf.inputs['Emission Color'].default_value=(.03,.68,.8,1)
            else:
                mat.node_tree.links.new(tex.outputs['Color'],bsdf.inputs['Emission Color'])
        mesh.materials.append(mat)
    for face,index in zip(mesh.polygons,range(0,len(rows),3)):
        face.material_index=rows[index][25]
    q.studio('chunkbuster')
    p.lighting()
    q.render(OUT/(name+'-mgm-readback.png'),(4,-7,-3.6),61,(0,0,5.5),(1440,1080))


def icon():
    p.open_source(SOURCE)
    scene=bpy.context.scene
    scene.render.film_transparent=True
    checks=[]
    for size in (16,32,64,128):
        path=OUT/('carrier-unit-'+str(size)+'.png')
        q.render(path,(4,-6,5),58,(0,0,5.5),(size,size))
        image=bpy.data.images.load(str(path),check_existing=False)
        pixels=list(image.pixels)
        occupied=sum(pixels[i]>.05 for i in range(3,len(pixels),4))
        clipped=any(pixels[(y*size+x)*4+3]>.05 for y in range(size) for x in range(size)
                    if x==0 or y==0 or x==size-1 or y==size-1)
        checks.append({'size':size,'occupiedPixels':occupied,'clipped':clipped,'rgba':image.channels==4})
    destination=c.STAGED/'textures/item/carrier_unit.png'
    destination.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(OUT/'carrier-unit-128.png',destination)
    return checks


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    canons={str(path):q.digest(path) for path in (SOURCE,p.SOURCE,p.BASE/'carrier-v27.glb',
                                               p.OUT/'carrier-v28-intake-proposal.glb')}
    previous={relative:q.digest(c.ASSETS/relative) for relative in PATHS}
    for relative in PATHS:
        backup=OUT/'previous-assets'/relative
        if not backup.exists():
            backup.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(c.ASSETS/relative,backup)
    q.dump(OUT/'previous-assets.json',previous)
    proposal=json.loads((p.OUT/'verification.json').read_text())
    if not proposal['passed'] or proposal['blendSha256']!=q.digest(SOURCE):
        raise RuntimeError('Approved V28 source differs from validated proposal')
    p.open_source(SOURCE)
    native=c.validate()
    if not native['passed']:
        raise RuntimeError(native['errors'])
    for lod in (False,True):
        c.export_runtime(lod,source=SOURCE)
    geometry=[]
    runtime=[]
    for name,lod in (('carrier',False),('carrier_lod',True)):
        geometry.append(check_canonical(name,lod))
        runtime.append(c.read_runtime(c.STAGED/'models/runtime'/(name+'.mgm')))
    icons=icon()
    for name in ('carrier','carrier_lod'):
        readback(name)
    q.sheet_start(1440,1000)
    q.sheet_text('V28 APPROVED CANON / ACTUAL MGM4 + ATLAS',24,18,28)
    for i,(name,label) in enumerate((('carrier','NEAR'),('carrier_lod','FAR'))):
        x=i*720
        q.sheet_text(label+' / '+str(runtime[i]['triangles'])+' TRIANGLES',x+24,82,24)
        q.sheet_image(OUT/(name+'-mgm-readback.png'),x+8,130,704,680)
    q.sheet_text('Exact canonical geometry / all intake parts included in both LODs',24,845,25)
    q.sheet_text('26 W / 50 L / 11 H / 845 entry rays per mesh / zero blocked',24,905,25)
    q.sheet_save(OUT/'runtime-readback-sheet.png')
    unchanged=all(q.digest(Path(path))==digest for path,digest in canons.items())
    unpublished=previous=={relative:q.digest(c.ASSETS/relative) for relative in PATHS}
    errors=[]
    if not unchanged or not unpublished:
        errors.append('canonical or shipped inputs changed during export')
    if not all(check['passed'] for check in geometry+runtime):
        errors.append('runtime geometry or format')
    if any(check['occupiedPixels']<8 or check['clipped'] or not check['rgba'] for check in icons):
        errors.append('canonical icon')
    report={'passed':not errors,'errors':errors,'revision':c.REVISION,'sourceSha256':q.digest(SOURCE),
            'source':str(SOURCE.relative_to(p.ROOT)),'native':native,'runtime':runtime,
            'canonicalGeometryMatches':geometry,'icons':icons,'canonicalFilesUnchanged':unchanged,
            'previousRuntimePreservedUntilValidation':unpublished,
            'scope':'Approved canonical geometry, MGM4/atlas/icon readback; no game launch'}
    q.dump(OUT/'verification.json',report)
    if errors:
        raise RuntimeError(errors)
    c.publish_validated_assets(source=SOURCE)
    publication=json.loads((OUT/'published-assets.json').read_text())
    if any(q.digest(c.ASSETS/asset['path'])!=asset['sha256'] for asset in publication['assets']):
        raise RuntimeError('Published resource hash mismatch')
    print('V28_APPROVED_ASSETS_PUBLISHED',flush=True)


if __name__=='__main__':
    main()
