import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from grouped_environment_validation import expected,validate,shared_markers,profile_present

def sample(account="A",kind="main"):
    lang,zone,winter,summer,noon,gap,maximized=expected(account)
    marker={"owner":account,"token":"token-"+account}
    environment={"language":lang,"languages":[lang],"localeMaximized":maximized,"numberLocaleMaximized":maximized,"timeZone":zone,"winterOffset":winter,"summerOffset":summer,"localNoon":noon,"parsedNoon":noon,"dstGap":gap}
    return {"account":account,"kind":kind,"language":lang,"languages":[lang],"localeMaximized":maximized,"numberLocaleMaximized":maximized,"timeZone":zone,
            "winterOffset":winter,"summerOffset":summer,"localNoon":noon,"parsedNoon":noon,"dstGap":gap,
            "acceptLanguage":lang+",en;q=0.5","cookie":"poc-owner="+account,"previousCookie":"","requestCookie":"poc-owner="+account,"cookieState":"ready",
            "localState":"ready","idbState":"ready","cacheState":"ready","local":copy.deepcopy(marker),"idb":copy.deepcopy(marker),"cache":copy.deepcopy(marker),
            "service":{"state":"ready","owner":account,"response":{"owner":account,**environment}}}

class GroupedValidationTests(unittest.TestCase):
    def test_profile_guard_rejects_unrecognized_output(self):
        self.assertTrue(profile_present('POC_PRESENT'));self.assertFalse(profile_present('POC_ABSENT'))
        for text in ('','ls: missing','POC_ABSENT\npermission denied','POC_PRESENT\nPOC_ABSENT'):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):profile_present(text)

    def test_expected_environments_and_valid_observations(self):
        for account in ("A","B","C"):
            for kind in ("main","frame","worker","popup"):
                with self.subTest(account=account,kind=kind):self.assertEqual([],validate(sample(account,kind)))

    def test_wrong_language_zone_and_dst_are_rejected(self):
        for key,value in (("language","en-US"),("timeZone","America/New_York"),("winterOffset",0),("dstGap","bad"),("acceptLanguage","en-US")):
            row=sample();row[key]=value
            with self.subTest(key=key):self.assertTrue(validate(row))

    def test_foreign_storage_cookie_and_service_worker_are_rejected(self):
        mutations=[("cookie","poc-owner=C"),("requestCookie","poc-owner=A; poc-owner=C"),("localPrevious",{"owner":"C","token":"x"}),
                   ("idb",{"owner":"C","token":"x"}),("cache",{"owner":"C","token":"x"}),("service",{"owner":"C"}),
                   ("service",{"owner":"A","response":{"owner":"C","language":"zh-CN","timeZone":"Asia/Shanghai"}})]
        for key,value in mutations:
            row=sample();row[key]=value
            with self.subTest(key=key,value=value):self.assertTrue(validate(row))

    def test_first_party_failures_are_not_hidden_by_privacy_blocking(self):
        row=sample();row["idbState"]="blocked";row["idb"]=None;self.assertTrue(validate(row))
        frame=sample(kind="frame");frame["idbState"]="blocked";frame["idb"]=None;self.assertEqual([],validate(frame))
        frame["cookieState"]="blocked";frame["cookie"]="";self.assertEqual([],validate(frame))
        frame["idbState"]="error";self.assertTrue(validate(frame))
        row=sample();row["kind"]="unexpected";self.assertTrue(validate(row))
        row=sample();row["service"]={"state":"unsupported"};self.assertTrue(validate(row))
        row=sample();row["service"]["response"]["summerOffset"]=0;self.assertTrue(validate(row))

    def test_same_account_must_share_real_tokens_and_other_account_must_not(self):
        a0=sample();a1=copy.deepcopy(a0);c=sample("C")
        self.assertTrue(shared_markers(a0,a1,c))
        a1["idb"]["token"]="different";self.assertFalse(shared_markers(a0,a1,c))
        a1=copy.deepcopy(a0);c["cache"]["token"]=a0["cache"]["token"];self.assertFalse(shared_markers(a0,a1,c))
        self.assertFalse(shared_markers(a0,None,c))

if __name__=="__main__":unittest.main()
