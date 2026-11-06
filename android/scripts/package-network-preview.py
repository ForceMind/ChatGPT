"""CI build receipt for a separately installable human-tested package. Never launches the app."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from android_version import load_version, assert_apk_version, apk_filename

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert not Path('android/keystore.properties').exists(), 'No production signing inputs allowed'
root=Path('android/app/build')
(root/'reports').mkdir(parents=True,exist_ok=True)
beta=os.environ.get('STUDIO_BETA_MODE')=='1'
kind='networkBeta' if beta else 'networkPreview'
apk=root/('outputs/apk/'+kind+'/app-'+kind+'.apk')
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
def run(*args):
    return subprocess.check_output([str(x) for x in args],text=True)
badging=run(tools/'aapt2','dump','badging',apk)
manifest=run(tools/'aapt2','dump','xmltree',apk,'--file','AndroidManifest.xml')
(root/'reports/preview-badging.txt').write_text(badging)
(root/'reports/preview-manifest.txt').write_text(manifest)
print(badging,flush=True)
identity='com.forcemind.chatgpt.profiles.networkbeta' if beta else 'com.forcemind.chatgpt.profiles.networkpreview'
label='ChatGPT Studio Beta' if beta else 'ChatGPT Studio 测试版'
assert "name='"+identity+"'" in badging
assert label in badging
sdk=re.search(r"^minSdkVersion:'([0-9]+)'$",badging,re.M)
xml_sdk=re.search(r'android:minSdkVersion[^\n]*=([0-9]+)$',manifest,re.M)
assert sdk and xml_sdk and int(sdk[1])==int(xml_sdk[1])==28, 'APK minSdk must remain exactly28'
for forbidden in ['MANAGE_DOCUMENTS','BIND_DEVICE_ADMIN','REORDER_TASKS','UploadProbeActivity','EnvironmentProbeActivity','RestartProbeApplication','NetworkDocumentsProvider','NetworkProxyAdminReceiver']:
    assert forbidden not in manifest,forbidden
for flag in ['android:debuggable','android:testOnly']:
    for line in manifest.splitlines():
        if flag in line:assert '0xffffffff' not in line and 'true' not in line
assert identity+'.handoff' in manifest
config=root/('generated/source/buildConfig/'+kind+'/com/forcemind/chatgpt/profiles/BuildConfig.java')
source=config.read_text()
version_metadata=None
if beta and 'ENVIRONMENT_CLIENT = true' in source:
    version_metadata=load_version()
    assert version_metadata['sourceSHA']==os.environ['GITHUB_SHA'], 'Full App packaging must use its actual source SHA'
    assert_apk_version(badging,version_metadata,source)
assert 'NETWORK_TEST = false' in source
assert 'NETWORK_HOME_URL = ""' in source and 'NETWORK_PROBE_URL = ""' in source
with zipfile.ZipFile(apk) as archive:
    names=archive.namelist()
    assert not any('studio_network_ci_ca' in n or 'secure-fixture' in n for n in names)
    assert not any(b'GeneratedNetworkTrust' in archive.read(n) for n in names if n.endswith('.dex'))
    native=[n for n in names if n.endswith('.so')]
    assert native and all('/arm64-v8a/' in n for n in native)
    import struct
    for name in native:
        data=archive.read(name);assert data[:6]==b'\x7fELF\x02\x01'
        offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
        for i in range(count):
            at=offset+i*size
            if struct.unpack_from('<I',data,at)[0]==1:
                file_at,address=struct.unpack_from('<QQ',data,at+8);alignment=struct.unpack_from('<Q',data,at+48)[0]
                assert alignment>=16384 and (address-file_at)%16384==0
subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(apk)],check=True,capture_output=True)
cert=run(tools/'apksigner','verify','--verbose','--print-certs',apk)
assert 'Number of signers: 1' in cert
version=re.search(r"versionName='([^']+)'",badging)[1]
folder=root/('network-beta' if beta else 'network-preview');folder.mkdir(exist_ok=True)
name=apk_filename(version) if version_metadata is not None else 'ChatGPT-Studio-'+version+'-android-arm64.apk'
shutil.copyfile(apk,folder/name)
signer=re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)',cert)[1].lower()
if beta:
    expected=json.loads(Path('docs/signing/android-network-beta.json').read_text())
    assert identity==expected['package'] and signer==expected['certificateSHA256']
code=int(re.search(r"versionCode='(\d+)'",badging)[1])
if beta:
    if version_metadata is not None:
        assert code==version_metadata['versionCode']
        if 'BETA_VERSION_CODE' in os.environ:assert code==int(os.environ['BETA_VERSION_CODE'])
    else:assert code==int(os.environ['BETA_VERSION_CODE'])
receipt={'sourceSHA':os.environ['GITHUB_SHA'],'package':identity,'displayName':label,'versionName':version,'versionCode':code,'file':name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signerSHA256':signer,'signing':'fixed dedicated beta key; not production' if beta else 'temporary CI test key; not production; future overlay upgrades not guaranteed','ciTrustIncluded':False,'syntheticNodesIncluded':False,'debuggable':False,'testOnly':False,'abi':['arm64-v8a'],'minAndroid':9,'alignment16KiB':True,'phoneInstalled':False,'realServiceTested':False,'publicRelease':False}
if version_metadata is not None:
    receipt['versionMetadata']=version_metadata
    (folder/'android-version.json').write_text(json.dumps(version_metadata,indent=2)+'\n')
(folder/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+'\n')
(folder/'SHA256SUMS.txt').write_text(receipt['sha256']+'  '+name+'\n')
(folder/'INSTALL.md').write_text('''# ChatGPT Studio 独立网络测试版

下载APK后在Android9及以上的arm64手机打开安装；根据系统提示允许对应下载器安装未知应用。

应用名称为「ChatGPT Studio 测试版」，包名独立，和正式版并存。账号、登录、资产、恢复记录和网络配置均使用新应用自己的空间，不会自动迁入正式版数据；保留正式版，不要卸载它。

启动后默认使用系统网络。在「更多 → 网络」添加自己的VLESS地址，选择线路并启用。首版只支持RAW/TCP none、TLS、REALITY，不提供免费节点或订阅；真实节点由你自行配置，无需向开发者发送节点密钥或账号密码。网页和原生文件下载共用所选线路，账号空间独立但共享线路。

本包使用临时CI测试签名，无测试CA、合成节点或诊断入口，非调试构建。后续测试包签名可能不同，不保证覆盖升级；这不影响正式版。卸载本测试版会删除本测试版自己的登录和数据。

请自行验收真实登录、线路切换、网页/文件、关闭恢复和日常体验。CI仅构建、单元测试和安装包静态校验，没有访问真实ChatGPT、测试真实节点或安装你的手机。只分发Actions产物，未创建公开Release。
''')
if beta:
    (folder/'INSTALL.md').write_text('''# ChatGPT Studio Beta 固定签名候选

Android9及以上arm64，独立包名com.forcemind.chatgpt.profiles.networkbeta，与当前临时签名测试版和正式版并存。请保留它们，不要卸载以迁移数据；本Beta需重新登录、自行配置节点，不自动复制旧应用Cookie或密钥。

使用测试专用固定签名，后续同包名同证书、更高版本号的候选可按Android正常覆盖安装流程更新。本批仅验证合成原生账号、加密载荷、资产、偏好和线路配置跨版本保存，未启动WebView、验证真实Cookie、个人手机或真实节点。

更多→网络添加自己的RAW/TCP none、TLS或REALITY VLESS配置，不提供免费节点。首轮交付仅Actions候选，不自动发布。证书公钥和SHA256见docs/signing/android-network-beta.json，私钥不在包或仓库中。
''')
shutil.copyfile('LICENSE',folder/'LICENSE')
shutil.copyfile('android/app/libs/dependencies.json',folder/'network-core-dependencies.json')
shutil.copytree('android/app/libs/licenses',folder/'licenses',dirs_exist_ok=True)
print(json.dumps(receipt,ensure_ascii=False))
