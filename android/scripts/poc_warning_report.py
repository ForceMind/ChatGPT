"""Correlate device log warnings with explicit phase markers, without assigning cause."""
import re

KINDS={'noEditableParent':'No editable parent','sessionStoreWinNull':'win is null','fatalException':'FATAL EXCEPTION'}

def localize(log):
    phase='before-first-marker';step='unknown';lifecycle=None;entries=[];by_phase={}
    for number,line in enumerate(log.splitlines(),1):
        marker=re.search(r'\bStudioPocPhase\s*:\s*phase=([a-z0-9-]+)\s+step=([a-z0-9-]+)',line)
        if marker:phase,step=marker.groups()
        if re.search(r'\bManagedRecoveryPoC\s*:\s*(?:event=|lifecycle=)',line):lifecycle=line[:1200]
        for kind,needle in KINDS.items():
            if needle not in line:continue
            pid=re.match(r'\d\d-\d\d\s+[\d:.]+\s+(\d+)\s+',line)
            entries.append({'kind':kind,'lineNumber':number,'pid':int(pid.group(1)) if pid else None,'phase':phase,'step':step,'nearestPriorLifecycleMarker':lifecycle,'line':line[:1600]})
            counts=by_phase.setdefault(phase,{key:0 for key in KINDS});counts[kind]+=1
    return {'schema':1,'causalAttribution':False,'scope':'Whole fresh-emulator log, correlated by the last preceding marker; proximity is not causality.','counts':{kind:sum(e['kind']==kind for e in entries) for kind in KINDS},'byPhase':by_phase,'entries':entries}
