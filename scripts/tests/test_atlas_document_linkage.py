"""Candidate graph tests: scoped provenance, same-page joins, unknown retention."""
from __future__ import annotations

import copy
import unittest

from scripts.atlas_document_linkage import build_candidate_graph


def fixture():
    report={
        "schema_version":3,
        "counts":{"distinct_pdf_sha256":3},
        "review_queue":[
            {"document_sha256":"doc-1","page_reference_evidence_private":[
                {"page":1,"title_reference_candidates":["000014"],
                 "parcel_reference_candidates":["002"]},
                {"page":2,"title_reference_candidates":["999"],
                 "parcel_reference_candidates":[]},
            ]},
            {"document_sha256":"doc-2","page_reference_evidence_private":[
                {"page":1,"title_reference_candidates":["0011"],
                 "parcel_reference_candidates":[]},
                {"page":2,"title_reference_candidates":[],
                 "parcel_reference_candidates":["002"]},
            ]},
        ],
        "unresolved_pdf_documents":[{"document_sha256":"doc-3"}],
    }
    sources=[
        {"namespace":"OTC","service_id":"a","layer_id":"plan","object_id":"1",
         "version":"v1","registry_scope":"TN-NABEUL",
         "title_reference":"000014","parcel_reference":"002"},
        {"namespace":"CRM","service_id":"sales","layer_id":"plots","object_id":"1",
         "version":"v1","registry_scope":"TN-NABEUL",
         "title_reference":"000014","parcel_reference":"002"},
        {"namespace":"OTC","service_id":"a","layer_id":"plan","object_id":"2",
         "version":"v1","registry_scope":"TN-SOUSSE",
         "title_reference":"000014","parcel_reference":"002"},
        {"namespace":"OTC","service_id":"a","layer_id":"plan","object_id":"3",
         "version":"v1","registry_scope":"TN-NABEUL",
         "title_reference":"00014","parcel_reference":"002"},
    ]
    return report,sources


class AtlasCandidateLinkageTest(unittest.TestCase):
    def test_ambiguous_scoped_candidates_keep_all_unlinked(self):
        report,sources=fixture()
        result=build_candidate_graph(report,{"doc-1":"TN-NABEUL"},sources)
        self.assertEqual(result["counts"],{
            "all_document_hashes_retained":3,
            "all_source_rows_retained":4,
            "candidate_edges":2,
            "documents_with_candidates":1,
            "unresolved_documents":2,
            "unlinked_source_records":2,
        })
        self.assertTrue(all(e["status"]=="AMBIGUOUS_CANDIDATE"
                            for e in result["edges"]))
        self.assertTrue(all(e["legally_verified"] is False
                            for e in result["edges"]))
        self.assertEqual(result["verified_legal_title_links"],None)
        self.assertFalse(result["public_export_permitted"])

    def test_document_without_authoritative_scope_stays_unresolved(self):
        report,sources=fixture()
        out=build_candidate_graph(report,{},sources)
        self.assertEqual(out["counts"]["candidate_edges"],0)
        self.assertEqual(out["counts"]["unresolved_documents"],3)
        self.assertTrue(all(row["reason"]=="SCOPE_UNVERIFIED"
                            for row in out["unresolved_documents"]))

    def test_split_references_across_pages_must_not_pair(self):
        report,sources=fixture()
        out=build_candidate_graph(report,{"doc-2":"TN-NABEUL"},sources)
        self.assertEqual(out["counts"]["documents_with_candidates"],0)

    def test_exact_text_does_not_strip_leading_zeros(self):
        report,sources=fixture()
        out=build_candidate_graph(report,{"doc-1":"TN-NABEUL"},sources)
        self.assertNotIn("3", [edge["source_scoped_key"][3] for edge in out["edges"]])

    def test_duplicate_source_key_fails(self):
        report,sources=fixture()
        with self.assertRaisesRegex(ValueError,"DUPLICATE_SOURCE_SCOPED"):
            build_candidate_graph(report,{},sources+[copy.deepcopy(sources[0])])

    def test_unknown_doc_is_not_dropped(self):
        report,sources=fixture()
        out=build_candidate_graph(report,{"doc-1":"TN-NABEUL"},sources)
        self.assertIn("doc-3",[d["document_sha256"] for d in out["unresolved_documents"]])
        self.assertEqual(out["counts"]["all_document_hashes_retained"],3)

    def test_unrecognized_document_scope_rejected(self):
        report,sources=fixture()
        with self.assertRaisesRegex(ValueError,"ANNOTATION_FOR_UNKNOWN"):
            build_candidate_graph(report,{"missing-doc":"TN-NABEUL"},sources)


if __name__=="__main__":
    unittest.main()
