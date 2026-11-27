"""Synthetic end-to-end bridge from archive batches to scoped title lookup."""
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from pypdf import PdfWriter
from scripts.atlas_archive_review_batch import run_batch
from scripts.atlas_cadastre_evidence_audit import write_private_json
from scripts.atlas_property_index import build_index, lookup


class IndexTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name).resolve()
        self.root = self.base/"archive"
        self.root.mkdir()
        self.db = self.base/"snapshot.sqlite"
        pdf = self.root/"plan.pdf"
        w = PdfWriter()
        w.add_blank_page(width=100,height=100)
        with pdf.open("wb") as f:
            w.write(f)
        self.sha = hashlib.sha256(pdf.read_bytes()).hexdigest()
        self.other = "0"*64
        with sqlite3.connect(self.db) as c:
            c.executescript("""
                CREATE TABLE resources (id TEXT, name TEXT, extension TEXT, sha256 TEXT, url TEXT);
                CREATE TABLE documents (sha256 TEXT, state TEXT, page_count INTEGER);
                CREATE TABLE pages (sha256 TEXT, page INTEGER, text TEXT);
            """)
            c.executemany("INSERT INTO resources VALUES (?,?,?,?,?)",[
                ("a","Plan cadastral Kelibia.pdf",".pdf",self.sha,"https://otc.nat.tn/a.pdf"),
                ("b","Duplicate.pdf",".pdf",self.sha,"https://example.invalid/b.pdf"),
                ("c","Unknown.pdf",".pdf",self.other,"https://example.invalid/c.pdf"),
                ("d","Unhashed.pdf",".pdf",None,"https://example.invalid/d.pdf")])
            c.execute("INSERT INTO documents VALUES (?,?,?)",(self.sha,"ocr_pending",1))
            c.execute("INSERT INTO pages VALUES (?,?,?)",(self.sha,1,"Titre foncier 000777"))
        self.output = self.base/"batch"
        ocr = {"mayApproveOwnership":False,"pages":[{"page":1,"method":"ocr",
            "lines":[{"text":"Titre foncier 00012345 Parcelle 0021 PRIVATE_NAME"}]}]}
        with patch("scripts.atlas_archive_review_batch._run_child",side_effect=[
                (None,"WORKER_TIMEOUT"),(ocr,"COMPLETED")]):
            run_batch(self.db,"mainfile_diagnostic",[self.root],self.output,ocr=True)
        self.scopes = {self.sha:"TN-NABEUL"}
        self.sources = [{"namespace":"ATLAS","service_id":"OTC","layer_id":"plan",
                         "object_id":"a","version":"v1","registry_scope":"TN-NABEUL",
                         "title_reference":"00012345","parcel_reference":"0021"}]

    def index(self):
        return build_index(self.output/"audit.json",[self.output],self.scopes,self.sources)

    def test_new_ocr_reaches_graph_and_search(self):
        r = self.index()
        self.assertEqual(r["counts"]["document_hashes"],2)
        self.assertEqual(r["counts"]["unhashed_resource_rows"],1)
        self.assertEqual(r["counts"]["batch_attempted"],1)
        self.assertEqual(r["counts"]["documents_with_candidate_links"],1)
        self.assertEqual(r["candidate_graph_private"]["edges"][0]["status"],"CANDIDATE")
        result = lookup(r,{"title_reference":"٠٠٠١٢٣٤٥","parcel_reference":"0021","registry_scope":"TN-NABEUL"})
        self.assertEqual(result["counts"],{"SCOPE_MATCH":1})
        self.assertIsNone(result["official_plan_verified"])
        self.assertNotIn("PRIVATE_NAME",json.dumps(r))

    def test_no_cross_observation_pairing(self):
        r = self.index()
        self.assertEqual(lookup(r,{"title_reference":"000777","parcel_reference":"0021"})["matches_private"],[])
        self.assertEqual(lookup(r,{"title_reference":"12345"})["matches_private"],[])

    def test_scope_conflict_retained_and_unscoped_not_promoted(self):
        self.assertEqual(lookup(self.index(),{"title_reference":"00012345","registry_scope":"TN-TUNIS"})["counts"],{"OTHER_SCOPE":1})
        self.scopes={}
        r=self.index()
        self.assertEqual(r["counts"]["documents_with_candidate_links"],0)
        self.assertEqual(lookup(r,{"title_reference":"00012345"})["counts"],{"SCOPE_UNVERIFIED":1})

    def test_missing_batch_result_rejected(self):
        (self.output/"documents"/"000001.json").unlink()
        with self.assertRaisesRegex(ValueError,"BATCH_RESULT_FILES_MISMATCH"):
            self.index()

    def test_resource_binding_rejected(self):
        p=self.output/"documents"/"000001.json"
        row=json.loads(p.read_text())
        row["resource_ids_private"]=["other"]
        p.unlink()
        write_private_json(p,row)
        with self.assertRaisesRegex(ValueError,"DOCUMENT_RESULT_BINDING_MISMATCH"):
            self.index()

    def test_audit_only_retains_unknown(self):
        r=build_index(self.output/"audit.json",[],self.scopes,self.sources)
        self.assertEqual(r["counts"]["document_hashes"],2)
        self.assertEqual(r["counts"]["batch_attempted"],0)
        self.assertEqual(r["counts"]["documents_with_candidate_links"],0)


if __name__=="__main__":
    unittest.main()
