import unittest
from pyproj import Transformer
from shapely.geometry import shape
from scripts.atlas_geometry_normalize import normalize_geometry

def rect(x,y,w,h):
    return [[x,y],[x+w,y],[x+w,y+h],[x,y+h],[x,y]]

class NormalizeTest(unittest.TestCase):
    def test_arcgis_holes_and_islands_and_disjoint(self):
        a=rect(10,35,1,1)
        hole=rect(10.1,35.1,.8,.8)
        island=rect(10.2,35.2,.1,.1)
        other=rect(11.1,35,.2,.2)
        g,qa=normalize_geometry({"rings":[hole,other,island,a],"spatialReference":{"wkid":4326}},"ArcGIS","EPSG:4326")
        obj=shape(g)
        self.assertEqual(g["type"],"MultiPolygon")
        self.assertAlmostEqual(obj.area,1-.64+.01+.04,places=8)
        self.assertEqual(sum(len(p.interiors) for p in obj.geoms),1)
        self.assertFalse(qa["ground_verified"])
    def test_web_mercator_alias(self):
        tr=Transformer.from_crs(4326,3857,always_xy=True)
        ring=[list(tr.transform(*p)) for p in rect(10,35,.001,.001)]
        result,qa=normalize_geometry({"rings":[ring],"spatialReference":{"wkid":102100,"latestWkid":3857}},"ArcGIS","EPSG:3857")
        self.assertAlmostEqual(shape(result).bounds[0],10,places=8)
        self.assertAlmostEqual(shape(result).bounds[1],35,places=8)
    def test_utm(self):
        tr=Transformer.from_crs(4326,32632,always_xy=True)
        ring=[list(tr.transform(*p)) for p in rect(10,35,.001,.001)]
        result,_=normalize_geometry({"type":"Polygon","coordinates":[ring]},"ProjectedGeoJSON","EPSG:32632")
        self.assertAlmostEqual(shape(result).bounds[0],10,places=8)
    def test_conflicting_and_unknown_crs(self):
        value={"rings":[rect(10,35,.1,.1)],"spatialReference":{"wkid":3857}}
        with self.assertRaisesRegex(ValueError,"CRS_CONFLICT"):normalize_geometry(value,"ArcGIS","EPSG:4326")
        with self.assertRaisesRegex(ValueError,"AUTHORITY_CRS"):normalize_geometry(value,"ArcGIS","auto")
    def test_no_repair_of_contact_or_bad_ring(self):
        with self.assertRaisesRegex(ValueError,"RING_CONTACT"):normalize_geometry({"rings":[rect(10,35,1,1),rect(10,35,.2,.2)]},"ArcGIS","EPSG:4326")
        with self.assertRaisesRegex(ValueError,"UNCLOSED"):normalize_geometry({"rings":[rect(10,35,1,1)[:-1]]},"ArcGIS","EPSG:4326")
    def test_no_outside_or_z_or_curves(self):
        with self.assertRaisesRegex(ValueError,"OUTSIDE_TUNISIA"):normalize_geometry({"rings":[rect(0,0,1,1)]},"ArcGIS","EPSG:4326")
        with self.assertRaisesRegex(ValueError,"EXTRA_DIMENSIONS"):normalize_geometry({"rings":[rect(10,35,1,1)],"hasZ":True},"ArcGIS","EPSG:4326")
