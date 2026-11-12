"""Synthetic-only examples. Never embed genuine CIN values or dates here."""
from datetime import date
import unittest
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


if __name__ == '__main__':
    unittest.main()
