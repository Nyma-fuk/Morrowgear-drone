"""Approved supply V2: native polygons and model-derived inventory icons."""
import argparse
import importlib.util
import json
import math
import os
import shutil
import sys
from pathlib import Path

sys.dont_write_bytecode=True
import bpy
from mathutils import Matrix,Vector,kdtree

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
OUT=ROOT/'docs/design/supplies-v2'
ASSETS=ROOT/'src/main/resources/assets/morrowgear_drone'
spec=importlib.util.spec_from_file_location('v24_helpers',HERE/'build_supply_carrier_v24.py')
q=importlib.util.module_from_spec(spec)
spec.loader.exec_module(q)
e,d,v=q.e,q.d,q.v
ITEMS=('autocannon_magazine','laser_cell','micro_missile_pack')
NAMES={'autocannon_magazine':'AUTOCANNON MAGAZINE','laser_cell':'WEAPON ENERGY CELL','micro_missile_pack':'MICRO MISSILE PACK'}
CAPSULE_SOURCE=ROOT/'src/client/java/jp/morrowgear/drone/client/MissileRenderState.java'
# MissileCapsuleShape.HULL; projectile local +Z maps to inventory native -Y.
CAPSULE_RINGS=((-0.40,.10,0x303A40),(-.34,.10,0x53636C),(-.34,.16,0x667780),
               (-.28,.19,0x85969E),(-.10,.19,0x46535C),(-.10,.165,0x222A30),
               (-.02,.165,0x46535C),(-.02,.19,0xA6B3BA),(.24,.19,0x788991),
               (.34,.16,0x65777F),(.40,.11,0xBAC5CB))
CAPSULE_OFFSET=-.10


def box(name,loc,size,mat='armor',pair=False,bevel=.025):
    return v.box(name,loc,size,mat,pair,bevel)


def cyl(name,loc,radius,depth,mat='edge',axis=(0,0,1),pair=False):
    return v.cylinder(name,loc,radius,depth,mat,pair,axis)


def face_panel(name,outline,yfront,yback,mat='armor',pair=False):
    n=len(outline)
    vertices=[(x,y,z) for y in (yfront,yback) for x,z in outline]
    faces=[tuple(range(n-1,-1,-1)),tuple(range(n,2*n))]
    faces.extend((i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n))
    return v.mesh(name,vertices,faces,mat,pair)


def bolt(name,loc,axis=(0,-1,0)):
    cyl(name+'Seat',loc,.035,.012,'dark',axis)
    cyl(name+'Core',Vector(loc)+Vector(axis)*.009,.017,.015,'edge',axis)


def setup():
    e.setup()
    bpy.context.preferences.filepaths.save_version=0
    v.M['red']=v.mat('SupplyMissileOxideBand',(.20,.045,.045),.45,.43)
    v.M['case']=v.mat('SupplyGraphitePanels',(.046,.052,.058),.52,.52)
    v.M['titanium']=v.mat('SupplyTitaniumEdges',(.23,.25,.26),.70,.40)
    v.M['brass']=v.mat('SupplyCartridgeBrass',(.40,.22,.065),.8,.31)
    v.M['projectile']=v.mat('SupplyCopperProjectile',(.38,.13,.050),.78,.31)
    cyan=v.M['cyan'].node_tree.nodes['Principled BSDF']
    cyan.inputs['Base Color'].default_value=(.006,.22,.40,1)
    cyan.inputs['Emission Color'].default_value=(.0,.40,.70,1)
    cyan.inputs['Emission Strength'].default_value=1.6
    for key in ('case','titanium','red'):
        nodes,links=v.M[key].node_tree.nodes,v.M[key].node_tree.links
        bsdf=nodes['Principled BSDF']
        color=tuple(bsdf.inputs['Base Color'].default_value)
        noise=nodes.new('ShaderNodeTexNoise')
        noise.inputs['Scale'].default_value=115
        ramp=nodes.new('ShaderNodeValToRGB')
        for stop,factor in zip(ramp.color_ramp.elements,(.83,1.09)):
            stop.color=(*(x*factor for x in color[:3]),1)
        links.new(noise.outputs['Fac'],ramp.inputs[0])
        links.new(ramp.outputs[0],bsdf.inputs['Base Color'])
        bump=nodes.new('ShaderNodeBump')
        bump.inputs['Strength'].default_value=.13
        bump.inputs['Distance'].default_value=.003
        links.new(noise.outputs['Fac'],bump.inputs['Height'])
        links.new(bump.outputs[0],bsdf.inputs['Normal'])


