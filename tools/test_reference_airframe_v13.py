"""Regression checks for the art geometry; these are not flight tests."""
import json
import math
import unittest
from pathlib import Path

import reference_airframe_sections as s

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-airframe-v13'


class AirframeGeometryTest(unittest.TestCase):
    def test_bilateral_surface(self):
        for x in (.1,.6,.9,1.3,1.91,2.8,3.3,4.2):
            lo,hi=s.chord_bounds(x)
            for i in range(41):
                y=lo+(hi-lo)*i/40
                for upper in (True,False):self.assertEqual(s.skin(x,y,upper),s.skin(-x,y,upper))

    def test_positive_finite_thickness(self):
        for i in range(96):
            x=4.74*i/96;lo,hi=s.chord_bounds(x)
            for j in range(81):
                y=lo+(hi-lo)*j/80;depth=s.skin(x,y)-s.skin(x,y,False)
                self.assertTrue(math.isfinite(depth));self.assertGreater(depth,0)

    def test_planform_preserved(self):
        old=json.loads((ROOT/'docs/design/reference-airframe-v11/design-parameters.json').read_text())
        self.assertEqual(old['boundary'],[list(p) for p in s.BOUNDARY])

    def test_camera_not_warped_to_hide_shape_difference(self):
        old=json.loads((ROOT/'docs/design/reference-airframe-v11/camera-fit.json').read_text())
        new=json.loads((OUT/'design-parameters.json').read_text())
        self.assertEqual(old['cameraParameters'],new['cameraParameters'])

    def test_outer_wing_tapers_both_edges(self):
        for x in (3.3,3.7):
            lo,hi=s.chord_bounds(x);c=hi-lo
            depth=lambda y:s.skin(x,y)-s.skin(x,y,False)
            peak=max(depth(lo+c*i/400) for i in range(401))
            self.assertLess(depth(lo)/peak,.08);self.assertLess(depth(hi)/peak,.08)

    def test_central_afterbody_tapers(self):
        values=[s.skin(0,y)-s.skin(0,y,False) for y in (.8,1.1,1.4,1.7,1.91)]
        self.assertTrue(all(a>b for a,b in zip(values,values[1:])))

    def test_lip_clearance(self):
        for i in range(721):
            angle=math.tau*i/720;x=s.CX+s.RADIUS*math.cos(angle);y=s.CY+s.RADIUS*math.sin(angle)
            self.assertGreaterEqual(s.skin(x,y)-(s.DECK-.008),.005)

    def test_actual_mesh_symmetry(self):
        report=json.loads((OUT/'validation.json').read_text())
        self.assertTrue(report['symmetryPass']);self.assertLess(report['mirrorError'],1e-5)

    def test_actual_mesh_watertight(self):
        report=json.loads((OUT/'mechanics.json').read_text())
        self.assertEqual(report['nonmanifoldEdgesAfterWelding'],0)
        self.assertGreater(report['bodyVolume'],0)

    def test_actual_triangle_sampling(self):
        report=json.loads((OUT/'section-diagnostics.json').read_text())
        self.assertGreater(report['sampledTriangleCount'],10000)
        self.assertLess(report['maximumSampledTriangleSurfaceError'],.02)

    def test_all_views_and_exports_exist(self):
        for name in ('hero.png','front.png','rear.png','top.png','bottom.png','left.png','right.png','underside.png','clay.png','airframe.blend','airframe.glb'):
            self.assertGreater((OUT/name).stat().st_size,1000)

    def test_does_not_claim_aerodynamic_or_visual_pass(self):
        report=json.loads((OUT/'validation.json').read_text())
        self.assertFalse(report['aerodynamicSimulationPerformed'])
        self.assertFalse(report['referenceFidelityApproved']);self.assertFalse(report['gameVerified'])


if __name__=='__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(AirframeGeometryTest)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    (OUT/'geometry-tests.json').write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful(),'scope':'Art geometry and export regressions only; not CFD, real-world flight, reference approval or game testing.'},indent=2))
    raise SystemExit(0 if result.wasSuccessful() else 1)
