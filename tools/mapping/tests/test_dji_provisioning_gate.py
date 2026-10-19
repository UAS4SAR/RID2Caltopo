import base64
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('gate', Path(__file__).resolve().parents[1] / 'verify_dji_provisioning.py')
gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)


class DeploymentGateTests(unittest.TestCase):
    def envelope(self, platform='android'):
        return (Path(__file__).parent/'fixtures/dji-app-registration.json').read_bytes()

    def test_app_wide_format_for_both_platforms_rejects_device_and_plaintext_input(self):
        for platform, other in [('android','apple'),('apple','android')]:
            data = self.envelope(platform)
            self.assertEqual(gate.validate(data, platform), data)
            self.assertEqual(gate.validate(data, other),data)
            with self.assertRaises(gate.InvalidProvisioning): gate.validate(b'{"type":"r2c_dji_device_provisioning","version":1}', platform)
            with self.assertRaises(gate.InvalidProvisioning): gate.validate(b'{"appId":"123456","appKey":"PRIVATE","appLicense":"PRIVATE"}', platform)
            value = json.loads(data); value['enc'] = 'not-base64'
            with self.assertRaises(gate.InvalidProvisioning): gate.validate(json.dumps(value).encode(), platform)
            with self.assertRaises(gate.InvalidProvisioning): gate.validate(b'x'*32769, platform)

    def test_actual_apk_aab_ipa_and_archive_assets_are_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            for ext, platform, entry in [('apk','android','assets/dji-registration/registration.json'),
                                        ('aab','android','base/assets/dji-registration/registration.json'),
                                        ('ipa','apple','Payload/R2C.app/dji-registration/registration.json')]:
                path = folder / ('candidate.'+ext)
                with zipfile.ZipFile(path,'w') as z: z.writestr('unrelated','not provisioning')
                with self.assertRaises(gate.InvalidProvisioning): gate.packaged(path,platform)
                data = self.envelope(platform)
                with zipfile.ZipFile(path,'w') as z: z.writestr(entry,data)
                self.assertEqual(gate.validate(gate.packaged(path,platform),platform),data)
            app = folder / 'candidate.xcarchive/Products/Applications/R2C.app'
            (app/'dji-registration').mkdir(parents=True)
            with self.assertRaises(gate.InvalidProvisioning): gate.packaged(app,'apple')
            data = self.envelope('apple'); (app/'dji-registration/registration.json').write_bytes(data)
            self.assertEqual(gate.packaged(folder/'candidate.xcarchive','apple'),data)


    def test_cli_compares_selected_input_to_final_package(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary); source = folder/'sealed.json'; source.write_bytes(self.envelope())
            apk = folder/'app.apk'
            with zipfile.ZipFile(apk,'w') as z: z.writestr('assets/dji-registration/registration.json',source.read_bytes())
            command = ['python3', str(Path(gate.__file__)), '--platform','android','--bundle',str(source),'--artifact',str(apk)]
            self.assertEqual(subprocess.run(command,capture_output=True).returncode,0)
            source.write_bytes(source.read_bytes()+b' ')
            result = subprocess.run(command,capture_output=True)
            self.assertNotEqual(result.returncode,0)
            self.assertIn(b'does not match',result.stderr)

    def test_apple_build_phase_requires_release_input_and_clears_debug_stale_asset(self):
        script = Path(__file__).resolve().parents[3]/'apple/prepare-dji-provisioning.sh'
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary);app=folder/'R2C.app';(app/'dji-registration').mkdir(parents=True)
            output=app/'dji-registration/registration.json'; output.write_bytes(self.envelope('apple'))
            env={k:v for k,v in os.environ.items() if not k.startswith('R2C_DJI_')}
            env['R2C_DJI_APP_REGISTRATION']=str(folder/'missing.json')
            env.update(TARGET_BUILD_DIR=str(folder),UNLOCALIZED_RESOURCES_FOLDER_PATH='R2C.app',CONFIGURATION='Debug')
            self.assertEqual(subprocess.run(['sh',str(script)],env=env,capture_output=True).returncode,0)
            self.assertFalse(output.exists())
            env['CONFIGURATION']='Release'
            env['R2C_DJI_APP_REGISTRATION']=str(folder/'missing.json')
            result=subprocess.run(['sh',str(script)],env=env,capture_output=True)
            self.assertNotEqual(result.returncode,0);self.assertIn(b'missing or unreadable',result.stderr)
            source=folder/'input.json';source.write_bytes(self.envelope('apple'))
            env['R2C_DJI_APP_REGISTRATION']=str(source)
            self.assertEqual(subprocess.run(['sh',str(script)],env=env,capture_output=True).returncode,0)
            self.assertEqual(output.read_bytes(),source.read_bytes())


if __name__ == '__main__': unittest.main()
