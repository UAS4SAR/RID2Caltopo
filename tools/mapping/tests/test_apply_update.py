import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from tools.mapping.apply_update import apply_update


class UpdateTests(unittest.TestCase):
    def test_source_backup_stop_copy_start_preserves_mission_and_pairing(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);project=root/"project";data=root/"data"
            old=project/"tools/mapping/local_runtime.py";old.parent.mkdir(parents=True);old.write_text("old source")
            python=project/".venv-mapping/bin/python";python.parent.mkdir(parents=True);python.write_text("fixture");python.chmod(0o755)
            state=data/"mission/state.json";state.parent.mkdir(parents=True);state.write_text(json.dumps({"status":"collecting"}))
            pairing=data/"station-pairing-code";pairing.write_text("unchanged-code")
            archive=root/"update.pyz"
            with zipfile.ZipFile(archive,"w") as z:z.writestr("tools/mapping/local_runtime.py","new source")
            actions=[]
            def runner(command,**kwargs):
                actions.append(command[-1])
                self.assertEqual(old.read_text(),"old source" if command[-1]=="stop" else "new source")
            apply_update(project,archive,data,runner)
            self.assertEqual(actions,["stop","start"])
            self.assertEqual(pairing.read_text(),"unchanged-code")
            self.assertEqual(json.loads(state.read_text())["status"],"collecting")
            self.assertIn("**/.dji-cloud-credentials.json*",(project/".gitignore").read_text().splitlines())
            with zipfile.ZipFile(next((data/"update-backups").glob("*.zip"))) as z:
                self.assertEqual(z.read("tools/mapping/local_runtime.py"),b"old source")

    def test_active_processing_prevents_source_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);project=root/"project";data=root/"data"
            python=project/".venv-mapping/bin/python";python.parent.mkdir(parents=True);python.write_text("fixture");python.chmod(0o755)
            state=data/"mission/state.json";state.parent.mkdir(parents=True);state.write_text(json.dumps({"status":"processing"}))
            with self.assertRaisesRegex(ValueError,"No files were changed"):
                apply_update(project,root/"nonexistent.pyz",data,lambda *args,**kwargs:self.fail("Started a service"))

    def test_credential_archive_rejected_before_backup_or_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); project=root/"project"; data=root/"data"
            python=project/".venv-mapping/bin/python"; python.parent.mkdir(parents=True)
            python.write_text("fixture"); python.chmod(0o755)
            private=project/"tools/mapping/.dji-cloud-credentials.json"
            private.parent.mkdir(parents=True); private.write_text("synthetic local secret")
            for name in (".dji-cloud-credentials.json", ".dji-cloud-credentials.json.backup"):
                archive=root/"update.pyz"
                with zipfile.ZipFile(archive,"w") as z:
                    z.writestr("tools/mapping/"+name,"synthetic packaged secret")
                with self.assertRaisesRegex(ValueError,"must not contain DJI credentials"):
                    apply_update(project,archive,data,lambda *args,**kwargs:self.fail("Started a service"))
                self.assertEqual(private.read_text(),"synthetic local secret")
                self.assertFalse((data/"update-backups").exists())
