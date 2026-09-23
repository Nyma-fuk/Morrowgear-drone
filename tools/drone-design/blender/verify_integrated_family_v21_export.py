"""Reuse the animated export roundtrip contract against the V21 staging assets."""
import importlib.util
from pathlib import Path

path=Path(__file__).with_name('verify_role_family_v20_export.py')
spec=importlib.util.spec_from_file_location('roundtrip20',path)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.OUT=module.ROOT/'docs/design/integrated-family-v21'
if __name__=='__main__':module.main()
