#!/usr/bin/env python3
"""CI-only synthetic selective-clear confirmation; not a resource or retention test."""
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
from urllib.parse import parse_qs, urlparse

spec = importlib.util.spec_from_file_location('grouped_poc', Path(__file__).with_name('grouped-environment-poc.py'))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
driver.guard_emulator()
report = {'scope': 'Targeted synthetic selective-clear test only; no long retention, popup or resource measurements.',
          'sourceSha': os.environ.get('GITHUB_SHA'), 'runId': os.environ.get('GITHUB_RUN_ID'),
          'systemBefore': {'locale': driver.adb('shell','getprop','persist.sys.locale'), 'timeZone': driver.adb('shell','getprop','persist.sys.timezone')}}
server = driver.ThreadingHTTPServer(('127.0.0.1',8765), driver.Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
driver.adb('reverse','tcp:8765','tcp:8765')
def timeout_handler(_signal,_frame):
    raise TimeoutError('Targeted selective-clear test timed out')
signal.signal(signal.SIGTERM, timeout_handler)
failure = None
try:
    print('Create four synthetic pages across three isolated accounts', flush=True)
    driver.phase = 'clear-smoke-baseline'
    baseline = driver.start_workload(driver.phase,'grouped')
    baseline['sameAccountStorageTokens'] = driver.same_account_tokens(driver.phase)
    baseline['environmentErrors'] = [{'account':r['account'],'kind':r['kind'],'tab':r['tab'],'errors':driver.validate(r)} for r in driver.records(driver.phase) if driver.validate(r)]
    baseline['passed'] = baseline['processRelationCorrect'] and baseline['sameAccountStorageTokens'] and not baseline['environmentErrors']
    report['baseline'] = baseline
    print('Clear C; wait for both clean C origins, then fresh A/B page, iframe and worker reports', flush=True)
    report['selectiveClear'] = driver.verify_selective_clear(driver.phase,baseline)
except Exception as error:
    failure = str(error)
    report['failure'] = failure
    print('Targeted selective-clear test failed:', failure, flush=True)
finally:
    try:
        report['systemAfter'] = {'locale': driver.adb('shell','getprop','persist.sys.locale'), 'timeZone': driver.adb('shell','getprop','persist.sys.timezone')}
    except (OSError,subprocess.SubprocessError) as error:
        report['systemAfter'] = {'error':str(error)}
    report['systemUnchanged'] = report['systemBefore'] == report['systemAfter']
    report['initialRequestErrors'] = []
    for request in driver.requests:
        account = request.get('account')
        if account not in ('A','B','C'): continue
        if not request['acceptLanguage'].lower().startswith(driver.expected(account)[0].lower()) or any(value != account for value in re.findall(r'(?:^|;\s*)poc-owner=([^;]*)',request['cookie'])):
            report['initialRequestErrors'].append(request)
    for account,tab in (('A','0'),('A','1'),('B','0'),('C','0')):
        for path in ('/page','/frame','/worker.js','/image.png'):
            if not any(item['phase']=='clear-smoke-baseline' and item['account']==account and urlparse(item['path']).path==path and parse_qs(urlparse(item['path']).query).get('tab')==[tab] for item in driver.requests):
                report['initialRequestErrors'].append({'account':account,'tab':tab,'missing':path})
    for name,data in (('report.json',report),('events.json',driver.records()),('requests.json',driver.requests),('heartbeats.json',driver.heartbeats)):
        (driver.OUT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2))
    try:
        driver.adb('shell','am','force-stop',driver.PACKAGE,check=False)
        driver.adb('reverse','--remove','tcp:8765',check=False)
    except (OSError,subprocess.SubprocessError) as error:
        print('Emulator cleanup unavailable:',str(error),flush=True)
    server.shutdown()
if failure or not report.get('baseline',{}).get('passed') or not report.get('selectiveClear',{}).get('passed') or not report['systemUnchanged'] or report['initialRequestErrors']:
    raise SystemExit(1)
