"""Validate isolated browser observations; synthetic fixture data only."""
import re

def profile_present(marker):
    if marker=="POC_PRESENT":return True
    if marker=="POC_ABSENT":return False
    raise ValueError("Experiment profile state could not be verified")

def clear_receipt_matches(row,pid):
    return (isinstance(row,dict) and row.get("account")=="C" and row.get("mode")=="grouped"
            and row.get("event")=="clear-requested" and row.get("context")=="grouped-poc-grouped-C"
            and type(row.get("tabs")) is int and row["tabs"]==0 and type(row.get("pid")) is int and row["pid"]==pid)

def expected(account):
    return (("en-US","America/New_York",300,240,"2026-01-15T17:00:00.000Z","2026-03-08T07:30:00.000Z","en-Latn-US") if account=="B"
            else ("zh-CN","Asia/Shanghai",-480,-480,"2026-01-15T04:00:00.000Z","2026-03-07T18:30:00.000Z","zh-Hans-CN"))

def validate(row):
    account=row["account"]
    if account not in ("A","B","C") or row.get("kind") not in ("main","frame","worker","popup"):return ["unknown account or context"]
    lang,zone,winter,summer,noon,gap,maximized=expected(account);errors=[]
    environment={"language":lang,"timeZone":zone,"winterOffset":winter,"summerOffset":summer,"localNoon":noon,"parsedNoon":noon,"dstGap":gap,"localeMaximized":maximized,"numberLocaleMaximized":maximized}
    for key,value in environment.items():
        if row.get(key)!=value:errors.append(key+": "+str(row.get(key))+" != "+str(value))
    if not row.get("languages") or row["languages"][0]!=lang:errors.append("navigator.languages")
    if not row.get("acceptLanguage","").lower().startswith(lang.lower()):errors.append("request Accept-Language")
    for key in ("cookie","previousCookie","requestCookie"):
        owners=re.findall(r"(?:^|;\s*)poc-owner=([^;]*)",row.get(key,""))
        if any(owner!=account for owner in owners):errors.append("foreign "+key)
        if key=="cookie" and row["kind"] in ("main","popup") and not row.get("probe") and owners!=[account]:errors.append("missing first-party cookie")
    if row["kind"]!="worker":
        if row.get("cookieState") not in ("ready","blocked"):errors.append("cookie read error")
        if row["kind"] in ("main","popup") and row.get("cookieState")!="ready":errors.append("first-party cookie unavailable")
        for key in ("local","idb","cache"):
            if row.get(key+"State") not in ("ready","blocked"):errors.append(key+" read error")
        for key in ("local","localPrevious","idb","idbPrevious","cache","cachePrevious"):
            value=row.get(key)
            if value is not None and (not isinstance(value,dict) or value.get("owner")!=account or not isinstance(value.get("token"),str) or not value["token"]):errors.append("foreign or invalid "+key)
        if row["kind"] in ("main","popup") and not row.get("probe"):
            for key in ("local","idb","cache"):
                if row.get(key+"State")!="ready" or not row.get(key):errors.append("missing first-party "+key)
        service=row.get("service",{})
        if row["kind"]=="main" and not row.get("probe") and service.get("state") in ("unsupported","error"):errors.append("service worker unavailable")
        if service.get("owner") not in (None,account):errors.append("foreign service worker registration")
        response=service.get("response")
        if response:
            if response.get("owner")!=account:errors.append("foreign service worker response")
            if any(response.get(key)!=value for key,value in environment.items()) or not response.get("languages") or response["languages"][0]!=lang:errors.append("service worker environment")
    return errors

def shared_markers(a0,a1,c):
    if not all(isinstance(r,dict) for r in (a0,a1,c)):return False
    for key in ("local","idb","cache"):
        values=[row.get(key) for row in (a0,a1,c)]
        if not all(isinstance(v,dict) and isinstance(v.get("token"),str) and v["token"] for v in values):return False
        if [v.get("owner") for v in values]!=["A","A","C"] or values[0]["token"]!=values[1]["token"] or values[0]["token"]==values[2]["token"]:return False
    return True
