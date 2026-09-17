#!/usr/bin/env python3
"""Wait for one submitted deployment. This client only calls the status API."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

KEY = 'deployMavenCentralSonatypeDeploymentId'
URL = 'https://central.sonatype.com/api/v1/publisher/status'


def deployment_id(text):
    values = [line.split('=', 1)[1].strip() for line in text.splitlines()
              if line.split('=', 1)[0].strip() == KEY and '=' in line]
    if len(values) != 1 or not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', values[0]):
        raise ValueError('Missing, duplicate or invalid Sonatype deployment ID')
    return values[0]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Maven Central status redirect refused')


def status(deployment, token, timeout):
    request = Request(URL+'?id='+deployment, data=b'', method='POST',
                      headers={'Authorization':'Bearer '+token, 'Content-Type':'application/json'})
    with build_opener(NoRedirect()).open(request, timeout=timeout) as response:
        return json.load(response)


def wait(deployment, token, *, request=status, clock=time.monotonic, sleep=time.sleep, timeout=2000):
    deadline = clock()+timeout
    while clock() < deadline:
        try:
            row = request(deployment, token, min(30, max(.001, deadline-clock())))
        except HTTPError as error:
            if error.code != 429 and not 500 <= error.code < 600:
                raise ValueError('Maven Central status HTTP '+str(error.code)) from None
        except (URLError, TimeoutError, OSError):
            pass
        else:
            if not isinstance(row, dict) or row.get('deploymentId') != deployment or row.get('errors'):
                raise ValueError('Invalid or failed Maven Central deployment status')
            state = row.get('deploymentState')
            if state == 'PUBLISHED':
                return dict(deploymentId=deployment, deploymentState=state)
            if state not in ['PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING']:
                raise ValueError('Maven Central deployment failed or returned unknown state')
            print('Maven Central deployment state: '+state, flush=True)
        remaining = deadline-clock()
        if remaining > 0:
            sleep(min(20, remaining))
    raise TimeoutError('Timed out waiting for Maven Central PUBLISHED status')


def run(properties, receipt):
    receipt.unlink(missing_ok=True)
    content = properties.read_bytes()
    deployment = deployment_id(content.decode('utf-8'))
    username = os.environ['JRELEASER_MAVENCENTRAL_USERNAME']
    password = os.environ['JRELEASER_MAVENCENTRAL_PASSWORD']
    if not username or not password:
        raise ValueError('Missing Maven Central credentials')
    token = base64.b64encode((username+':'+password).encode()).decode()
    row = wait(deployment, token)
    row['propertiesSha256'] = hashlib.sha256(content).hexdigest()
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps(row, indent=2)+'\n')
    print('Maven Central deployment is PUBLISHED', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('properties', type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    args = parser.parse_args()
    try:
        run(args.properties, args.receipt)
    except (ValueError, KeyError, OSError, TimeoutError):
        # Do not print HTTP response bodies, credentials or exception internals.
        parser.exit(1, 'Maven Central publication not confirmed; status check failed.\n')
