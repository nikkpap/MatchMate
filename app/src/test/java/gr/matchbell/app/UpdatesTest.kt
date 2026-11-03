package gr.matchbell.app

import org.junit.Assert.*
import org.junit.Test

class UpdatesTest {
    private val sha = "a".repeat(64)
    private fun release(tag: String = "v0.3.12", draft: Boolean = false, pre: Boolean = false, asset: String = "MatchMate-0.3.12.apk") = """
        {"tag_name":"$tag","name":"MatchMate 0.3.12","draft":$draft,"prerelease":$pre,
         "html_url":"https://github.com/nikkpap/MatchMate/releases/tag/$tag",
         "body":"Νέα κανάλια\n\nSHA-256: $sha",
         "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":1},
                   {"name":"$asset","browser_download_url":"https://github.com/nikkpap/MatchMate/releases/download/$tag/$asset","size":2600000}]}"""

    @Test fun versionsCompareNumerically() {
        assertTrue(isNewerVersion("0.3.12", "0.3.9"))
        assertTrue(isNewerVersion("v0.3.1", "0.3.0"))
        assertTrue(isNewerVersion("0.4", "0.3.99"))
        assertFalse(isNewerVersion("0.3.0", "0.3.0"))
        assertFalse(isNewerVersion("0.3", "0.3.0"))
        assertFalse(isNewerVersion("0.2.50", "0.3.0"))
        assertTrue(isNewerVersion("1.0.0-preview", "0.9"))
    }
    @Test fun releaseParsesApkAndChecksum() {
        val info = parseRelease(release())!!
        assertEquals("0.3.12", info.version)
        assertTrue(info.apkUrl.endsWith("MatchMate-0.3.12.apk"))
        assertEquals(2600000L, info.apkSize)
        assertEquals(sha, info.sha256)
        assertEquals("Νέα κανάλια", info.notes)
    }
    @Test fun draftsPrereleasesAndApkLessReleasesAreIgnored() {
        assertNull(parseRelease(release(draft = true)))
        assertNull(parseRelease(release(pre = true)))
        assertNull(parseRelease(release(asset = "MatchMate.zip")))
        assertNull(parseRelease("{\"message\":\"Not Found\"}"))
        assertNull(parseRelease("garbage"))
    }
}
