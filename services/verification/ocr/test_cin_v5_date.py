"""Synthetic-only examples. Never embed genuine CIN values or dates here."""
from datetime import date
import unittest
from unittest.mock import patch
from cin_v5_date import date_from_v5_votes


def pair(text, confidence=.97):
    return [(text, confidence), (text, confidence)]


class DateDecodeTests(unittest.TestCase):
    def read(self, day='41', month='فيفري', year='0002'):
        return date_from_v5_votes(pair(day), pair(month), pair(year),
                                  today=date(2026, 11, 12))

    def test_isolated_digit_reversal_and_leap_day(self):
        self.assertEqual(self.read(day='92', year='0002'), '2000-02-29')

    def test_tunisian_twelve_months(self):
        months = ['جانفي', 'فيفري', 'مارس', 'أفريل', 'ماي', 'جوان',
                  'جويلية', 'أوت', 'سبتمبر', 'أكتوبر', 'نوفمبر', 'ديسمبر']
        for number, month in enumerate(months, 1):
            with self.subTest(month=number):
                self.assertEqual(self.read(day='31', month=month),
                                 f'2000-{number:02d}-13')

    def test_variants_agree_only_for_normalized_month(self):
        self.assertEqual(date_from_v5_votes(pair('50'),
            [('أوت', .95), ('اوت', .92)], pair('0002'), today=date(2026, 11, 12)),
            '2000-08-05')

    def test_blank_or_missing_is_not_guessed(self):
        for field in ('day', 'month', 'year'):
            choices = dict(day=pair('50'), month=pair('مارس'), year=pair('0002'))
            choices[field] = pair('')
            self.assertIsNone(date_from_v5_votes(choices['day'], choices['month'], choices['year']))

    def test_low_confidence_or_conflicting_readings_fail_closed(self):
        sample = pair('50')
        self.assertIsNone(date_from_v5_votes(sample, [('مارس', .99), ('ماي', .99)], pair('0002')))
        self.assertIsNone(date_from_v5_votes([('50', .99), ('50', .71)], pair('مارس'), pair('0002')))
        self.assertIsNone(date_from_v5_votes(pair('50'), pair('مارس'), [('0002', float('nan')), ('0002', .99)]))
        self.assertIsNone(date_from_v5_votes(pair('50'), pair('مارس'), pair('0002')[:1]))
        self.assertIsNone(date_from_v5_votes([( '50', True ), ('50', .99)], pair('مارس'), pair('0002')))

    def test_rejects_false_date_and_noise(self):
        for bad in ['00', '23', '03', '٢٠', 'x1', '1/']:
            with self.subTest(bad=bad):
                self.assertIsNone(self.read(day=bad))
        self.assertIsNone(self.read(day='13', month='فيفري', year='1002'))  # Feb 31
        self.assertIsNone(self.read(day='92', month='فيفري', year='1002'))  # nonleap Feb 29
        self.assertIsNone(self.read(day='50', month='مارس', year='9999'))  # future
        self.assertIsNone(self.read(day='50', month='مارس', year='9901'))  # before 1900
        self.assertIsNone(self.read(day='50', month='مارسس'))
        self.assertIsNone(self.read(day='50', month='شباط'))

    def test_rejects_extraneous_labels(self):
        self.assertIsNone(self.read(month='جانفي تاريخ'))
        self.assertIsNone(self.read(year='0002السنة'))
        self.assertIsNone(self.read(day='50/'))

    def test_front_roi_passes_six_bounded_crops_to_consensus(self):
        """Synthetic full-card pixels only; no real OCR or PII."""
        import numpy as np
        import cin_v5
        card = np.full((760, 1200, 3), 230, dtype=np.uint8)
        reads = [('0002', .97), ('0002', .97),
                 ('جويلية', .95), ('جويلية', .93),
                 ('50', .96), ('50', .95)]
        with patch.object(cin_v5, 'recognize', side_effect=reads) as recognizer:
            result = cin_v5.birth_date(None, [], card, given_name_row=470)
        self.assertEqual(result, '2000-07-05')
        self.assertEqual(recognizer.call_count, 6)
        for args in [call.args for call in recognizer.call_args_list]:
            crop = args[2]
            self.assertTrue(18 <= crop.shape[0] <= 90)
            self.assertTrue(50 <= crop.shape[1] <= 350)
        self.assertEqual(card[0, 0, 0], 230)

    def test_front_roi_missing_or_ambiguous_does_not_create_date(self):
        import numpy as np
        import cin_v5
        card = np.full((760, 1200, 3), 220, dtype=np.uint8)
        with patch.object(cin_v5, 'recognize') as recognizer:
            self.assertIsNone(cin_v5.birth_date(None, [], card, given_name_row=10))
            recognizer.assert_not_called()
        with patch.object(cin_v5, 'recognize',
                          side_effect=[('0002', .99)] * 6):
            self.assertIsNone(cin_v5.birth_date(None, [], card, given_name_row=470))


    def test_front_number_vote_and_conflict(self):
        import numpy as np
        import cin_v5
        card=np.full((760,1200,3),220,dtype=np.uint8)
        with patch.object(cin_v5,'recognize',side_effect=[('87654321',.96)]*6) as read:
            self.assertEqual(cin_v5.cin_number(None,[],card),'5678')
        self.assertEqual(read.call_count,6)
        variants=[('87654321',.96)]*2+[('87654322',.96)]*2+[('',.99)]*2
        with patch.object(cin_v5,'recognize',side_effect=variants):
            self.assertIsNone(cin_v5.cin_number(None,[],card))
        with patch.object(cin_v5,'recognize',return_value=('',.99)):
            self.assertIsNone(cin_v5.cin_number(None,[],card))

    def test_front_dob_enabled_in_extraction(self):
        import numpy as np
        import cin_v5
        card=np.full((760,1200,3),220,dtype=np.uint8)
        with patch.object(cin_v5,'normalize_card',return_value=card), \
             patch.object(cin_v5,'find_name_rows',return_value=(400,470)), \
             patch.object(cin_v5,'consensus_name',
                          side_effect=[('المهدي',.91),('ياسمين',.92)]), \
             patch.object(cin_v5,'cin_number',return_value='5678'), \
             patch.object(cin_v5,'birth_date',return_value='2000-03-13') as dob:
            result=cin_v5.extract_fields(np.zeros((760,1200,3),dtype=np.uint8),None,[])
        self.assertEqual(result['cinLast4'],'5678')
        self.assertEqual(result['birthDate'],'2000-03-13')
        self.assertEqual(result['confidencePercent'],91)
        dob.assert_called_once()


if __name__ == '__main__':
    unittest.main()
