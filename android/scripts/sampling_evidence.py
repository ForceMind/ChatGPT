"""Keep the first fatal CI error distinct from secondary cleanup failures."""
import base64
import json
import subprocess
import traceback
from pathlib import Path


def error_details(error):
    data = {'type': type(error).__name__, 'message': str(error),
            'traceback': ''.join(traceback.format_exception(type(error), error, error.__traceback__))}
    if isinstance(error, (subprocess.CalledProcessError, subprocess.TimeoutExpired)):
        data.update(command=error.cmd, returnCode=getattr(error, 'returncode', None))
        if isinstance(error, subprocess.TimeoutExpired):
            data['timeoutSeconds'] = error.timeout
        for name in ('stdout', 'stderr'):
            value = getattr(error, name)
            if isinstance(value, bytes):
                data[name + 'Base64'] = base64.b64encode(value).decode('ascii')
                value = value.decode('utf-8', errors='replace')
            data[name] = value
    return data


def capture_failure(folder, error):
    folder = Path(folder)
    details = error_details(error)
    try:
        with (folder / 'first-terminal-error.json').open('x') as output:
            json.dump(details, output, indent=2)
    except FileExistsError:
        pass
    command = folder / 'command-failure.json'
    if command.exists():
        try:
            with (folder / 'first-command-failure.json').open('xb') as output:
                output.write(command.read_bytes())
        except FileExistsError:
            pass
    return details


def finalize_report(folder, report, cleanup, primary_error):
    try:
        cleanup()
    except Exception as error:
        report['complete'] = False
        report['cleanupError'] = error_details(error)
        if primary_error is None:
            capture_failure(folder, error)
            raise
        primary_error.add_note('Cleanup also failed: ' + str(error))
    finally:
        (Path(folder) / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
