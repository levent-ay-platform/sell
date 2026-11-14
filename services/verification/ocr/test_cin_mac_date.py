"""Fictional dates only: native text must be corroborated by v5 digits."""
import copy
import unittest
from datetime import date


def document(text='ولد في 05 ديسمبر 2000', top=550, confidence=.5):
    return {'pages':[{'page':1,'lines':[{'text':text,'confidence':confidence,
        'bbox':{'left':480,'top':top,'right':1160,'bottom':top+80}}]}]}


class MacDateTests(unittest.TestCase):
    def test_native_year_geometry_guides_v5_without_accepting_a_guessed_year(self):
        import numpy as np
        from cin_mac_date import guided_year_votes,corroborated_birth_date
        card=np.full((760,1200,3),230,dtype=np.uint8)
        doc=document()
        doc['pages'][0]['ocrWords']=[{'text':'2000','bbox':{'left':490,'top':545,'right':655,'bottom':649}}]
        crops=[]
        def reader(crop):
            crops.append(crop.shape)
            return [('0002',.99),('0002',.98)]
        votes=guided_year_votes(doc,card,455,'2000-12-05',reader)
        self.assertEqual(crops,[(118,181,3)])
        self.assertEqual(corroborated_birth_date([([('50',.99)]*2,[('',0.)]*2)]+votes,
            [{'2000-12-05'},{'2000-12-05'}]),'2000-12-05')
        doc['pages'][0]['ocrWords'][0]['bbox']['top']=100
        self.assertEqual(guided_year_votes(doc,card,455,'2000-12-05',reader),[])
    def test_exact_month_in_birth_row_and_real_calendar_only(self):
        from cin_mac_date import native_birth_dates
        self.assertEqual(native_birth_dates(document(),1200,760,455),{'2000-12-05'})
        for text in ['ولد في 31 فيفري 2000','ولد في 05 ديسمبرر 2000',
                     'ولد في 05 ديسمبر 9999','ولد في 05 ديسمبر 2000 17',
                     'اصدرت في 05 ديسمبر 2000','ولد في 05 ديسمبر جانفي 2000']:
            self.assertEqual(native_birth_dates(document(text),1200,760,455),set())

    def test_rejects_issue_row_geometry_low_scores_and_multiple_candidates(self):
        from cin_mac_date import native_birth_dates
        for doc in [document(top=200),document(top=695),document(confidence=.49),
                    document(confidence=float('nan'))]:
            self.assertEqual(native_birth_dates(doc,1200,760,455),set())
        doc=document();other=copy.deepcopy(doc['pages'][0]['lines'][0])
        other['text']='ولد في 06 ديسمبر 2000';doc['pages'][0]['lines'].append(other)
        self.assertEqual(native_birth_dates(doc,1200,760,455),{'2000-12-05','2000-12-06'})

    def test_two_native_views_and_v5_numeric_consensus_required(self):
        from cin_mac_date import corroborated_birth_date
        pair=lambda value:[(value,.98),(value,.97)]
        votes=[(pair('50'),pair('0002'))]
        views=[{'2000-12-05'},{'2000-12-05'}]
        self.assertEqual(corroborated_birth_date(votes,views,today=date(2026,11,14)),'2000-12-05')
        for bad in [[set(),views[0]],[views[0],{'2000-11-05'}],
                    [views[0],{'2000-12-05','2000-11-05'}],views[:1]]:
            self.assertIsNone(corroborated_birth_date(votes,bad))
        for bad in [[],[(pair('60'),pair('0002'))],
                    votes+[(pair('60'),pair('0002'))],
                    votes+[(pair('60'),[('',.1),('',.1)])],
                    votes+[([('',.1),('',.1)],pair('1002'))],
                    votes+[([('50',.98),('60',.98)],pair('0002'))],
                    [([('50',.98),('50',.70)],pair('0002'))]]:
            self.assertIsNone(corroborated_birth_date(bad,views))
        self.assertIsNone(corroborated_birth_date(votes,views,today=date(1999,1,1)))

    def test_v5_birth_date_calls_optional_fallback_with_actual_numeric_votes(self):
        import numpy as np
        import cin_v5
        from unittest.mock import patch
        card=np.full((760,1200,3),230,dtype=np.uint8)
        def numeric(_s,_l,crop):
            text='50' if crop.shape[1]<250 else '0002'
            return [(text,.98),(text,.97)]
        with patch.object(cin_v5,'numeric_pair',side_effect=numeric), \
             patch.object(cin_v5,'recognize',return_value=('',.1)), \
             patch('cin_mac_date.read_mac_birth_date',return_value='2000-12-05') as fallback:
            self.assertEqual(cin_v5.birth_date(None,[],card,470),'2000-12-05')
            self.assertEqual(len(fallback.call_args.args[2]),3)

if __name__=='__main__':unittest.main()
