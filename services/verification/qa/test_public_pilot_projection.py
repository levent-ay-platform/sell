"""P0/P1 public Pilot projection tests: deterministic and no private source access."""
import unittest

from pilot_workflow import PilotStore, WorkflowError


class PublicPilotProjection(unittest.TestCase):
    def test_legacy_reviewed_submission_is_not_falsely_classified_as_land(self):
        private = {
            'title': 'Synthetic review', 'region': 'Nabeul',
            'area': '240', 'price': '48000', 'description': '',
            'latitude': '', 'longitude': '',
            'privateIdentity': 'NEVER_PUBLIC',
            'ownerPhone': '+21600000000',
        }
        exposed = PilotStore._public_listing_fields(private)
        self.assertEqual(exposed['kind'], 'UNKNOWN')
        self.assertEqual(exposed['locationAccuracy'], 'AREA_ONLY')
        self.assertIsNone(exposed['latitude'])
        self.assertIsNone(exposed['longitude'])
        self.assertEqual(set(exposed), {
            'title','region','area','price','description','kind',
            'latitude','longitude','locationAccuracy',
        })
        self.assertNotIn('NEVER_PUBLIC', str(exposed))

    def test_direct_owner_private_position_is_reduced_in_public_projection(self):
        private = {
            'title': 'Synthetic land', 'region': 'Nabeul',
            'area': '300', 'price': '80000', 'description': 'QA',
            'kind': 'LAND',
            'latitude': '36.87978493', 'longitude': '11.004884',
        }
        exposed = PilotStore._public_listing_fields(private, allow_public_location=True)
        self.assertEqual(exposed['kind'], 'LAND')
        self.assertEqual(exposed['locationAccuracy'], 'APPROXIMATE')
        self.assertEqual(exposed['latitude'], '36.88')
        self.assertEqual(exposed['longitude'], '11.00')
        self.assertNotEqual(exposed['latitude'], private['latitude'])
        self.assertNotEqual(exposed['longitude'], private['longitude'])
        self.assertEqual(private['latitude'], '36.87978493')  # private source unmodified

    def test_out_of_country_invalid_or_missing_points_cannot_become_markers(self):
        base = {'title':'QA','region':'Nabeul','area':'100','price':'40000','kind':'LAND'}
        for lat, lon in [
            ('0','0'), ('36','0'), ('NaN','11'), ('inf','11'),
            ('36.7',''), ('','11'), ('95','11'), ('0','11'),
            ('36','180'), (None,None),
        ]:
            with self.subTest(lat=lat,lon=lon):
                public = PilotStore._public_listing_fields({
                    **base, 'latitude':lat, 'longitude':lon,
                }, allow_public_location=True)
                self.assertIsNone(public['latitude'])
                self.assertIsNone(public['longitude'])
                self.assertEqual(public['locationAccuracy'], 'AREA_ONLY')

    def test_admin_reviewed_legacy_location_does_not_prove_public_map_consent(self):
        private = {
            'title':'Legacy synthetic','region':'Nabeul','area':'100','price':'50000',
            'kind':'LAND','latitude':'36.87978','longitude':'11.00488',
        }
        public = PilotStore._public_listing_fields(private)
        self.assertIsNone(public['latitude'])
        self.assertIsNone(public['longitude'])
        self.assertEqual(public['locationAccuracy'],'AREA_ONLY')
        self.assertEqual(private['latitude'],'36.87978')

    def test_direct_public_listing_cannot_claim_a_position_outside_tunisia(self):
        base = {
            'title':'QA property','region':'Nabeul','description':'',
            'area':'120','price':'50000','kind':'LAND'
        }
        for lat, lon in [('0','0'),('36','0'),('37','17'),('40','10'),('25','11')]:
            with self.subTest(lat=lat,lon=lon):
                with self.assertRaises(WorkflowError) as denied:
                    PilotStore._direct_fields({
                        **base,'latitude':lat,'longitude':lon,
                    })
                self.assertEqual(denied.exception.code, 'PUBLIC_LOCATION_OUT_OF_SCOPE')
        allowed = PilotStore._direct_fields({
            **base,'latitude':'36.87978','longitude':'11.00488',
        })
        self.assertEqual((allowed['latitude'],allowed['longitude']),('36.880','11.005'))

    def test_unknown_kind_never_falls_back_to_land_or_a_sale_claim(self):
        base = {'title':'QA','region':'Tunis','area':'100','price':'40000'}
        for kind in [None, '', 'OTHER', 'terrain', 'COMMERCIAL', 'LANDFAKE']:
            with self.subTest(kind=kind):
                self.assertEqual(
                    PilotStore._public_listing_fields({**base, 'kind':kind})['kind'],
                    'UNKNOWN',
                )
        self.assertEqual(PilotStore._public_listing_fields({
            **base, 'kind':'APARTMENT'
        })['kind'], 'APARTMENT')


if __name__ == '__main__':
    unittest.main()
