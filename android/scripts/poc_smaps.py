"""Aggregate kernel smaps counters; never read mapped memory contents."""
import re

HEADER=re.compile(r'^([0-9a-f]+)-([0-9a-f]+)\s+([rwxps-]{4})\s+[0-9a-f]+\s+[0-9a-f]+:[0-9a-f]+\s+\d+\s*(.*)$')
FIELDS=('Size','Rss','Pss','Private_Clean','Private_Dirty','Shared_Clean','Shared_Dirty','Anonymous','Swap','SwapPss')

def parse_smaps(raw):
    groups={};current=None;regions=0;rss_fields=0;pss_fields=0
    for line in raw.splitlines():
        match=HEADER.match(line)
        if match:
            start,end,permissions,name=match.groups()
            if int(end,16)<=int(start,16):raise ValueError('Invalid mapping address interval')
            name=name.strip() or '[anonymous-unnamed]'
            key=(permissions,name);current=groups.setdefault(key,{'permissions':permissions,'name':name,'regions':0,**{k+'KiB':0 for k in FIELDS}})
            current['regions']+=1;regions+=1;continue
        value=re.match(r'^(\w+):\s+(\d+)\s+kB$',line)
        if value and value[1] in FIELDS:
            if current is None:raise ValueError('Counters precede mapping header')
            if value[1]=='Rss':rss_fields+=1
            if value[1]=='Pss':pss_fields+=1
            current[value[1]+'KiB']+=int(value[2])
    if not regions:raise ValueError('Missing kernel mappings')
    if rss_fields!=regions or pss_fields!=regions:raise ValueError('Incomplete mapping counters')
    rows=list(groups.values())
    if not any(row['RssKiB'] for row in rows):raise ValueError('Missing resident mapping counters')
    return {'mappingRegions':regions,'groups':rows,'totalsKiB':{key:sum(row[key+'KiB'] for row in rows) for key in FIELDS},'scope':'Kernel address-range metadata and counters only; no memory content, allocation stacks, object graph or leak attribution.'}
