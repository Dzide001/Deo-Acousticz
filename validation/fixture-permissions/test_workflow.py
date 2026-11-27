#!/usr/bin/env python3
"""Exercise publication guards in an isolated Git index, using only authored data."""
import hashlib,json,shutil,subprocess,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class PublicationGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        for pkg in ('cf1-reference','clf-handedness','clf-magnitude','fixture-permissions'):
            dest=self.root/'validation'/pkg;dest.mkdir(parents=True)
            names=('generate.py','audit.py','prepare_external.py','external-sources.json') if pkg=='fixture-permissions' else ('provenance.json','results/reference.json')
            for name in names:
                p=dest/name;p.parent.mkdir(exist_ok=True);shutil.copy2(ROOT/'validation'/pkg/name,p)
            if pkg!='fixture-permissions':
                (dest/'fixtures').mkdir()
                for f in (ROOT/'validation'/pkg/'fixtures').glob('*.tab'):shutil.copy2(f,dest/'fixtures'/f.name)
        shutil.copy2(ROOT/'.gitignore',self.root/'.gitignore')
        self.git('init','-q');self.run_script('generate.py','--prepare',success=True);self.git('add','-A')
    def git(self,*args):subprocess.run(['git',*args],cwd=self.root,check=True,capture_output=True)
    def run_script(self,name,*args,success):
        r=subprocess.run([sys.executable,str(self.root/'validation/fixture-permissions'/name),*args],cwd=self.root,capture_output=True,text=True)
        self.assertEqual(success,r.returncode==0,r.stdout+r.stderr);return r
    def test_clean_index_passes_and_tracked_binary_is_rejected(self):
        self.run_script('audit.py','--index',success=True)
        (self.root/'speaker.CF2').write_bytes(b'authored negative control')
        self.git('add','speaker.CF2');self.run_script('audit.py','--index',success=False)
    def test_renamed_known_source_bytes_are_rejected(self):
        payload=b'authored stand-in for a permission-blocked source'
        manifest=self.root/'validation/fixture-permissions/external-sources.json';data=json.loads(manifest.read_text())
        data['negativeControlSha256']=hashlib.sha256(payload).hexdigest();manifest.write_text(json.dumps(data))
        (self.root/'renamed.dat').write_bytes(payload);self.git('add','-A')
        self.run_script('audit.py','--index',success=False)
    def test_external_preparation_cannot_write_inside_repository(self):
        dest=self.root/'private-but-public-location'
        self.run_script('prepare_external.py','--extracted-dir',str(self.root/'absent'),'--output',str(dest),success=False)
        self.assertFalse(dest.exists())
if __name__=='__main__':unittest.main()
