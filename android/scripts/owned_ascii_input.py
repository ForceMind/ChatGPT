"""Existing owned native-character/prefix acknowledgement, shared by CI flows."""
import hashlib
import json
import os
import re
import selectors
import subprocess
import time
from environment_upgrade import _write_json as write_json


class AcknowledgedNativeInput:
    input_identity_field = 'route'
    def input_timeout(self, timeout):
        if self._input_deadline is None:
            return timeout
        remaining = self._input_deadline-time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Network native input exceeded its original 75-second deadline')
        return min(timeout, remaining)


    def command(self, args, timeout=60, check=True):
        result = super().command(args, timeout=self.input_timeout(timeout), check=check)
        self.input_timeout(timeout)
        return result


    def wait(self, description, sample, condition, timeout=75, interval=0.6):
        if self._input_deadline is None:
            return super().wait(description, sample, condition, timeout=timeout, interval=interval)
        original = self._input_deadline
        self._input_deadline = min(original, time.monotonic()+timeout)
        last = None
        try:
            while True:
                self.input_timeout(timeout)
                last = sample()
                self.input_timeout(timeout)
                if condition(last):
                    return last
                time.sleep(self.input_timeout(interval))
        except TimeoutError:
            write_json(self.output/'last-timeout.json', {'description':description,'last':last})
            raise
        finally:
            self._input_deadline = original


    def type_draft(self, account, tab, draft):
        assert self._input_deadline is None
        self._input_deadline = time.monotonic()+75
        try:
            return super().type_draft(account, tab, draft)
        finally:
            self._input_deadline = None


    def stream_json(self, stream):
        while b'\n' not in stream['buffer']:
            ready=stream['selector'].select(self.input_timeout(75))
            if not ready:raise TimeoutError('Native stream receipt exceeded the shared input deadline')
            chunk=os.read(stream['process'].stdout.fileno(),65536)
            if not chunk:raise AssertionError('Native input stream ended before its receipt')
            stream['stdout'].write(chunk);stream['stdout'].flush();stream['buffer']+=chunk
            assert len(stream['buffer'])<=1024*1024, 'Native stream line exceeded its bounded protocol'
        line,stream['buffer']=stream['buffer'].split(b'\n',1)
        self.input_timeout(75)
        return json.loads(line.decode('utf-8'))


    def stream_state(self, value, phase, dispatch, pid=None):
        assert value['provider']=='ci-native-ascii-stream' and value['phase']==phase
        assert value['api']==self.api and value['uid']==2000 and value['dispatch']==dispatch
        assert isinstance(value['pid'],int) and value['pid']>0 and (pid is None or value['pid']==pid)
        return value['pid']


    def start_input_stream(self):
        self.sequence+=1;label=str(self.sequence)+'-native-ascii-stream'
        args=['adb','shell','-T','su','2000','env','CLASSPATH=/data/local/tmp/studio-poc-ascii-input.jar',
              'app_process','/system/bin','com.forcemind.chatgpt.pocinput.AsciiInputStream']
        proof={'command':args,'complete':False,'dispatches':[],'devicePID':None,'exitCode':None}
        self.input_stream_receipts.append(proof)
        stream={'proof':proof,'path':self.output/(label+'.json'),'buffer':b'',
                'stdout':(self.output/(label+'-stdout.txt')).open('wb'),
                'stderr':(self.output/(label+'-stderr.txt')).open('wb'),
                'selector':selectors.DefaultSelector(),'process':None}
        try:
            self.input_timeout(75)
            process=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stream['stderr'],bufsize=0)
            stream['process']=process;proof['hostPID']=process.pid
            stream['selector'].register(process.stdout,selectors.EVENT_READ)
            ready=self.stream_json(stream);proof['devicePID']=self.stream_state(ready,'ready',0)
            assert ready['qemu'] is True;proof['ready']=ready
            write_json(stream['path'],proof)
            return stream
        except BaseException as failure:
            proof['errorClass']=type(failure).__name__
            try:self.stop_input_stream(stream,False)
            except BaseException as cleanup:proof['cleanupErrorClass']=type(cleanup).__name__
            raise


    def dispatch_input_character(self, stream, character, index):
        self.input_timeout(75)
        process=stream['process'];assert process.poll() is None
        assert process.stdin.write((character+'\n').encode('ascii'))==2
        process.stdin.flush();self.input_timeout(75)
        dispatched=self.stream_json(stream);self.stream_state(dispatched,'dispatch',index,stream['proof']['devicePID'])
        assert dispatched['unicodeChar']==ord(character)
        proof={'dispatch':dispatched,'validated':False};stream['proof']['dispatches'].append(proof)
        receipt=self.stream_json(stream);self.sequence+=1
        name='ascii-keyboard-'+str(self.sequence)+'.json';write_json(self.output/name,receipt)
        proof['nativeReceipt']=name
        self.validate_stream_keyboard_receipt(receipt,character)
        done=self.stream_json(stream);self.stream_state(done,'done',index,stream['proof']['devicePID'])
        proof.update(done=done,validated=True);write_json(stream['path'],stream['proof'])
        return receipt


    def stop_input_stream(self, stream, success):
        process=stream['process'];proof=stream['proof']
        try:
            if process is not None:
                try:process.stdin.close()
                except OSError:
                    if success:raise
                if success:
                    closed=self.stream_json(stream);self.stream_state(closed,'closed',len(proof['dispatches']),proof['devicePID'])
                    assert closed['complete'] is True;proof['closed']=closed
                    assert process.wait(timeout=self.input_timeout(75))==0
                    self.input_timeout(75);proof['complete']=True
        finally:
            if process is not None:
                if process.poll() is None:
                    # Failure cleanup sends no keyboard events and cannot turn a failed deadline into success.
                    pid=proof.get('devicePID')
                    if pid is not None:
                        command=['adb','shell','su','2000','kill','-KILL',str(pid)]
                        proof['cleanupCommand']=command
                        try:
                            result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=1)
                            proof['cleanupExitCode']=result.returncode;proof['cleanupOutput']=result.stdout.decode('utf-8',errors='replace')
                        except subprocess.TimeoutExpired:proof['cleanupTimedOut']=True
                    process.kill()
                    try:process.wait(timeout=1)
                    except subprocess.TimeoutExpired:proof['hostExitUnconfirmed']=True
                proof['exitCode']=process.poll()
                # Keep every available original line, including a failed receipt and its stderr first error.
                while stream['selector'].select(0):
                    chunk=os.read(process.stdout.fileno(),65536)
                    if not chunk:break
                    stream['stdout'].write(chunk)
                process.stdout.close()
            stream['selector'].close();stream['stdout'].close();stream['stderr'].close()
            write_json(stream['path'],proof)


    def validate_stream_keyboard_receipt(self, receipt, draft):
        # Same native receipt assertions as the unchanged common batch driver.
        assert receipt['provider'] == 'ci-paced-ascii-keyboard' and receipt['complete'] is True
        assert receipt['api'] == self.api and receipt['uid'] == 2000 and receipt['qemu'] is True
        assert receipt['managerClass'] == ('android.hardware.input.InputManagerGlobal' if self.api == 35 else 'android.hardware.input.InputManager')
        assert receipt['injectionMode'] == 2 and receipt['paceMillis'] == 40
        assert receipt['textLength'] == len(draft) and receipt['textSHA256'] == hashlib.sha256(draft.encode('utf-8')).hexdigest()
        events = receipt['events']
        assert events and [event['index'] for event in events] == list(range(len(events)))
        assert all(event['accepted'] is True and event['waitForFinish'] is True and event['source'] == 257
                   and event['deviceId'] == -1 and event['repeatCount'] == 0 for event in events)
        down = {}
        reconstructed = []
        previous = None
        for event in events:
            assert event['action'] in (0, 1) and event['eventTime'] >= event['downTime']
            assert event['completedAt'] >= event['eventTime']
            if previous is not None:
                assert event['eventTime'] >= previous['completedAt'] + receipt['paceMillis']
            if event['action'] == 0:
                assert event['keyCode'] not in down
                assert event['downTime'] == event['eventTime']
                down[event['keyCode']] = event['downTime']
                if event['unicodeChar']:
                    reconstructed.append(chr(event['unicodeChar']))
            else:
                assert down.pop(event['keyCode']) == event['downTime']
            previous = event
        assert not down and ''.join(reconstructed) == draft
        self.keyboard_events.append(receipt)


    def native_ascii_batch(self, draft):
        # Serialize this owned input flow only; the common batch helper is unchanged.
        assert self._input_deadline is not None and re.fullmatch(r'[A-Za-z0-9-]{1,128}', draft)
        path = '/c/a1'
        baseline = self.latest(self.status(), path)
        assert baseline and baseline['draft'] == baseline['modelDraft'] == '' and baseline['focused'] is True
        document, identity = baseline['document'], baseline[self.input_identity_field]
        counts = (baseline['inputCount'], baseline['trustedInputCount'])
        receipts_before = len(self.keyboard_events)
        reconstructed = []
        stream=self.start_input_stream();failure=None
        try:
            for index, character in enumerate(draft):
                self.input_timeout(75)
                receipt = self.dispatch_input_character(stream,character,index)
                if index:
                    assert receipt['events'][0]['eventTime'] >= self.keyboard_events[-2]['events'][-1]['completedAt']+receipt['paceMillis'], 'Native character batches overlap'
                reconstructed.extend(chr(event['unicodeChar']) for event in receipt['events'] if event['action']==0 and event['unicodeChar'])
                prefix, previous = draft[:index+1], draft[:index]
                wanted = (counts[0]+index+1,counts[1]+index+1)
                def acknowledged(status):
                    row = self.latest(status,path)
                    assert row and row['document']==document and row[self.input_identity_field]==identity, 'Input document or route changed'
                    assert row['draft'] in (previous,prefix) and row['modelDraft'] in (previous,prefix), 'Illegal native input prefix'
                    assert wanted[0]-1 <= row['inputCount'] <= wanted[0], 'Unexpected input event count'
                    assert wanted[1]-1 <= row['trustedInputCount'] <= wanted[1], 'Unexpected trusted input event count'
                    return row['draft']==row['modelDraft']==prefix and (row['inputCount'],row['trustedInputCount'])==wanted
                proof = {'index':index,'expectedPrefix':prefix,'document':document,
                         'keyboardReceiptIndex':len(self.keyboard_events)-1,'streamPID':stream['proof']['devicePID'],'streamDispatch':index,'complete':False}
                if self.input_identity_field == 'route':
                    proof['route'] = identity
                else:
                    proof.update(inputIdentityField=self.input_identity_field, inputIdentity=identity)
                evidence = self.output/('native-prefix-'+str(index+1)+'.json')
                write_json(evidence,proof)
                try:
                    status = self.wait('exact trusted native prefix '+str(index+1),self.status,acknowledged)
                except Exception as prefix_failure:
                    proof['errorClass']=type(prefix_failure).__name__;write_json(evidence,proof)
                    raise
                row = self.latest(status,path)
                proof.update(complete=True,draft=row['draft'],modelDraft=row['modelDraft'],inputCount=row['inputCount'],
                             trustedInputCount=row['trustedInputCount'],receivedAt=row['receivedAt'],
                             elapsedMillis=int((75-self.input_timeout(75))*1000))
                self.input_prefix_receipts.append(proof)
                write_json(evidence,proof)
            assert len(self.keyboard_events)-receipts_before == len(draft)
            assert ''.join(reconstructed)==draft, 'Combined native Unicode receipt differs from complete draft'
            assert row['draft']==row['modelDraft']==draft and (row['inputCount'],row['trustedInputCount'])==(counts[0]+len(draft),counts[1]+len(draft))
            self.assert_environment(row,'A')
        except BaseException as error:
            failure=error;stream['proof']['errorClass']=type(error).__name__
            raise
        finally:
            try:self.stop_input_stream(stream,failure is None)
            except BaseException as cleanup:
                if failure is None:raise
                stream['proof']['cleanupErrorClass']=type(cleanup).__name__
                write_json(stream['path'],stream['proof'])


