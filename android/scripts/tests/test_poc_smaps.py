import pathlib,sys,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from poc_smaps import parse_smaps

class MappingParserTests(unittest.TestCase):
    def test_duplicate_ranges_aggregate_by_reported_name_and_permissions(self):
        raw='1000-2000 rw-p 0000 00:00 0 [anon:arena pool]\nSize: 4 kB\nRss: 4 kB\nPss: 3 kB\nPrivate_Dirty: 2 kB\n2000-4000 rw-p 0000 00:00 0 [anon:arena pool]\nSize: 8 kB\nRss: 8 kB\nPss: 6 kB\nPrivate_Dirty: 5 kB\n'
        r=parse_smaps(raw);self.assertEqual(r['mappingRegions'],2);self.assertEqual(len(r['groups']),1);self.assertEqual(r['totalsKiB']['Pss'],9);self.assertEqual(r['totalsKiB']['Private_Dirty'],7)
    def test_unnamed_and_file_mappings_are_not_merged(self):
        r=parse_smaps('1000-2000 rw-p 0000 00:00 0\nRss: 4 kB\nPss: 4 kB\n2000-3000 r-xp 0000 01:02 10 /data/app/lab/base.apk (deleted)\nRss: 4 kB\nPss: 1 kB\n')
        self.assertEqual(len(r['groups']),2);self.assertEqual(r['groups'][0]['name'],'[anonymous-unnamed]');self.assertEqual(r['groups'][1]['name'],'/data/app/lab/base.apk (deleted)')
    def test_empty_denied_or_malformed_input_is_not_zero_memory(self):
        for raw in ['', 'Permission denied','Pss: 100 kB\n','2000-1000 rw-p 0000 00:00 0\nRss: 1 kB\n']:
            with self.assertRaises(ValueError):parse_smaps(raw)

if __name__=='__main__':unittest.main()
