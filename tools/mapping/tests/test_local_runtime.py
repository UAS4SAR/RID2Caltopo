import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from tools.mapping.local_runtime import station_ready


class LocalRuntimeTests(unittest.TestCase):
    def test_ready_receipt_matches_process_address_pairing_and_boot(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root)
            value={"pid":1234,"address":"127.0.0.1","port":8765,"processingAvailable":True,
                "pairingHash":hashlib.sha256(b"test-pairing").hexdigest(),"bootNonce":"fresh-launch"}
            (root/".station-runtime.json").write_text(json.dumps(value))
            self.assertTrue(station_ready(root,1234,"127.0.0.1","test-pairing","fresh-launch"))
            for pid,address,code,nonce in [(1235,"127.0.0.1","test-pairing","fresh-launch"),
                    (1234,"192.168.1.1","test-pairing","fresh-launch"),
                    (1234,"127.0.0.1","wrong-code","fresh-launch"),
                    (1234,"127.0.0.1","test-pairing","previous-launch")]:
                self.assertFalse(station_ready(root,pid,address,code,nonce))

    def test_child_emits_private_ready_receipt_after_binding(self):
        with tempfile.TemporaryDirectory() as root:
            environment=dict(os.environ,R2C_MAPPING_PAIRING_CODE="test-pairing-fixture",R2C_MAPPING_BOOT_NONCE="test-child-launch")
            process=subprocess.Popen([sys.executable,"-m","tools.mapping.server","--root",root,"--port","0",
                "--nodeodm","http://127.0.0.1:3000"],env=environment,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            try:
                receipt=Path(root)/".station-runtime.json"
                deadline=time.monotonic()+10
                while not receipt.is_file() and process.poll() is None and time.monotonic()<deadline:time.sleep(.1)
                self.assertTrue(receipt.is_file(),"Child did not report readiness")
                value=json.loads(receipt.read_text())
                self.assertEqual(value["pid"],process.pid)
                self.assertEqual(value["address"],"127.0.0.1")
                self.assertGreater(value["port"],0)
                self.assertEqual(value["bootNonce"],"test-child-launch")
                self.assertTrue(value["processingAvailable"])
                self.assertNotIn("test-pairing-fixture",receipt.read_text())
            finally:
                process.terminate();process.wait(timeout=5)