def ammo_structure():
    box('AmmoCaseFloor',(0,0,.065),(1.58,.97,.13),'armor',bevel=.035)
    for y in (-.455,.455):
        box('AmmoCaseWall',(0,y,.48),(1.58,.09,.83),'case',bevel=.022)
        box('AmmoCaseRim',(0,y,.94),(1.58,.065,.07),'titanium',bevel=.012)
    for x in (-.745,.745):
        box('AmmoCaseSide',(x,0,.49),(.09,.90,.86),'case',bevel=.024)
        box('AmmoSideRim',(x,0,.94),(.07,.93,.07),'titanium',bevel=.01)
        for y in (-.43,.43):
            box('AmmoCornerReinforcement',(x,y,.48),(.14,.14,.94),'titanium',bevel=.030)
            for z in (.13,.82):
                bolt('AmmoFrameFastener',(x,y-.07,z))
        for z in (.48,.62,.76):
            box('AmmoSideVent',(x*1.06,0,z),(.025,.61,.050),'dark',bevel=.01)
    box('AmmoFrontPanel',(0,-.511,.48),(1.26,.04,.76),'case',bevel=.055)
    for x in (-.53,.53):
        box('AmmoLatchMount',(x,-.535,.85),(.20,.055,.19),'titanium',bevel=.017)
        box('AmmoLatchInset',(x,-.569,.86),(.10,.027,.12),'dark',bevel=.012)
        for z in (.37,.63):
            bolt('AmmoFrontBolt',(x,-.546,z))
    box('AmmoBeltBed',(0,0,.76),(1.37,.77,.08),'dark',bevel=.02)
    lid_start=set(bpy.context.scene.objects)
    box('AmmoOpenLid',(0,0,1.05),(1.62,1.01,.13),'case',bevel=.055)
    for x in (-.73,.73):
        box('LidTitaniumRail',(x,0,1.045),(.13,1.01,.17),'titanium',bevel=.027)
    box('LidInnerSeal',(0,0,.969),(1.39,.82,.025),'dark',bevel=.035)
    box('LidInnerPanel',(0,0,.951),(1.21,.65,.024),'case',bevel=.035)
    for x in (-.53,.53):
        box('LidLatchPlate',(x,-.42,1.12),(.19,.20,.055),'titanium',bevel=.018)
        box('LidLatchClasp',(x,-.44,1.16),(.11,.12,.042),'dark',bevel=.01)
    for x in (-.38,.38):
        box('AmmoCarryHandleFoot',(x,-.39,1.13),(.12,.21,.09),'dark',bevel=.018)
        box('AmmoCarryHandleUpright',(x,-.63,1.13),(.09,.42,.10),'titanium',bevel=.025)
    box('AmmoCarryHandleGrip',(0,-.85,1.13),(.82,.15,.10),'dark',bevel=.025)
    pivot=Vector((0,.48,.985))
    turn=Matrix.Translation(pivot)@Matrix.Rotation(math.radians(-68),4,'X')@Matrix.Translation(-pivot)
    for obj in set(bpy.context.scene.objects)-lid_start:
        obj.matrix_world=turn@obj.matrix_world
    for x in (-.56,.56):
        cyl('AmmoLidHinge',(x,.47,.99),.067,.22,'titanium',(1,0,0))
    q.canonical_halves()
    box('AmmoCapacityStripe',(.28,-.535,.48),(.13,.012,.75),'amber',bevel=.001)
    # Cartridge noses point to the feed side; this is intentional functional asymmetry.
    for j in range(6):
        y=-.33+j*.13
        z=.855
        cyl('CartridgeBrassCase',(0.08,y,z),.052,.80,'brass',(1,0,0))
        cyl('CartridgeCaseBase',(.505,y,z),.057,.035,'brass',(1,0,0))
        cyl('CartridgeSteelBelt',(.04,y,z),.057,.48,'dark',(1,0,0))
        bpy.ops.mesh.primitive_cone_add(vertices=24,radius1=.052,radius2=.006,depth=.24,
                                        location=(-.44,y,z),rotation=(0,-math.pi/2,0))
        obj=bpy.context.object
        obj.name='LinkedCartridgeProjectile'
        obj.data.materials.append(v.M['projectile'])
        obj['functional_asymmetry']='feed direction -X'


