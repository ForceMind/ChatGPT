import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from poc_warning_report import localize

class WarningReportTests(unittest.TestCase):
    def test_warning_before_markers_is_not_assigned_to_later_phase(self):
        r=localize('10-28 01:02:03.004  123  456 W GeckoEditableChild: No editable parent\n10-28 01:02:04.000  99  99 I StudioPocPhase: phase=seed step=begin')
        self.assertEqual(r['entries'][0]['phase'],'before-first-marker');self.assertEqual(r['entries'][0]['pid'],123)
    def test_latest_phase_and_stop_marker_are_kept(self):
        r=localize('I StudioPocPhase: phase=seed step=begin\nI StudioPocPhase: phase=normal-exit step=complete-exit-request\nW GeckoEditableChild: No editable parent\nI StudioPocPhase: phase=reconnected step=begin\nE Gecko: win is null')
        self.assertEqual([(e['phase'],e['step']) for e in r['entries']],[('normal-exit','complete-exit-request'),('reconnected','begin')]);self.assertEqual(r['counts']['sessionStoreWinNull'],1)
    def test_lifecycle_is_context_not_a_cause(self):
        r=localize('I ManagedRecoveryPoC: lifecycle=destroy account=A\nW GeckoEditableChild: No editable parent')
        self.assertIn('lifecycle=destroy',r['entries'][0]['nearestPriorLifecycleMarker']);self.assertFalse(r['causalAttribution'])
    def test_no_warnings_produces_zero_counts(self):
        r=localize('I StudioPocPhase: phase=seed step=begin\nI ManagedRecoveryPoC: event=resumed account=A')
        self.assertEqual(r['entries'],[]);self.assertTrue(all(v==0 for v in r['counts'].values()))

if __name__=='__main__':unittest.main()
