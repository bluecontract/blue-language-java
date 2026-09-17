import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

spec = importlib.util.spec_from_file_location('wait_central', Path(__file__).with_name('wait-maven-central.py'))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)
ID = '28570f16-da32-4c14-bd2e-c1acc0782365'

class WaitCentral(unittest.TestCase):
    def test_exact_deployment_property(self):
        for text in ['', 'wrongDeploymentId='+ID, w.KEY+'=bad', w.KEY+'='+ID+'\n'+w.KEY+'='+ID]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                w.deployment_id(text)
        self.assertEqual(w.deployment_id('# comment\n'+w.KEY+'='+ID+'\n'), ID)

    def test_waits_until_published_without_publication_requests(self):
        states = iter(['VALIDATED', 'PUBLISHING', 'PUBLISHED'])
        calls = []
        def request(deployment, token, timeout):
            calls.append((deployment, token, timeout))
            return dict(deploymentId=deployment, deploymentState=next(states), errors={})
        elapsed = [0]
        result = w.wait(ID, 'secret', request=request, clock=lambda: elapsed[0],
                        sleep=lambda seconds: elapsed.__setitem__(0, elapsed[0]+seconds))
        self.assertEqual(result, dict(deploymentId=ID, deploymentState='PUBLISHED'))
        self.assertEqual(len(calls), 3)

    def test_reject_failed_malformed_wrong_id_and_errors(self):
        for row in [None, {}, dict(deploymentId=ID, deploymentState='FAILED'),
                    dict(deploymentId='wrong', deploymentState='PUBLISHED'),
                    dict(deploymentId=ID, deploymentState='UNKNOWN'),
                    dict(deploymentId=ID, deploymentState='PUBLISHED', errors={'x':['bad']})]:
            with self.subTest(row=row), self.assertRaises(ValueError):
                w.wait(ID, 'secret', request=lambda *args: row)

    def test_timeout_and_transient_errors_are_bounded(self):
        for failure in [URLError('network'), HTTPError('url', 503, 'secret', {}, None), HTTPError('url', 429, 'secret', {}, None)]:
            elapsed = [0]
            with self.subTest(failure=failure), self.assertRaises(TimeoutError):
                w.wait(ID, 'secret', request=lambda *args: (_ for _ in ()).throw(failure),
                       clock=lambda:elapsed[0], sleep=lambda seconds:elapsed.__setitem__(0, elapsed[0]+seconds), timeout=2)
            self.assertEqual(elapsed[0], 2)
        with self.assertRaisesRegex(ValueError, 'HTTP 401'):
            w.wait(ID, 'secret', request=lambda *args: (_ for _ in ()).throw(HTTPError('url',401,'secret',{},None)))

    def test_fixed_status_request_and_no_redirects(self):
        response = io.BytesIO(json.dumps(dict(deploymentId=ID, deploymentState='PUBLISHED')).encode())
        with patch.object(w, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            w.status(ID, 'secret', 3)
            req = opener.return_value.open.call_args.args[0]
            self.assertEqual(req.full_url, w.URL+'?id='+ID)
            self.assertEqual(req.method, 'POST')
            self.assertEqual(req.get_header('Authorization'), 'Bearer secret')
            self.assertEqual(opener.return_value.open.call_args.kwargs['timeout'], 3)
        with self.assertRaises(ValueError):
            w.NoRedirect().redirect_request(None,None,302,'',{},'https://untrusted.invalid')

    def test_receipt_written_only_after_published_and_bound_to_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            props, receipt = Path(tmp)/'output.properties', Path(tmp)/'receipt.json'
            props.write_text(w.KEY+'='+ID+'\n')
            receipt.write_text('stale')
            env = dict(JRELEASER_MAVENCENTRAL_USERNAME='user', JRELEASER_MAVENCENTRAL_PASSWORD='secret')
            with patch.dict(w.os.environ, env), patch.object(w, 'wait', side_effect=ValueError('failed')):
                with self.assertRaises(ValueError): w.run(props, receipt)
            self.assertFalse(receipt.exists())
            with patch.dict(w.os.environ, env), patch.object(w, 'wait', return_value=dict(deploymentId=ID,deploymentState='PUBLISHED')):
                w.run(props, receipt)
            row=json.loads(receipt.read_text())
            self.assertEqual(row['propertiesSha256'], w.hashlib.sha256(props.read_bytes()).hexdigest())
            self.assertEqual(row['deploymentState'], 'PUBLISHED')

if __name__ == '__main__': unittest.main()
