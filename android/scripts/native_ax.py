"""Fresh focused-window AX reads shared only by the two owned Gecko CI drivers."""
import hashlib
import json
import re
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from environment_ci_target import PACKAGE


class NativeAXMixin:
    def wait(self,description,sample,condition,timeout=75,interval=.6):
        previous=getattr(self,"_ui_read_deadline",None)
        deadline=time.monotonic()+timeout
        self._ui_read_deadline=min(previous,deadline) if previous is not None else deadline
        try:return super().wait(description,sample,condition,timeout=timeout,interval=interval)
        finally:self._ui_read_deadline=previous

    def ui(self,deadline=None):
        # A new app_process and UiAutomation connection per read; never wait for page idle.
        # A stalled Android 9 UiAutomation connection must not consume the
        # entire enclosing status wait. Fresh reads keep its original deadline.
        limits=[time.monotonic()+5]
        if deadline is not None:limits.append(deadline)
        if getattr(self,"_ui_read_deadline",None) is not None:limits.append(self._ui_read_deadline)
        deadline=min(limits)
        def budget(cap):
            remaining=deadline-time.monotonic()
            if remaining<=0:raise TimeoutError('Fresh native AX exceeded its current read-only deadline')
            return self.input_timeout(min(cap,remaining))
        request=str(uuid.uuid4())
        self.sequence+=1;receipt_path=self.output/(f'{self.sequence:04d}-ui-snapshot.json')
        receipt={'available':False,'source':'fresh-app-process-ui-automation','requestId':request}
        self.last_ui_receipt=receipt_path.name
        try:
            # The helper deadline starts after adb/app_process launch. Reserve
            # transport/exit time inside the same host deadline so its failure
            # receipt can arrive before subprocess.run kills the adb client.
            remaining=budget(5)
            if remaining<=1:raise TimeoutError('Insufficient current AX budget for a guarded helper read')
            duration=int((remaining-1)*1000)
            receipt['helperBudgetMillis']=duration
            receipt['hostDeadlineIncludesLaunchAndExit']=True
            command=['adb','shell','su','2000','env','GITHUB_ACTIONS=true',
                'CLASSPATH=/data/local/tmp/studio-poc-ui-snapshot.jar','app_process','/system/bin',
                'com.forcemind.chatgpt.pocinput.UiSnapshot',PACKAGE,request,str(duration)]
            output=self.command(command,timeout=budget(25),check=False)
            execution=json.loads((self.output/(f'{self.sequence:04d}-command.json')).read_text(encoding="utf-8"))
            result=json.loads(output);receipt['helper']={key:value for key,value in result.items() if key!='xml'}
            receipt['exitCode']=execution['exitCode']
            assert result['schema']==1 and result['provider']=='ci-readonly-active-window-ax'
            assert result['requestId']==request and result['api']==self.api and result['uid']==2000 and result['qemu'] is True
            assert result['package']==PACKAGE and type(result['pid']) is int and result['pid']>0
            assert result['waitForIdle'] is False and result['actionsPerformed']==0 and result['screenshots'] is False
            if execution['exitCode']!=0 or result['complete'] is not True or result['available'] is not True:
                receipt['errorClass']=result.get('errorClass','NativeAxUnavailable')
                return []
            assert result['phase']=='complete' and result['errorClass']==''
            assert result['connectionClass']=='android.app.UiAutomationConnection' and result['connectionFlags']==1
            assert result['constructorSignature']=='UiAutomation(Looper,IUiAutomationConnection)'
            assert result['selection']=='unique-input-focused-application' and result['serviceFlags']&64
            assert result['windowsMetadataComplete'] is True and result['windowPackagesComplete'] is True
            windows=result['windows'];assert windows and len({item['id'] for item in windows})==len(windows)
            for item in windows:
                assert type(item['id']) is int and type(item['type']) is int
                assert type(item['isFocused']) is bool and type(item['isActive']) is bool
                assert item['allowedPackage'] in ('',PACKAGE) and item['packageQueried'] is True
            focused=[item for item in windows if item['type']==1 and item['isFocused'] is True]
            assert len(focused)==1 and focused[0]==result['chosenWindow']
            chosen=result['chosenWindow']
            assert chosen['allowedPackage']==result['rootPackage']==PACKAGE and chosen['rootPresent'] is True
            assert chosen['id']==chosen['rootWindowId']==result['rootWindowId']==result['windowId']>=0
            active=result['activeRoot'];assert active and active['allowedPackage'] in ('',PACKAGE)
            assert type(active['id']) is int and type(active['rootPresent']) is bool
            assert result['startedElapsedMillis']<=result['capturedElapsedMillis']<=result['finishedElapsedMillis']
            assert 0<=result['finishedElapsedMillis']-result['startedElapsedMillis']<=duration
            xml=result['xml'];assert hashlib.sha256(xml.encode('utf-8')).hexdigest()==result['xmlSHA256']
            root=ET.fromstring(xml);assert root.tag=='hierarchy'
            assert int(root.attrib['window-id'])==result['windowId'] and result['windowId']>=0
            assert len(list(root.iter('node')))==result['nodeCount']>0
            budget(25)
            self.sequence+=1;xml_path=self.output/(f'{self.sequence:04d}-ui.xml')
            xml_path.write_text(xml,encoding='utf-8')
            receipt.update(available=True,xmlFile=xml_path.name,xmlSHA256=hashlib.sha256(xml.encode('utf-8')).hexdigest())
            parents={child:parent for parent in root.iter() for child in parent}
            elements=list(root.iter('node'));indices={node:index for index,node in enumerate(elements)};nodes=[]
            for node in elements:
                bounds=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.attrib.get('bounds',''))
                if not bounds:continue
                left,top,right,bottom=map(int,bounds.groups())
                if right<=left or bottom<=top:continue
                ancestor=node
                while ancestor is not None and ancestor.attrib.get('class')!='android.webkit.WebView':ancestor=parents.get(ancestor)
                assert node.attrib['package']==PACKAGE
                nodes.append(dict(node.attrib,rectangle=[left,top,right,bottom],nodeIndex=indices[node],
                    parentIndex=indices.get(parents.get(node)),webViewIndex=indices[ancestor] if ancestor is not None else None,
                    uiSnapshot={'provider':result['provider'],'api':result['api'],'uid':result['uid'],
                                'pid':result['pid'],'requestId':request,'xmlSHA256':result['xmlSHA256'],'waitForIdle':False,
                                'selection':result['selection'],'rootWindowId':result['rootWindowId'],
                                'rootPackage':result['rootPackage'],'chosenWindow':chosen,
                                'windows':windows,'activeRoot':active}))
            budget(25)
            return nodes
        except Exception as error:
            receipt.update(available=False,errorClass=type(error).__name__,error=traceback.format_exc())
            return []
        finally:receipt_path.write_text(json.dumps(receipt,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")

    def failure_ui(self):
        nodes=self.ui()
        if not nodes:raise RuntimeError('Fresh native AX unavailable; see '+str(self.last_ui_receipt))
        return nodes
