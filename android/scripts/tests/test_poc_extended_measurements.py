import pathlib
import sys
import unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from poc_extended_measurements import parse_proc_stat,cpu_delta

class ExtendedMeasurementTests(unittest.TestCase):
    def test_stat_with_spaces_and_parentheses_in_command(self):
        fields=['S']+['0']*21
        fields[11]='120';fields[12]='30';fields[19]='500'
        self.assertEqual(parse_proc_stat('123 (renderer (tab one)) '+' '.join(fields)),{'pid':123,'cpuTicks':150,'startTicks':500})
    def test_truncated_stat_is_not_zero_cpu(self):
        with self.assertRaises(ValueError):parse_proc_stat('123 (renderer) S 0')
    def test_cpu_uses_device_duration_and_one_core_units(self):
        before={'hostMonotonic':1,'deviceUptimeSeconds':10,'processes':[{'pid':1,'startTicks':20,'cpuTicks':100},{'pid':2,'startTicks':30,'cpuTicks':0}]}
        after={'hostMonotonic':14,'deviceUptimeSeconds':20,'processes':[{'pid':1,'startTicks':20,'cpuTicks':1100},{'pid':2,'startTicks':30,'cpuTicks':1000}]}
        r=cpu_delta(before,after,100)
        self.assertEqual(r['oneCorePercent'],200);self.assertEqual(r['deviceSeconds'],10);self.assertEqual(r['hostSeconds'],13)
    def test_recycled_or_missing_process_is_incomplete_not_idle(self):
        b={'hostMonotonic':1,'deviceUptimeSeconds':10,'processes':[{'pid':1,'startTicks':20,'cpuTicks':100}]}
        for processes in ([],[{'pid':1,'startTicks':21,'cpuTicks':110}]):
            a={'hostMonotonic':11,'deviceUptimeSeconds':20,'processes':processes};r=cpu_delta(b,a,100)
            self.assertFalse(r['completeProcessCoverage']);self.assertIsNone(r['oneCorePercent'])
    def test_invalid_clock_interval_is_rejected(self):
        s={'hostMonotonic':1,'deviceUptimeSeconds':10,'processes':[]}
        with self.assertRaises(ValueError):cpu_delta(s,s,100)

if __name__=='__main__':unittest.main()