def energy_cell():
    body=box('CellPressureBody',(0,0,.91),(1.12,.65,1.78),'case',bevel=.105)
    bpy.context.view_layer.objects.active=body
    for modifier in list(body.modifiers):
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    front=face_panel('CellFrontArmor',[(-.46,.14),(-.51,.35),(-.51,1.51),(-.40,1.73),
               (.40,1.73),(.51,1.51),(.51,.35),(.46,.14)],-.353,-.318,'armor')
    for part in (body,front):
        q.subtract(part,box('CellIndicatorVoid',(0,-.36,.90),(.47,.23,1.23),'dark',bevel=.045))
    rail=[(.38,.10),(.55,.17),(.59,.31),(.56,1.62),(.43,1.79),
          (.33,1.78),(.38,1.51),(.39,1.14),(.34,1.02),(.39,.88),(.37,.31)]
    face_panel('CellTitaniumCrashRail',rail,-.39,-.25,'titanium',True)
    for y in (.29,):
        for x in (-.49,.49):
            box('CellRearEdge',(x,y,.91),(.14,.13,1.62),'titanium',bevel=.052)
    face_panel('CellIndicatorRecess',[(-.18,.30),(-.23,.38),(-.23,1.42),(-.15,1.51),
               (.15,1.51),(.23,1.42),(.23,.38),(.18,.30)],-.317,-.26,'dark')
    bezel=face_panel('CellIndicatorBezel',[(-.125,.34),(-.165,.39),(-.165,1.39),(-.11,1.44),
               (.11,1.44),(.165,1.39),(.165,.39),(.125,.34)],-.350,-.316,'edge')
    q.subtract(bezel,box('CellGlassAperture',(0,-.35,.895),(.195,.15,1.0),'dark',bevel=.012))
    box('CellCyanStateWindow',(0,-.343,.895),(.18,.009,.97),'cyan',bevel=.012)
    for z in (.48,.63,.78,.93,1.08,1.23):
        box('CellChargeScale',(0,-.351,z),(.16,.008,.009),'glass',bevel=.001)
    for x in (-.24,.24):
        box('CellTerminalPocket',(x,0,1.799),(.33,.40,.048),'dark',bevel=.035)
        box('CellPowerContact',(x,0,1.836),(.19,.25,.041),'brass',bevel=.016)
        for y in (-.07,.07):
            cyl('CellContactDimple',(x,y,1.860),.018,.004,'dark')
    for x in (-.55,.55):
        for z in (.48,.69,.90,1.11,1.32):
            box('CellSideCoolingSlot',(x,0,z),(.025,.43,.067),'dark',bevel=.02)
    for x in (-.45,.45):
        for z in (.35,1.43):
            bolt('CellRailLock',(x,-.411,z))
    box('CellRearSeal',(0,.336,.90),(.65,.03,1.23),'dark',bevel=.045)
    box('CellRearServicePanel',(0,.361,.90),(.57,.022,1.13),'case',bevel=.038)
    for x in (-.20,.20):
        for z in (.44,1.37):
            bolt('CellRearLock',(x,.383,z),(0,1,0))
    q.canonical_halves()


def missile_profile(name,x,z):
    n=8
    vertices=[(x+r*math.cos(i*math.tau/n),CAPSULE_OFFSET-forward,z+r*math.sin(i*math.tau/n))
              for forward,r,_ in CAPSULE_RINGS for i in range(n)]
    faces=[tuple(reversed(range(n))),tuple(range((len(CAPSULE_RINGS)-1)*n,len(CAPSULE_RINGS)*n))]
    material_indices=[len(CAPSULE_RINGS),len(CAPSULE_RINGS)-1]
    for row in range(len(CAPSULE_RINGS)-1):
        for i in range(n):
            faces.append((row*n+i,row*n+(i+1)%n,(row+1)*n+(i+1)%n,(row+1)*n+i))
            material_indices.append(row)
    obj=v.mesh(name,vertices,faces,'titanium')
    obj.data.materials.clear()
    for color in (*[ring[2] for ring in CAPSULE_RINGS],0x20282D):
        key='CapsuleMetal_'+hex(color)
        if key not in v.M:
            srgb=[((color>>shift)&255)/255 for shift in (16,8,0)]
            linear=[c/12.92 if c<=.04045 else ((c+.055)/1.055)**2.4 for c in srgb]
            v.M[key]=v.mat(key,linear,.60,.40)
        obj.data.materials.append(v.M[key])
    for face,index in zip(obj.data.polygons,material_indices):
        face.material_index=index
        face.use_smooth=False
    band_vertices=[(x+.167*math.cos(i*math.tau/n),CAPSULE_OFFSET-forward,z+.167*math.sin(i*math.tau/n))
                   for forward in (-.082,-.037) for i in range(n)]
    v.mesh(name+'RecessedRedBand',band_vertices,[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)],'red')
    for sign in (-1,1):
        box(name+'Cradle',(x+sign*.18,.015,z-.14),(.080,.48,.12),'edge',bevel=.023)


