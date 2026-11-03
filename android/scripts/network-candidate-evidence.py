"""Read compiled candidate identity in Actions; never runs or installs the app."""
import hashlib,json,os,subprocess,zipfile
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS')=='true'
root=Path('android/app/build');reports=root/'reports';reports.mkdir(exist_ok=True)
tool=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/aapt2'
result={'sourceSHA':os.environ['GITHUB_SHA'],'productionSigning':False,'formalDataAccessed':False,'apk':{}}
for kind,name in [('release','app-release-unsigned.apk'),('networkCi','app-networkCi.apk')]:
 apk=root/'outputs/apk'/kind/name
 assert apk.is_file(),str(apk)
 badging=subprocess.check_output([str(tool),'dump','badging',str(apk)],text=True)
 manifest=subprocess.check_output([str(tool),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],text=True)
 (reports/('network-'+kind+'-manifest.txt')).write_text(manifest)
 with zipfile.ZipFile(apk) as z:
  names=z.namelist();ci_trust=any('studio_network_ci_ca' in n for n in names)
  native=[n for n in names if n.endswith('.so')]
  generated=any(b'GeneratedNetworkTrust' in z.read(n) for n in names if n.endswith('.dex'))
 if kind=='release':
  assert "name='com.forcemind.chatgpt.profiles'" in badging
  assert not ci_trust and not generated
  assert native and all('/arm64-v8a/' in n for n in native)
  assert 'MANAGE_DOCUMENTS' not in manifest and 'BIND_DEVICE_ADMIN' not in manifest
 else:
  assert "name='com.forcemind.chatgpt.profiles.networkci'" in badging
  assert ci_trust and generated
 result['apk'][kind]={'file':name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'ciTrust':ci_trust,'generatedTrust':generated,'nativeLibraries':native,'unsigned':kind=='release'}
(reports/'network-candidate.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
