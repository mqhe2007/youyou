"""Exercise the actual Room query strings against SQLite (no Android runtime required)."""
import pathlib
import re
import sqlite3
import unittest

DAO = (pathlib.Path(__file__).resolve().parents[1] / 'apps/android/app/src/main/java/com/example/youyou_album/data/db/dao/PhotoDao.kt').read_text()


def query(method):
    return re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun ' + method, DAO).group(1)


class HashBackfillSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('CREATE TABLE photos_table (id TEXT PRIMARY KEY, source_type TEXT, source_uri TEXT, content_hash TEXT, modified_at INTEGER, size INTEGER, is_favorite INTEGER)')
        self.db.executemany('INSERT INTO photos_table VALUES (?,?,?,?,?,?,?)', [
            ('a', 'local', 'content://a', None, 1, 10, 0),
            ('b', 'local', 'content://b', '', 1, 10, 0),
            ('c', 'server', None, None, 1, 10, 0),
            ('d', 'local', 'content://d', 'known', 1, 10, 0),
        ])

    def tearDown(self):
        self.db.close()

    def patch(self, **overrides):
        args = dict(id='a', uri='content://a', modifiedAt=1, size=10, hash='sha256')
        args.update(overrides)
        return self.db.execute(query('fillContentHash'), args).rowcount

    def test_paging_skips_remote_known_and_failed_previous_rows(self):
        first = self.db.execute(query('getMissingLocalHashes'), dict(afterId='', limit=1)).fetchall()
        second = self.db.execute(query('getMissingLocalHashes'), dict(afterId=first[0][0], limit=100)).fetchall()
        self.assertEqual([row[0] for row in first + second], ['a', 'b'])

    def test_preserves_concurrent_favorite(self):
        self.db.execute("UPDATE photos_table SET is_favorite=1 WHERE id='a'")
        self.assertEqual(self.patch(), 1)
        self.assertEqual(self.db.execute("SELECT content_hash,is_favorite FROM photos_table WHERE id='a'").fetchone(), ('sha256', 1))

    def test_does_not_resurrect_deleted_row(self):
        self.db.execute("DELETE FROM photos_table WHERE id='a'")
        self.assertEqual(self.patch(), 0)

    def test_rejects_changed_file_and_existing_hash(self):
        self.assertEqual(self.patch(size=11), 0)
        self.assertEqual(self.patch(modifiedAt=2), 0)
        self.assertEqual(self.patch(uri='content://other'), 0)
        self.assertEqual(self.patch(), 1)
        self.assertEqual(self.patch(hash='stale'), 0)


if __name__ == '__main__':
    unittest.main()