def missile_rack():
    for x in (-.87,.87):
        face_panel('RackArmoredSide',[(x-.07,.04),(x+.07,.04),(x+.09,.17),(x+.09,1.65),
                   (x+.04,1.78),(x-.06,1.78),(x-.09,1.65),(x-.09,.17)],-.20,.44,'armor')
        box('RackSideRail',(x,.13,.90),(.16,.66,1.64),'case',bevel=.06)
        for z in (.10,.92,1.76):
            box('RackFrameJoint',(x,-.19,z),(.23,.19,.15),'titanium',bevel=.04)
            bolt('RackFrameBolt',(x,-.291,z))
        for y in (-.10,.38):
            box('RackHandleUpright',(x,y,1.95),(.10,.11,.35),'armor',bevel=.025)
        box('RackSideCarryGrip',(x,.14,2.13),(.14,.68,.12),'case',bevel=.027)
    for z in (.10,.92,1.77):
        box('RackShelf',(0,.07,z),(1.79,.77,.12),'case',bevel=.028)
        box('RackFrontShelfEdge',(0,-.33,z),(1.77,.085,.10),'titanium',bevel=.018)
        for x in (-.57,0,.57):
            box('RackShelfTie',(x,-.372,z),(.14,.025,.09),'armor',bevel=.012)
    box('RackRearServicePlate',(0,.43,.94),(1.58,.09,1.57),'dark',bevel=.055)
    for x in (-.70,0,.70):
        box('RackRearFrame',(x,.48,.95),(.10,.06,1.43),'case',bevel=.02)
    for x in (-.52,.52):
        box('RackTopHandleFoot',(x,.12,1.87),(.13,.18,.18),'armor',bevel=.025)
    box('RackCentralCarryHandle',(0,.12,1.97),(1.16,.14,.13),'case',bevel=.03)
    for i,(x,z) in enumerate(((-.53,1.37),(0,1.37),(.53,1.37),(-.29,.55),(.29,.55))):
        missile_profile('Missile_'+str(i),x,z)
    q.canonical_halves()


