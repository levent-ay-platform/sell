"""End-to-end offline Atlas -> Amlak smoke using synthetic documents only.

Verifies real CLI entrypoints without contacting Mac, database, or a server.
Approval is deliberately supplied as a separate synthetic reviewer event.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from scripts.atlas_cadastre_evidence_audit import write_private_json

ROOT=Path(__file__).resolve().parents[2]


def run_tool(module, *arguments, env=None):
    result=subprocess.run(
        [sys.executable,"-m",module,*map(str,arguments)],
        cwd=ROOT,text=True,capture_output=True,timeout=15,
        env={**os.environ,**(env or {})},
        check=False,
    )
    if result.returncode:
        raise AssertionError(f"{module} failed with returncode={result.returncode}: "
                             f"{result.stderr[-1000:]}")
    return json.loads(result.stdout)


class AtlasPipelineE2ESmoke(unittest.TestCase):
    def test_private_cadastre_to_georef_to_reviewed_public_and_bridge(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary)
            db=folder/"archive-synthetic.sqlite"
            connection=sqlite3.connect(db)
            connection.executescript("""
              CREATE TABLE resources (id TEXT, name TEXT, extension TEXT, sha256 TEXT);
              CREATE TABLE documents (sha256 TEXT, state TEXT, page_count INTEGER);
              CREATE TABLE pages (sha256 TEXT, page INTEGER, text TEXT);
              INSERT INTO resources VALUES
                ('synthetic-doc-1','Plan cadastral.pdf','.pdf','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
                ('synthetic-doc-2','Copy of plan.pdf','.pdf','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'),
                ('synthetic-unscanned','unknown.pdf','.pdf','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb');
              INSERT INTO documents VALUES
                ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','indexed',1);
              INSERT INTO pages VALUES
                ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,'Plan cadastral. T.F. 00000555941. Parcelle 0021');
            """)
            connection.commit()
            connection.close()
            audit=folder/"private-audit.json"
            out=run_tool("scripts.atlas_cadastre_evidence_audit",
                         "--db",db,"--output",audit,"--snapshot-kind","mainfile_diagnostic")
            self.assertEqual(out["candidate_documents"],1)
            report=json.loads(audit.read_text(encoding="utf-8"))
            self.assertEqual(report["counts"]["pdf_documents_untriaged_unknown"],1)
            self.assertEqual(report["candidates"][0]["resource_rows"],2)
            self.assertEqual(report["candidates"][0]["title_reference_candidates"],["00000555941"])
            self.assertFalse(report["live_consistent"])

            def wgs(x,y):
                return [10+x*0.00001,36+y*0.00001]
            georef={
                "page_unit":"PIXEL",
                "target_crs":"EPSG:4326",
                "page_ring":[[0,0],[10,0],[10,10],[0,10],[0,0]],
                "control_points":[
                    {"page":[0,0],"wgs84":wgs(0,0),"role":"FIT"},
                    {"page":[100,0],"wgs84":wgs(100,0),"role":"FIT"},
                    {"page":[0,100],"wgs84":wgs(0,100),"role":"FIT"},
                    {"page":[100,100],"wgs84":wgs(100,100),"role":"CHECK"},
                ],
                "maximum_holdout_error_m":2,
            }
            georef_file=folder/"private-controls.json"
            write_private_json(georef_file,georef)
            geometry_report=folder/"private-geometry.json"
            mapped=run_tool("scripts.atlas_geometry_evidence",
                            "--input",georef_file,"--output",geometry_report)
            self.assertEqual(mapped["geometry_status"],"GEOREFERENCED_CANDIDATE")
            self.assertFalse(mapped["ground_verified"])

            # EXPLICIT synthetic reviewer approval; the previous step
            # never produces this legal or ground-verified decision.
            geometry=json.loads(geometry_report.read_text(encoding="utf-8"))
            approved={
                "record_id":"synthetic-reviewed-evidence-1",
                "document_sha256":"a"*64,
                "classification":"OFFICIAL_CADASTRAL",
                "authority_verified":True,
                "title_parcel_link":"VERIFIED",
                "geometry_status":"GROUND_VERIFIED",
                "qa":{"target_crs":"EPSG:4326","fit_points":3,"independent_holdout":1,
                      "holdout_max_error_m":geometry["holdout_max_error_m"]},
                "geometry4326":geometry["geometry4326"],
                "publication":{"manager_approved":True,"geometry_approved":True,
                               "rights_cleared":True,"owner_consented":True,"listing_active":True},
                "source":{"namespace":"ATLAS","source_id":"OTC","layer_id":"plan",
                          "external_id":"example-1","version":"synthetic-v1"},
                "registry_scope":"TN-NABEUL",
                "title_reference":"00000555941",
                "plot_reference":"0021",
                "locality_key":"KELIBIA",
                "private_owner_name":"PRIVATE_SIMULATED_OWNER",
                "private_source_url":"https://do-not-publish.invalid/file.pdf",
            }
            reviews_file=folder/"private-manual-review.json"
            revoked_file=folder/"private-revocations.json"
            write_private_json(reviews_file,[approved])
            write_private_json(revoked_file,{
                "complete":True,"revision":1,"revoked_record_ids":[]})
            public=folder/"public-staging.json"
            private_import=folder/"private-import.json"
            env={"ATLAS_PUBLIC_HMAC_KEY":"synthetic-test-long-hmac-key-never-used-in-real-life-00001"}
            result=run_tool("scripts.atlas_public_projection",
                            "--reviews",reviews_file,"--revocations",revoked_file,
                            "--output",public,env=env)
            self.assertEqual(result["approved_feature_count"],1)
            result=run_tool("scripts.atlas_amlak_private_bridge",
                            "--reviews",reviews_file,"--revocations",revoked_file,
                            "--output",private_import)
            self.assertEqual(result["reviewed_record_count"],1)
            self.assertFalse(result["database_updated"])
            exported=public.read_text(encoding="utf-8")
            self.assertNotIn("PRIVATE_SIMULATED_OWNER",exported)
            self.assertNotIn("00000555941",exported)
            self.assertNotIn("do-not-publish",exported)
            self.assertEqual(public.stat().st_mode & 0o777,0o600)
            dto=json.loads(private_import.read_text(encoding="utf-8"))["records"][0]
            self.assertEqual(dto["titleReference"],"00000555941")
            self.assertEqual(dto["plotReference"],"0021")

            revoked_new=folder/"private-revocations-v2.json"
            write_private_json(revoked_new,{
                "complete":True,"revision":2,
                "revoked_record_ids":["synthetic-reviewed-evidence-1"]})
            withdrawn=folder/"public-withdrawn.json"
            result=run_tool("scripts.atlas_public_projection",
                            "--reviews",reviews_file,"--revocations",revoked_new,
                            "--output",withdrawn,env=env)
            self.assertEqual(result["approved_feature_count"],0)


if __name__=="__main__":
    unittest.main()
