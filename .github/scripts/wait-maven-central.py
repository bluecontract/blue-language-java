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


class StatusError(ValueError):
    """Only fixed, secret-free diagnostic messages may enter this exception."""


def failure_message(error):
    if isinstance(error, StatusError):
        detail = str(error)
    elif isinstance(error, HTTPError):
        detail = 'status HTTP '+str(error.code)
    elif isinstance(error, json.JSONDecodeError):
        detail = 'invalid JSON response'
    elif isinstance(error, KeyError) and error.args[0] in (
            'JRELEASER_MAVENCENTRAL_USERNAME', 'JRELEASER_MAVENCENTRAL_PASSWORD'):
        detail = 'missing environment variable '+error.args[0]
    elif isinstance(error, TimeoutError):
        detail = 'timed out waiting for PUBLISHED status'
    elif isinstance(error, OSError):
        detail = 'local file or network error'
    else:
        detail = 'invalid input or status response'
    return 'Maven Central publication not confirmed: '+detail+'.'


def deployment_id(text):
    values = [line.split('=', 1)[1].strip() for line in text.splitlines()
              if line.split('=', 1)[0].strip() == KEY and '=' in line]
    if len(values) != 1 or not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', values[0]):
        raise StatusError('missing, duplicate or invalid Sonatype deployment ID')
    return values[0]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise StatusError('status redirect refused')


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
                raise StatusError('status HTTP '+str(error.code)) from None
        except (URLError, TimeoutError, OSError):
            pass
        else:
            if not isinstance(row, dict):
                raise StatusError('status response must be an object')
            if 'deploymentId' not in row:
                raise StatusError('missing deployment ID in status response')
            if row['deploymentId'] != deployment:
                raise StatusError('deployment ID mismatch in status response')
            # Sonatype can retain error metadata even after publication. Like
            # JReleaser, decide readiness from the matching deployment's state.
            state = row.get('deploymentState')
            if state == 'PUBLISHED':
                return dict(deploymentId=deployment, deploymentState=state)
            if state == 'FAILED':
                raise StatusError('deployment state FAILED')
            if state not in ['PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING']:
                raise StatusError('unknown deployment state')
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
    for name, value in [('JRELEASER_MAVENCENTRAL_USERNAME', username), ('JRELEASER_MAVENCENTRAL_PASSWORD', password)]:
        if not value:
            raise StatusError('empty environment variable '+name)
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
    except (ValueError, KeyError, OSError, TimeoutError) as error:
        # Do not print HTTP response bodies, credentials or exception internals.
        parser.exit(1, failure_message(error)+'\n')