def build(item):
    setup()
    {'autocannon_magazine':ammo_structure,'laser_cell':energy_cell,'micro_missile_pack':missile_rack}[item]()
    scene=bpy.context.scene
    scene['revision']='SUPPLIES_V2_CAPSULE_01' if item=='micro_missile_pack' else 'USER_APPROVED_SUPPLIES_V2'
    if item=='micro_missile_pack':
        scene['capsule_source_sha256']=q.digest(CAPSULE_SOURCE)
    scene['front_axis']='-Y'
    scene['up_axis']='+Z'
    scene['item_id']=item
    scene['capacity']={'autocannon_magazine':120,'laser_cell':1000,'micro_missile_pack':5}[item]
    scene['approved_reference_sha256']=q.digest(OUT/'reference.png')
    q.studio(item)
    scene.cycles.samples=28
    scene.view_settings.exposure=-.25
    folder=OUT/item
    folder.mkdir(parents=True,exist_ok=True)
    parts=d.geometry()
    points=d.all_points(parts)
    bounds=d.bounds(points)
    target=tuple((bounds[i]+bounds[i+3])/2 for i in range(3))
    span=max(bounds[i+3]-bounds[i] for i in range(3))*1.35
    bpy.ops.wm.save_as_mainfile(filepath=str(folder/(item+'.blend')))
    q.export(folder/(item+'.glb'))
    for name,axis in q.VIEWS:
        q.render(folder/(name+'.png'),axis,span,target,(1024,1024))
    for size in (16,32,64,128,1024):
        q.render(folder/('icon-'+str(size)+'.png'),(4,-6,4),span*.95,target,(size,size))
    triangles=0
    graph=bpy.context.evaluated_depsgraph_get()
    for obj in scene.objects:
        if obj.type=='MESH':
            evaluated=obj.evaluated_get(graph)
            mesh=evaluated.to_mesh()
            mesh.calc_loop_triangles()
            triangles+=len(mesh.loop_triangles)
            evaluated.to_mesh_clear()
    tree=kdtree.KDTree(len(points))
    for i,p in enumerate(points):
        tree.insert(p,i)
    tree.balance()
    mirror=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    report={'passed':item=='autocannon_magazine' or mirror<.0001,'boundsBlender':bounds,
            'triangles':triangles,'mirrorError':mirror,'functionalAsymmetry':'cartridge feed -X' if item=='autocannon_magazine' else None,
            'referenceSha256':q.digest(OUT/'reference.png'),'sourceSha256':q.digest(folder/(item+'.blend')),
            'glbSha256':q.digest(folder/(item+'.glb')),'capacity':scene['capacity'],
            'missileLayout':[3,2] if item=='micro_missile_pack' else None,'icons':{}}
    for size in (16,32,64,128):
        img=bpy.data.images.load(str(folder/('icon-'+str(size)+'.png')),check_existing=False)
        values=list(img.pixels)
        occupied=sum(values[i]>.05 for i in range(3,len(values),4))
        clipped=any(values[(y*size+x)*4+3]>.05 for y in range(size) for x in range(size) if x==0 or y==0 or x==size-1 or y==size-1)
        report['icons'][str(size)]={'size':list(img.size),'occupiedPixels':occupied,'clipped':clipped,'rgba':img.channels==4}
        if occupied<8 or clipped or img.channels!=4:
            report['passed']=False
    q.dump(folder/'validation.json',report)
    if not report['passed']:
        raise RuntimeError(report)
    if item=='micro_missile_pack':
        report['capsuleProfile']={'source':str(CAPSULE_SOURCE.relative_to(ROOT)),
            'sourceSha256':q.digest(CAPSULE_SOURCE),'sides':8,'length':.8,'diameter':.38,
            'rings':CAPSULE_RINGS,'nativeFrontY':CAPSULE_OFFSET-.40,
            'storedState':'no exhaust; non-flashing red identification band'}
        q.dump(folder/'validation.json',report)
    shutil.copyfile(folder/'icon-128.png',folder/'staged-runtime.png')


def sheets(items=ITEMS):
    q.sheet_start(1320,780)
    q.sheet_text('MORROWGEAR / SUPPLIES V2 / CANONICAL 3D',26,18,28)
    for i,item in enumerate(ITEMS):
        x=i*440
        q.sheet_text(NAMES[item],x+20,80,20)
        q.sheet_image(OUT/item/'hero.png',x+10,120,420,420)
        q.sheet_text({'autocannon_magazine':'120 rounds','laser_cell':'1000 weapon energy','micro_missile_pack':'5 missiles / 3 + 2'}[item],x+22,551,20)
        for size,dx in ((128,38),(32,231),(16,315)):
            q.sheet_text(str(size)+'px',x+dx,609,16)
            q.sheet_image(OUT/item/('icon-'+str(size)+'.png'),x+dx,645,size,size)
    q.sheet_save(OUT/'approval-icons.png')
    q.sheet_start(1500,1000)
    q.sheet_text('APPROVED V2 / CAPSULE PROFILE UPDATE',26,18,27)
    q.sheet_image(OUT/'reference.png',20,76,1460,486,uv=(0,.29,1,1))
    for i,item in enumerate(ITEMS):
        q.sheet_image(OUT/item/'hero.png',i*500+30,565,440,380)
    q.sheet_text('Weapon Energy Cell: identifier laser_cell retained',26,960,20)
    q.sheet_save(OUT/'reference-comparison.png')
    for item in items:
        q.sheet_start(1440,1080)
        q.sheet_text(NAMES[item]+' / CANONICAL 7 VIEWS',24,18,25)
        for i,(name,_) in enumerate(q.VIEWS):
            x,y=i%3*480,i//3*330+74
            q.sheet_text(name.upper(),x+20,y,18)
            q.sheet_image(OUT/item/(name+'.png'),x+50,y+25,370,280)
        q.sheet_save(OUT/item/'contact-sheet.png')
    old=OUT/'micro_missile_pack/before-capsule/hero.png'
    if old.exists():
        q.sheet_start(1100,650)
        q.sheet_text('MICRO MISSILE PACK / SAME 3 + 2 RACK',24,18,26)
        q.sheet_text('BEFORE / POINTED',24,74,22)
        q.sheet_text('CURRENT / SHARED CAPSULE SHAPE',574,74,20)
        q.sheet_image(old,20,110,510,490)
        q.sheet_image(OUT/'micro_missile_pack/hero.png',570,110,510,490)
        q.sheet_text('8-sided / length 0.80 / diameter 0.38 / flat front / stepped body',24,614,20)
        q.sheet_save(OUT/'micro_missile_pack/capsule-comparison.png')


