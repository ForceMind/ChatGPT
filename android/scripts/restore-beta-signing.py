"""CI-only secret materialization outside workspace/artifacts; never logs key/password."""
import base64,os
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS')=='true'
path=Path(os.environ['RUNNER_TEMP'])/'studio-beta.p12'
fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'wb') as output:output.write(base64.b64decode(os.environ['STUDIO_BETA_KEYSTORE_B64'],validate=True))
with open(os.environ['GITHUB_ENV'],'a') as env:env.write('STUDIO_BETA_KEYSTORE_PATH='+str(path)+'\n')
