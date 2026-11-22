Fixtures for `run_rar_tests.py`. Every compressed entry carries a CRC32 in its header, which the reader checks, so "ok" means the
decoded bytes are right, not only that decoding ran.

* `rarfile-*.rar`: from https://github.com/markokr/rarfile (test/files, ISC licence).
* `libarchive-*.rar`: from https://github.com/libarchive/libarchive (libarchive/test/*.rar.uu, uudecoded; BSD-2-Clause).
  Several of these are deliberately damaged regression files (bad tables, overflows, truncation); they must fail cleanly.