def verify():
    reports=[]
    errors=[]
    staged=[]
    for item in ITEMS:
        folder=OUT/item
        saved=json.loads((folder/'validation.json').read_text())
        if not saved['passed'] or saved['sourceSha256']!=q.digest(folder/(item+'.blend')):
            errors.append(item+' source validation')
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.gltf(filepath=str(folder/(item+'.glb')))
        points=d.all_points(d.geometry())
        bounds=d.bounds(points)
        error=max(abs(a-b) for a,b in zip(bounds,saved['boundsBlender']))
        if error>.0001:
            errors.append(item+' glb bounds')
        definition=json.loads((ASSETS/'items'/(item+'.json')).read_text())
        model=json.loads((ASSETS/'models/item'/(item+'.json')).read_text())
        if definition['model']['model']!='morrowgear_drone:item/'+item or model['textures']['layer0']!='morrowgear_drone:item/'+item:
            errors.append(item+' model reference')
        candidate=folder/'staged-runtime.png'
        resource=candidate if candidate.exists() else ASSETS/'textures/item'/(item+'.png')
        if candidate.exists():
            staged.append((candidate,ASSETS/'textures/item'/(item+'.png')))
        image=bpy.data.images.load(str(resource))
        exact=q.digest(resource)==q.digest(folder/'icon-128.png')
        if not exact or list(image.size)!=[128,128] or image.channels!=4:
            errors.append(item+' resource provenance')
        report={'id':item,'passed':saved['passed'] and error<.0001 and exact,
                'sourceSha256':saved['sourceSha256'],'glbBoundsError':error,
                'resourceSha256':q.digest(resource),'resourceEqualsCanonical128':exact,
                'resourceSize':list(image.size),'resourceRGBA':image.channels==4,
                'triangles':saved['triangles'],'icons':saved['icons']}
        if item=='micro_missile_pack':
            graph=bpy.context.evaluated_depsgraph_get()
            tips=[]
            for x,z in ((-.53,1.37),(0,1.37),(.53,1.37),(-.29,.55),(.29,.55)):
                hit,loc,_,_,obj,_=bpy.context.scene.ray_cast(graph,Vector((x,-1,z)),Vector((0,1,0)))
                tip=bool(hit and abs(loc.y-(CAPSULE_OFFSET-.40))<.0001 and obj.name.startswith('Missile_'))
                tips.append({'xz':[x,z],'hit':tip,'frontY':loc.y if hit else None,'object':obj.name if obj else None})
            report['fiveCapsuleFronts']=tips
            report['capsuleProfile']=saved.get('capsuleProfile')
            if not report['capsuleProfile'] or report['capsuleProfile']['sourceSha256']!=q.digest(CAPSULE_SOURCE):
                errors.append('capsule source drift')
            if not all(tip['hit'] for tip in tips):
                errors.append('five missiles')
        reports.append(report)
    q.dump(OUT/'verification.json',{'passed':not errors,'errors':errors,'models':reports,
           'referenceSha256':q.digest(OUT/'reference.png'),'scope':'Native GLB readback and model-derived RGBA resources; no game launch'})
    if errors:
        raise RuntimeError(errors)
    published=[]
    for candidate,destination in staged:
        temporary=destination.with_suffix('.verified-tmp')
        shutil.copyfile(candidate,temporary)
        os.replace(temporary,destination)
        published.append({'path':str(destination.relative_to(ASSETS)),'sha256':q.digest(destination)})
    q.dump(OUT/'published-assets.json',{'passed':True,'assets':published})


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--only',choices=(*ITEMS,'sheets','verify','all','capsule'),default='all')
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    for item in ITEMS:
        if args.only in ('all',item) or args.only=='capsule' and item=='micro_missile_pack':
            build(item)
    if args.only=='capsule':
        sheets(('micro_missile_pack',))
        verify()
    if args.only in ('all','sheets'):
        sheets()
    if args.only in ('all','verify'):
        verify()
