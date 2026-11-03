"""Read compiled candidate identity in Actions; never runs or installs the app."""
import hashlib,json,os,subprocess,zipfile,struct,re
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
  elf=[]
  if kind=='release':
   for member in native:
    data=z.read(member);assert data[:6]==b'\x7fELF\x02\x01';phoff=struct.unpack_from('<Q',data,32)[0];entsize,count=struct.unpack_from('<HH',data,54);segments=[]
    for i in range(count):
     offset=phoff+i*entsize
     if struct.unpack_from('<I',data,offset)[0]==1:
      fileOffset,address=struct.unpack_from('<QQ',data,offset+8);alignment=struct.unpack_from('<Q',data,offset+48)[0];assert alignment>=16384 and (address-fileOffset)%16384==0;segments.append(alignment)
    assert segments;elf.append({'library':member,'loadSegmentAlignments':segments,'supports16KiBElfAlignment':True})
  generated=any(b'GeneratedNetworkTrust' in z.read(n) for n in names if n.endswith('.dex'))
 if kind=='release':
  result['versionName']=re.search(r"versionName='([^']+)'",badging)[1];result['versionCode']=int(re.search(r"versionCode='(\d+)'",badging)[1])
  assert "name='com.forcemind.chatgpt.profiles'" in badging
  assert not ci_trust and not generated
  assert native and all('/arm64-v8a/' in n for n in native)
  assert 'MANAGE_DOCUMENTS' not in manifest and 'BIND_DEVICE_ADMIN' not in manifest and 'REORDER_TASKS' not in manifest
  subprocess.run([str(tool.with_name('zipalign')),'-c','-P','16','4',str(apk)],check=True,capture_output=True)
 else:
  assert "name='com.forcemind.chatgpt.profiles.networkci'" in badging
  assert ci_trust and generated
 result['apk'][kind]={'file':name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'ciTrust':ci_trust,'generatedTrust':generated,'nativeLibraries':native,'unsigned':kind=='release','elf':elf,'zipalign16KiBVerified':kind=='release'}
(reports/'network-candidate.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
