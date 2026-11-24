"""Offline, privacy-preserving, lossless PDF-on-disk inventory contracts."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.atlas_pdf_blob_inventory import scan_pdf_blobs


class AtlasPdfBlobInventoryTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.root = self.base / 'approved'
        self.root.mkdir()
        self.good = b'%PDF-1.4\nMinimal fixture without actual PDF objects\n%%EOF'
        self.good_sha = hashlib.sha256(self.good).hexdigest()
        self.missing_sha = '0' * 64
        self.pdf = self.root / 'good.pdf'
        self.pdf.write_bytes(self.good)
        self.backlog = self.base / 'private.json'
        self._backlog()

    def _backlog(self, *, bad_sha=None):
        data = {'kind': 'ATLAS_PRIVATE_OCR_BACKLOG_CANDIDATES_ONLY',
                'snapshot_kind': 'mainfile_diagnostic', 'live_consistent': False,
                'counts': {'distinct_hashed_pdfs': 2, 'pdf_rows_without_sha': 1},
                'backlog_private': [
                    {'document_sha256_private': bad_sha or self.good_sha,
                     'resource_rows': 2, 'resource_ids_private': ['s1', 's2'],
                     'required_action': 'VERIFY_BLOB_AND_INDEX'}],
                'text_ready_private': [
                    {'document_sha256_private': self.missing_sha,
                     'resource_rows': 1, 'resource_ids_private': ['s3'],
                     'required_action': 'TEXT_AVAILABLE_REVIEW'}],
                'unhashed_pdf_resource_ids_private': ['s4']}
        self.backlog.write_text(json.dumps(data))

    def test_valid_file_source_references_and_absent_stay_distinct(self):
        report = scan_pdf_blobs(self.backlog, self.root)
        self.assertTrue(report['scan_complete_within_selected_root'])
        self.assertFalse(report['catalogue_live_consistent'])
        self.assertEqual(report['counts']['catalogue_resource_references_preserved'], 3)
        self.assertEqual(report['counts']['catalogue_pdf_rows_without_sha_preserved'], 1)
        self.assertEqual(report['counts']['catalogue_hashes_with_matching_bytes'], 1)
        self.assertEqual(report['counts']['catalogue_hashes_with_pdf_header_hint'], 1)
        lookup = {x['document_sha256_private']: x for x in report['catalogue_private']}
        self.assertEqual(lookup[self.good_sha]['resource_ids_private'], ['s1', 's2'])
        self.assertEqual(lookup[self.good_sha]['blob_status'], 'HASH_AND_PDF_HEADER_OBSERVED')
        self.assertEqual(lookup[self.missing_sha]['blob_status'], 'NOT_OBSERVED_IN_THIS_SCAN')
        self.assertNotIn(str(self.root), str(report))

    def test_duplicate_byte_copies_not_deduplicated(self):
        (self.root / 'copy.PDF').write_bytes(self.good)
        report = scan_pdf_blobs(self.backlog, self.root)
        good = next(x for x in report['catalogue_private'] if x['document_sha256_private'] == self.good_sha)
        self.assertEqual(good['sha256_matching_file_count'], 2)
        self.assertEqual(report['counts']['local_pdf_files_hashed'], 2)
        self.assertEqual(len(good['matching_files']), 2)

    def test_unknown_files_preserved_with_hash_and_path_in_private_report(self):
        extra = b'not a PDF but has suffix'
        (self.root / 'strange.pdf').write_bytes(extra)
        result = scan_pdf_blobs(self.backlog, self.root)
        self.assertEqual(result['counts']['local_pdf_files_unmatched_to_catalogue'], 1)
        self.assertEqual(result['unmatched_local_pdf_files_private'][0]['sha256_private'],
                         hashlib.sha256(extra).hexdigest())
        self.assertFalse(result['unmatched_local_pdf_files_private'][0]['pdf_header_hint'])

    def test_symlink_cannot_escape_approved_root(self):
        outside = self.base / 'outside.pdf'
        outside.write_bytes(self.good)
        (self.root / 'link.pdf').symlink_to(outside)
        (self.root / 'loop').symlink_to(self.base, target_is_directory=True)
        report = scan_pdf_blobs(self.backlog, self.root)
        self.assertFalse(report['scan_complete_within_selected_root'])
        self.assertEqual(report['counts']['symlink_files_skipped'], 1)
        self.assertEqual(report['counts']['symlink_dirs_skipped'], 1)
        self.assertEqual(report['counts']['local_pdf_files_hashed'], 1)
        with self.assertRaisesRegex(ValueError, 'APPROVED_REAL_ARCHIVE_ROOT_REQUIRED'):
            scan_pdf_blobs(self.backlog, self.root / 'loop')

    def test_bounded_counts_refuse_partial_report(self):
        with self.assertRaisesRegex(ValueError, 'POSITIVE_SCAN_BOUNDS_REQUIRED'):
            scan_pdf_blobs(self.backlog, self.root, max_files=0)
        (self.root / 'second.pdf').write_bytes(self.good)
        with self.assertRaisesRegex(ValueError, 'MAX_PDF_FILES_EXCEEDED'):
            scan_pdf_blobs(self.backlog, self.root, max_files=1)

    def test_total_byte_limit_is_fail_closed(self):
        with self.assertRaisesRegex(ValueError, 'MAX_TOTAL_BYTES_EXCEEDED'):
            scan_pdf_blobs(self.backlog, self.root, max_total_bytes=1)

    def test_invalid_sha_fails_closed(self):
        self._backlog(bad_sha='not-a-sha')
        with self.assertRaisesRegex(ValueError, 'INVALID_SHA256'):
            scan_pdf_blobs(self.backlog, self.root)

    def test_blob_no_magic_not_official(self):
        bytes_ = b'no magic but bytes match'
        self.pdf.write_bytes(bytes_)
        self._backlog(bad_sha=hashlib.sha256(bytes_).hexdigest())
        report = scan_pdf_blobs(self.backlog, self.root)
        self.assertEqual(report['counts']['catalogue_hashes_with_matching_bytes'], 1)
        self.assertEqual(report['counts']['catalogue_hashes_with_pdf_header_hint'], 0)
        self.assertEqual(next(x for x in report['catalogue_private'] if x['document_sha256_private'] == hashlib.sha256(bytes_).hexdigest())['blob_status'], 'HASH_OBSERVED_NOT_PDF_HEADER')


if __name__ == '__main__':
    unittest.main()
