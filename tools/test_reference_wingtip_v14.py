"""Art geometry checks. These do not establish reference likeness or flight."""
import json
import math
import unittest
from pathlib import Path

from PIL import Image
import reference_wingtip_sections_v14 as s
import check_reference_airframe_v13 as diagnostic

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-wingtip-v14'
VIEWS=('hero','top','bottom','front','rear','left','right','underside','rear-oblique','clay')


class WingtipGeometryTest(unittest.TestCase):
    def test_symmetric_bounded_positive_surface(self):
        for i in range(120):
            x=4.8366*i/120;lo,hi=s.chord_bounds(x)
            for j in range(100):
                y=lo+(hi-lo)*j/99
                upper,lower=s.skin(x,y),s.skin(x,y,False)
                self.assertTrue(math.isfinite(upper) and math.isfinite(lower))
                self.assertGreater(upper-lower,0)
                self.assertGreater(lower,-.5);self.assertLess(upper,.8)
                self.assertEqual(upper,s.skin(-x,y))

    def test_outer_deck_continuity(self):
        for y in (.16,.4,.7,1.):
            t=(y-s.BOUNDARY[4][1])/(s.BOUNDARY[7][1]-s.BOUNDARY[4][1])
            root=s.BOUNDARY[4][0]*(1-t)+s.BOUNDARY[7][0]*t
            for x in (2.88,root):
                for top in (True,False):
                    self.assertLess(abs(s.skin(x-.0001,y,top)-s.skin(x+.0001,y,top)),.001)

    def test_four_corner_tip_has_no_internal_diagonal_fold(self):
        for i in range(4,8):
            x,y,_=s.BOUNDARY[i]
            self.assertAlmostEqual(s.skin(x,y),s.tip_surface(x,y)[0],places=5)
        self.assertGreater(math.dist(s.BOUNDARY[4],s.BOUNDARY[7]),.9)

    def test_actual_mesh_symmetry_and_hull_integrity(self):
        r=json.loads((OUT/'validation.json').read_text())
        self.assertTrue(r['symmetryPass']);self.assertLess(r['mirrorError'],1e-5)
        r=json.loads((OUT/'mechanics.json').read_text())
        self.assertTrue(r['passed']);self.assertEqual(r['nonmanifoldEdgesAfterWelding'],0)

    def test_actual_triangle_sampling(self):
        r=json.loads((OUT/'section-diagnostics.json').read_text())
        self.assertGreater(r['sampledTriangleCount'],10000)
        self.assertLess(r['maximumSampledTriangleSurfaceError'],.02)

    def test_comparison_camera_preserved(self):
        before=json.loads((ROOT/'docs/design/reference-airframe-v11/camera-fit.json').read_text())
        after=json.loads((OUT/'design-parameters.json').read_text())
        self.assertEqual(before['cameraParameters'],after['cameraParameters'])

    def test_all_views_nonblank_current_and_exported(self):
        modified=max(p.stat().st_mtime for p in (ROOT/'tools/reference_wingtip_sections_v14.py',ROOT/'tools/drone-design/blender/build_reference_wingtip_v14.py'))
        for name in VIEWS:
            path=OUT/(name+'.png');im=Image.open(path)
            self.assertEqual(im.size,(1681,936));self.assertIsNotNone(im.getchannel('A').getbbox())
            self.assertGreater(path.stat().st_mtime,modified)
        for name in ('airframe.blend','airframe.glb'):
            self.assertGreater((OUT/name).stat().st_size,1000)
            self.assertGreater((OUT/name).stat().st_mtime,modified)

    def test_regional_silhouettes_not_worsened(self):
        r=json.loads((OUT/'comparison.json').read_text())
        for region in r['regions'].values():self.assertGreater(region['afterIoU'],region['beforeIoU'])

    def test_no_false_approval_or_flight_claim(self):
        r=json.loads((OUT/'validation.json').read_text())
        for name in ('referenceFidelityApproved','aerodynamicSimulationPerformed','gameVerified'):
            self.assertFalse(r[name])


if __name__=='__main__':
    diagnostic.OUT=OUT;diagnostic.s=s;diagnostic.main()
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(WingtipGeometryTest))
    (OUT/'geometry-tests.json').write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful(),'scope':'Mesh, sections, exports and comparison regressions; not visual approval or aerodynamics.'},indent=2))
    raise SystemExit(0 if result.wasSuccessful() else 1)
