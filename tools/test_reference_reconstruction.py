import json
import hashlib
from datetime import datetime, timezone
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'work/visual-analysis-libs'))
import numpy as np
from PIL import Image
from reference_surface_cage import deform
from fit_reference_projection import project, silhouette_metrics

OUT=ROOT/'docs/design/reference-family-v10'
CAGE=json.loads((OUT/'analysis/reference-surface-cage.json').read_text(encoding='utf-8'))
FIT=json.loads((OUT/'analysis/reference-shape-fit.json').read_text(encoding='utf-8'))


class ReferenceReconstructionTests(unittest.TestCase):
    def test_camera_projection_matches_blender(self):
        landmarks=json.loads((OUT/'base/landmarks.json').read_text(encoding='utf-8'))
        for value in landmarks.values():
            self.assertLess(np.linalg.norm(project([value['xyz']],FIT['cameraParameters'])[0]-value['xy']),.01)

    def test_deformation_is_bilateral(self):
        points=np.random.default_rng(847).uniform([-4.8,-2.8,-.8],[4.8,2.0,1.1],(1000,3))
        reflected=points*np.array([-1,1,1])
        a=deform(points,CAGE['values'],CAGE['fanY'])*np.array([-1,1,1])
        b=deform(reflected,CAGE['values'],CAGE['fanY'])
        self.assertLess(np.max(np.abs(a-b)),1e-10)

    def test_centerline_is_continuous(self):
        for y in np.linspace(-2.7,2,30):
            points=np.array([[-1e-6,y,.2],[1e-6,y,.2]])
            result=deform(points,CAGE['values'],CAGE['fanY'])
            self.assertLess(np.linalg.norm(result[1]-result[0]),1e-5)

    def test_rotor_volume_is_pinned(self):
        points=np.array([[sign*(1.91+r*np.cos(a)),CAGE['fanY']+r*np.sin(a),.3]
                         for sign in (-1,1) for r in (0,.45,.89,1.0) for a in np.linspace(0,2*np.pi,32)])
        self.assertLess(np.max(np.abs(deform(points,CAGE['values'],CAGE['fanY'])-points)),1e-10)

    def test_deformation_does_not_fold_space(self):
        points=np.array([[x,y,.2] for x in np.linspace(-4.8,4.8,70) for y in np.linspace(-2.7,2,50)])
        eps=1e-5;base=deform(points,CAGE['values'],CAGE['fanY']);jac=[]
        for axis in range(3):
            offset=np.zeros(3);offset[axis]=eps
            jac.append((deform(points+offset,CAGE['values'],CAGE['fanY'])-base)/eps)
        determinants=np.linalg.det(np.stack(jac,axis=-1))
        self.assertGreater(float(determinants.min()),.15)

    def test_zero_deformation_is_identity(self):
        points=np.random.default_rng(42).normal(size=(50,3))
        np.testing.assert_array_equal(points,deform(points,[0]*18,CAGE['fanY']))

    def test_actual_model_symmetry(self):
        data=json.loads((OUT/'base/validation.json').read_text(encoding='utf-8'))
        self.assertTrue(data['symmetryPass']);self.assertLess(data['mirrorError'],1e-5)

    def test_fidelity_is_not_falsely_approved(self):
        for path in (OUT/'base/validation.json',OUT/'analysis/reference-shape-fit.json'):
            data=json.loads(path.read_text(encoding='utf-8'))
            self.assertFalse(data.get('referenceFidelityApproved',data.get('fidelityApproved')))

    def test_numeric_gate_has_not_been_relaxed(self):
        data=json.loads((OUT/'analysis/latest-comparison.json').read_text(encoding='utf-8'))
        self.assertEqual(data['gate'],{'silhouetteIoU':.95,'meanContourErrorPx':8,'landmarkRmsErrorPx':12})
        expected=data['silhouetteIoU']>=.95 and data['meanContourErrorPx']<=8 and data['landmarkRmsErrorPx']<=12
        self.assertEqual(data['passed'],expected)

    def test_comparison_uses_current_inputs(self):
        data=json.loads((OUT/'analysis/latest-comparison.json').read_text(encoding='utf-8'))
        self.assertGreaterEqual(len(data['inputSha256']),7)
        for name,expected in data['inputSha256'].items():
            self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(),expected,name)

    def test_silhouette_identity_and_translation(self):
        reference=np.zeros((100,100),bool);reference[20:60,20:60]=True
        same=silhouette_metrics(reference,reference)
        self.assertEqual(same['silhouetteIoU'],1);self.assertEqual(same['meanContourErrorPx'],0)
        shifted=np.zeros_like(reference);shifted[20:60,30:70]=True
        result=silhouette_metrics(reference,shifted)
        self.assertAlmostEqual(result['silhouetteIoU'],.6)
        self.assertGreater(result['meanContourErrorPx'],0)

    def test_invalid_silhouettes_are_rejected(self):
        with self.assertRaises(ValueError):silhouette_metrics(np.zeros((3,3)),np.zeros((4,4)))
        with self.assertRaises(ValueError):silhouette_metrics(np.zeros((3,3)),np.zeros((3,3)))

    def test_full_model_and_hull_are_not_conflated(self):
        data=json.loads((OUT/'analysis/latest-comparison.json').read_text(encoding='utf-8'))
        self.assertIn('All rendered parts',data['fullModelSilhouette']['scope'])
        self.assertIn('hidden edges are not drawn',data['visibleWireVisibility'])
        self.assertIn('not independent validation',data['landmarkCaveat'])

    def test_mesh_is_finite(self):
        mesh=json.loads((OUT/'base/hull-mesh.json').read_text(encoding='utf-8'))
        vertices=np.array(mesh['vertices']);self.assertTrue(np.isfinite(vertices).all())
        self.assertTrue(all(len(face)>=3 and min(face)>=0 and max(face)<len(vertices) for face in mesh['faces']))

    def test_views_are_real_outputs(self):
        for name in ('hero','front','rear','top','bottom','left','right','underside','clay','visible-wire'):
            im=Image.open(OUT/'base'/f'{name}.png').convert('RGBA')
            self.assertGreater(np.count_nonzero(np.array(im)[:,:,3]),1000)


if __name__=='__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceReconstructionTests)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    report={'completedUtc':datetime.now(timezone.utc).isoformat(),
            'scope':'Diagnostic tooling and limited structural checks only; not visual fidelity, role mechanisms or Minecraft gameplay.',
            'testsRun':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),
            'passed':result.wasSuccessful(),
            'comparisonSha256':hashlib.sha256((OUT/'analysis/latest-comparison.json').read_bytes()).hexdigest()}
    (OUT/'analysis/diagnostic-tests.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    sys.exit(0 if result.wasSuccessful() else 1)
