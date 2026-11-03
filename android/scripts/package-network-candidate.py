"""Package an unsigned arm64 review candidate, never publish or sign it."""
import hashlib,json,os,shutil
from pathlib import Path
assert os.environ.get('GITHUB_ACTIONS')=='true'
root=Path('android/app/build');metadata=json.loads((root/'reports/network-candidate.json').read_text());assert metadata['sourceSHA']==os.environ['GITHUB_SHA'] and metadata['productionSigning'] is False
apk=root/'outputs/apk/release/app-release-unsigned.apk';expected=metadata['apk']['release'];assert hashlib.sha256(apk.read_bytes()).hexdigest()==expected['sha256']
folder=root/'network-candidate';folder.mkdir(exist_ok=True);name='ChatGPT-Studio-Network-Candidate-'+os.environ['GITHUB_SHA'][:8]+'-android-arm64-UNSIGNED.apk';shutil.copyfile(apk,folder/name)
shutil.copyfile('LICENSE',folder/'LICENSE-ChatGPT-Studio');shutil.copyfile('android/app/libs/dependencies.json',folder/'network-core-dependencies.json');shutil.copytree('android/app/libs/licenses',folder/'licenses',dirs_exist_ok=True)
(folder/'NETWORK-THIRD-PARTY-NOTICES.md').write_text('The fixed Xray-core and Go/mobile dependency versions are recorded in network-core-dependencies.json. Original dependency licenses are in licenses/. Third-party licenses remain separate from the project license. The network core source is pinned by android/network-poc/corebridge/go.mod and the CI core manifest.\n')
(folder/'README.md').write_text('''# Android 网络接入候选

这是正式客户端代码的未签名 arm64 审阅产物，不是公开升级版。没有使用生产签名，不要卸载现有应用来安装它。

包名和原账号/恢复格式保持不变，安装升级需后续使用原签名。本批没有操作个人设备、真实账号或真实 ChatGPT；验收范围与限制以随源码提交的 docs/benchmarks/android-network-integration.json 为准。

构建只在 GitHub Actions 完成。候选没有 CI CA、管理角色、测试 Provider 或合成账号数据；独立 CI 测试包不能作为正式安装包。
''')
info=dict(metadata,artifactFile=name,publicRelease=False,phoneInstalled=False)
(folder/'candidate.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'file':name,'bytes':expected['bytes'],'sha256':expected['sha256'],'signed':False,'published':False}))
